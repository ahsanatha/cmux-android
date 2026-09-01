package dev.cmux.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class CmuxClient implements AutoCloseable {
    private static final class RpcException extends IllegalStateException {
        final String code;
        RpcException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
    interface EventListener {
        void onEvent(String topic, JSONObject payload);
        void onDisconnect(String message);
    }

    private final Supplier<String> accessToken;
    private final EventListener listener;
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private AutoCloseable connection;
    private DataInputStream input;
    private OutputStream output;
    private volatile boolean closed = true;
    private JSONObject hostStatus = new JSONObject();
    private String streamId;
    private final StateSyncMirror stateSync = new StateSyncMirror();
    private volatile boolean stateSyncUnavailable;

    CmuxClient(StackAuthClient auth, EventListener listener) {
        this(() -> {
            try { return auth.accessToken(); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException(error.getMessage(), error); }
        }, listener);
    }

    CmuxClient(Supplier<String> accessToken, EventListener listener) {
        this.accessToken = accessToken;
        this.listener = listener;
    }

    void connect(String host, int port) throws Exception {
        close();
        Socket next = new Socket();
        next.connect(new InetSocketAddress(host, port), 15_000);
        next.setTcpNoDelay(true);
        attach(next, next.getInputStream(), next.getOutputStream());
    }

    void connect(WireConnection next) throws Exception {
        close();
        attach(next, next.input(), next.output());
    }

    private void attach(AutoCloseable next, InputStream nextInput, OutputStream nextOutput)
        throws Exception {
        connection = next;
        input = new DataInputStream(nextInput);
        output = nextOutput;
        closed = false;
        Thread reader = new Thread(this::readLoop, "cmux-mobile-reader");
        reader.setDaemon(true);
        reader.start();
        hostStatus = request("mobile.host.status", new JSONObject());
        subscribe();
    }

    JSONObject request(String method, JSONObject params) throws Exception {
        if (closed) throw new IllegalStateException("Not connected");
        String id = UUID.randomUUID().toString();
        JSONObject envelope = new JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params);
        envelope.put("auth", new JSONObject().put("stack_access_token", accessToken.get()));
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            byte[] payload = envelope.toString().getBytes(StandardCharsets.UTF_8);
            byte[] frame = FrameCodec.encode(payload);
            synchronized (this) {
                output.write(frame);
                output.flush();
            }
            try {
                return response.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        } finally {
            pending.remove(id);
        }
    }

    JSONObject listWorkspaces() throws Exception {
        JSONObject snapshot;
        if (!stateSyncUnavailable) {
            try {
                JSONObject response = request("mobile.sync.fetch", stateSync.fetchParams());
                if (stateSync.applyFetch(response) != StateSyncMirror.Result.GAP && stateSync.ready()) {
                    snapshot = stateSync.projection();
                    return withWorkspaceChangesSummaries(snapshot);
                }
            } catch (RpcException error) {
                if (!"method_not_found".equals(error.code)) throw error;
                stateSyncUnavailable = true;
                stateSync.reset();
            }
        }
        snapshot = request("mobile.workspace.list", new JSONObject());
        return withWorkspaceChangesSummaries(snapshot);
    }

    private JSONObject withWorkspaceChangesSummaries(JSONObject snapshot) {
        if (!supports("workspace.changes.v1")) return snapshot;
        JSONArray workspaces = snapshot.optJSONArray("workspaces");
        if (workspaces == null || workspaces.length() == 0) return snapshot;
        try {
            for (int offset = 0; offset < workspaces.length(); offset += 64) {
                JSONArray ids = new JSONArray();
                for (int i = offset; i < Math.min(offset + 64, workspaces.length()); i++) {
                    JSONObject workspace = workspaces.optJSONObject(i);
                    if (workspace != null && !workspace.optString("id").isBlank()) {
                        ids.put(workspace.getString("id"));
                    }
                }
                if (ids.length() > 0) attachWorkspaceChangesSummaries(snapshot,
                    request("mobile.workspace.changes.summary",
                        new JSONObject().put("workspace_ids", ids)));
            }
        } catch (Exception ignored) {
            // Summary chips are optional; a stale repository must not hide the workspace list.
        }
        return snapshot;
    }

    static void attachWorkspaceChangesSummaries(JSONObject snapshot, JSONObject response)
        throws Exception {
        JSONArray workspaces = snapshot.optJSONArray("workspaces");
        JSONArray summaries = response.optJSONArray("summaries");
        if (workspaces == null || summaries == null) return;
        for (int i = 0; i < summaries.length(); i++) {
            JSONObject summary = summaries.optJSONObject(i);
            if (summary == null || summary.optString("workspace_id").isBlank()
                || !summary.optBoolean("is_repo") || summary.optInt("files_changed") <= 0) continue;
            for (int j = 0; j < workspaces.length(); j++) {
                JSONObject workspace = workspaces.optJSONObject(j);
                if (workspace != null && summary.optString("workspace_id")
                    .equals(workspace.optString("id"))) {
                    workspace.put("_changes_summary", summary);
                    break;
                }
            }
        }
    }

    JSONObject createWorkspace(String title, String workingDirectory, String initialCommand)
        throws Exception {
        return createWorkspace(title, workingDirectory, initialCommand, null);
    }

    JSONObject createWorkspace(String title, String workingDirectory, String initialCommand,
                               String groupId) throws Exception {
        JSONObject params = new JSONObject();
        if (title != null && !title.isBlank()) params.put("title", title.trim());
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            params.put("working_directory", workingDirectory.trim());
        }
        if (initialCommand != null && !initialCommand.isBlank()) {
            params.put("initial_command", initialCommand);
        }
        if (groupId != null && !groupId.isBlank()) params.put("group_id", groupId);
        params.put("operation_id", UUID.randomUUID().toString());
        return request("workspace.create", params);
    }

    JSONObject createWorkspaceGroup(String title) throws Exception {
        JSONObject params = new JSONObject();
        if (title != null && !title.isBlank()) params.put("title", title.trim());
        return request("workspace.group.create", params);
    }

    JSONObject workspaceGroupAction(String groupId, String action, String title) throws Exception {
        JSONObject params = new JSONObject().put("group_id", groupId).put("action", action);
        if (title != null && !title.isBlank()) params.put("title", title.trim());
        return request("workspace.group.action", params);
    }

    JSONObject moveWorkspace(String workspaceId, String windowId, String groupId) throws Exception {
        JSONObject params = workspaceParams(workspaceId, windowId);
        if (groupId != null && !groupId.isBlank()) params.put("group_id", groupId);
        return request("workspace.move", params);
    }

    JSONObject listDirectories(String path, int offset) throws Exception {
        if (path == null || !(path.equals("~") || path.startsWith("~/") || path.startsWith("/"))) {
            throw new IllegalArgumentException("Directory must be an absolute or ~ path");
        }
        return request("mobile.directory.list", new JSONObject()
            .put("path", path).put("offset", Math.max(0, offset)).put("limit", 100));
    }

    JSONObject searchDirectories(String query) throws Exception {
        return request("mobile.directory.search", new JSONObject().put("query", query.trim()));
    }

    JSONObject createTerminal(String workspaceId) throws Exception {
        return request("mobile.terminal.create", new JSONObject()
            .put("workspace_id", workspaceId)
            .put("client_id", "cmux-android"));
    }

    JSONObject workspaceAction(String workspaceId, String windowId, String action,
                               String valueKey, String value) throws Exception {
        JSONObject params = workspaceParams(workspaceId, windowId).put("action", action);
        if (valueKey != null && value != null) params.put(valueKey, value);
        return request("workspace.action", params);
    }

    JSONObject closeWorkspace(String workspaceId, String windowId) throws Exception {
        return request("workspace.close", workspaceParams(workspaceId, windowId));
    }

    JSONObject workspaceChanges(String workspaceId) throws Exception {
        return request("mobile.workspace.changes.files",
            new JSONObject().put("workspace_id", workspaceId));
    }

    JSONObject workspaceDiff(String workspaceId, String path, int maxLines) throws Exception {
        return request("mobile.workspace.changes.file_diff", new JSONObject()
            .put("workspace_id", workspaceId).put("path", path).put("max_lines", maxLines));
    }

    JSONObject workspaceFileStat(String workspaceId, String path, String revision) throws Exception {
        if (!("current".equals(revision) || "base".equals(revision))) {
            throw new IllegalArgumentException("Invalid workspace file revision");
        }
        return request("mobile.workspace.changes.file_stat", new JSONObject()
            .put("workspace_id", workspaceId).put("path", path).put("revision", revision));
    }

    byte[] workspaceFileFetch(String workspaceId, String path, String revision) throws Exception {
        if (!("current".equals(revision) || "base".equals(revision))) {
            throw new IllegalArgumentException("Invalid workspace file revision");
        }
        return artifactFetch("mobile.workspace.changes.file_fetch", new JSONObject()
            .put("workspace_id", workspaceId).put("path", path).put("revision", revision));
    }

    private static JSONObject workspaceParams(String workspaceId, String windowId)
        throws Exception {
        JSONObject params = new JSONObject()
            .put("workspace_id", workspaceId)
            .put("client_id", "cmux-android");
        if (windowId != null && !windowId.isBlank()) params.put("window_id", windowId);
        return params;
    }

    JSONObject attach(String workspaceId, String surfaceId, int maxScrollbackRows) throws Exception {
        return replay(workspaceId, surfaceId, maxScrollbackRows);
    }

    JSONObject notificationFeed() throws Exception {
        return request("notification.feed.list", new JSONObject());
    }

    JSONObject setNotificationsRead(JSONArray ids, boolean read) throws Exception {
        return request(read ? "notification.feed.mark_read" : "notification.feed.mark_unread",
            new JSONObject().put("notification_ids", ids));
    }

    JSONObject markAllNotificationsRead() throws Exception {
        return request("notification.feed.mark_all_read", new JSONObject());
    }

    JSONObject dismissNotifications(JSONArray ids) throws Exception {
        return request("notification.dismiss", new JSONObject()
            .put("notification_ids", ids)
            .put("client_id", "cmux-android"));
    }

    private void subscribe() throws Exception {
        if (!supports("events.v1")) return;
        streamId = UUID.randomUUID().toString();
        request("mobile.events.subscribe", new JSONObject()
            .put("stream_id", streamId)
            .put("topics", new JSONArray()
                .put("workspace.updated")
                .put("terminal.render_grid")
                .put("notification.dismissed")
                .put("notification.badge")
                .put("notification.feed.changed")
                .put("browser.frame")
                .put("browser.state")
                .put("browser.closed")
                .put("browser.dialog")
                .put("browser.dialog.resolved")
                .put("chat.message")
                .put("chat.session")
                .put("mobile.sync.delta"))
            .put("render_grid_anchor", supports("terminal.render_grid.screen_anchor.v1")
                ? "screen" : "viewport"));
    }

    JSONObject listBrowsers(String workspaceId) throws Exception {
        return request("mobile.browser.list", new JSONObject().put("workspace_id", workspaceId));
    }

    JSONObject chatSessions(String workspaceId) throws Exception {
        JSONObject params = new JSONObject();
        if (workspaceId != null && !workspaceId.isBlank()) params.put("workspace_id", workspaceId);
        return request("mobile.chat.sessions", params);
    }

    static List<JSONObject> openableChatSessions(JSONArray sessions) {
        ArrayList<JSONObject> values = new ArrayList<>();
        boolean hasLive = false;
        if (sessions != null) for (int i = 0; i < sessions.length(); i++) {
            JSONObject value = sessions.optJSONObject(i);
            if (value == null || value.optString("session_id").isBlank()) continue;
            values.add(value);
            hasLive |= !"ended".equals(value.optString("state"));
        }
        if (hasLive) values.removeIf(value -> "ended".equals(value.optString("state")));
        values.sort(Comparator
            .comparingInt((JSONObject value) -> chatStateRank(value.optString("state")))
            .thenComparing((JSONObject value) -> value.optString("last_activity_at", ""),
                Comparator.reverseOrder()));
        return values;
    }

    private static int chatStateRank(String state) {
        return "needs_input".equals(state) ? 0 : "working".equals(state) ? 1
            : "idle".equals(state) ? 2 : 3;
    }

    JSONObject chatHistory(String sessionId) throws Exception { return chatHistory(sessionId, null); }

    JSONObject chatHistory(String sessionId, Integer beforeSeq) throws Exception {
        JSONObject params = new JSONObject().put("session_id", sessionId).put("limit", 200);
        if (beforeSeq != null) params.put("before_seq", beforeSeq);
        return request("mobile.chat.history", params);
    }

    JSONObject chatSession(String sessionId) throws Exception {
        return request("mobile.chat.session", new JSONObject().put("session_id", sessionId));
    }

    void chatSend(String sessionId, String text) throws Exception {
        chatSend(sessionId, text, null, null);
    }

    void chatSend(String sessionId, String text, byte[] image, String format) throws Exception {
        JSONObject params = new JSONObject().put("session_id", sessionId).put("text", text);
        if (image != null && format != null) params.put("attachments", new JSONArray().put(
            new JSONObject().put("data_b64", Base64.getEncoder().encodeToString(image))
                .put("format", format)));
        request("mobile.chat.send", params);
    }

    void chatInterrupt(String sessionId, boolean hard) throws Exception {
        request("mobile.chat.interrupt",
            new JSONObject().put("session_id", sessionId).put("hard", hard));
    }

    void chatAnswer(String sessionId, int optionIndex) throws Exception {
        request("mobile.chat.answer", new JSONObject()
            .put("session_id", sessionId).put("option_index", optionIndex));
    }

    JSONObject chatArtifactGallery(String sessionId, String cursor, String query) throws Exception {
        JSONObject params = new JSONObject().put("session_id", sessionId).put("page_size", 60);
        if (cursor != null && !cursor.isBlank()) params.put("cursor", cursor);
        if (query != null && !query.isBlank()) params.put("query", query.trim());
        if (supports("chat.artifact.folders.v1")) params.put("include_directories", true);
        return request("mobile.chat.artifact.gallery", params);
    }

    JSONObject chatArtifactStat(String sessionId, String path) throws Exception {
        return request("mobile.chat.artifact.stat",
            new JSONObject().put("session_id", sessionId).put("path", path));
    }

    byte[] chatArtifactFetch(String sessionId, String path) throws Exception {
        return artifactFetch("mobile.chat.artifact.fetch",
            new JSONObject().put("session_id", sessionId).put("path", path));
    }

    JSONObject chatArtifactThumbnail(String sessionId, String path, int maxDimension)
        throws Exception {
        return request("mobile.chat.artifact.thumbnail", new JSONObject()
            .put("session_id", sessionId).put("path", path).put("max_dimension", maxDimension));
    }

    JSONObject chatArtifactList(String sessionId, String path) throws Exception {
        return request("mobile.chat.artifact.list",
            new JSONObject().put("session_id", sessionId).put("path", path));
    }

    JSONObject terminalArtifactScan(String workspaceId, String surfaceId, boolean visibleOnly)
        throws Exception {
        JSONObject params = new JSONObject().put("workspace_id", workspaceId)
            .put("surface_id", surfaceId).put("include_missing", true);
        if (visibleOnly) params.put("visible_only", true);
        if (supports("terminal.artifact.list.v1")) params.put("include_directories", true);
        return request("mobile.terminal.artifact.scan", params);
    }

    JSONObject terminalArtifactStat(String workspaceId, String surfaceId, String path)
        throws Exception {
        return request("mobile.terminal.artifact.stat", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId).put("path", path));
    }

    byte[] terminalArtifactFetch(String workspaceId, String surfaceId, String path)
        throws Exception {
        return artifactFetch("mobile.terminal.artifact.fetch", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId).put("path", path));
    }

    JSONObject terminalArtifactThumbnail(String workspaceId, String surfaceId, String path,
                                          int maxDimension) throws Exception {
        return request("mobile.terminal.artifact.thumbnail", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("path", path).put("max_dimension", maxDimension));
    }

    JSONObject terminalArtifactList(String workspaceId, String surfaceId, String path)
        throws Exception {
        return request("mobile.terminal.artifact.list", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId).put("path", path));
    }

    private byte[] artifactFetch(String method, JSONObject base) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            JSONObject params = new JSONObject(base.toString()).put("offset", offset)
                .put("length", 256 * 1024);
            JSONObject chunk = request(method, params);
            if (chunk.optLong("offset", -1) != offset) throw new IllegalStateException("Artifact changed while loading");
            byte[] data = Base64.getDecoder().decode(chunk.getString("data_b64"));
            if (bytes.size() + data.length > 64 * 1024 * 1024) {
                throw new IllegalArgumentException("Artifact is larger than 64 MB");
            }
            bytes.write(data);
            offset += data.length;
            if (chunk.optBoolean("eof")) {
                if (chunk.optLong("total_size", offset) != offset) {
                    throw new IllegalStateException("Artifact transfer was incomplete");
                }
                return bytes.toByteArray();
            }
            if (data.length == 0) throw new IllegalStateException("Artifact transfer made no progress");
        }
    }

    JSONObject notificationReconcile(JSONArray deliveredIds) throws Exception {
        return request("notification.reconcile", new JSONObject()
            .put("delivered_ids", deliveredIds).put("client_id", "cmux-android"));
    }

    JSONObject stateSyncFetch(String workspaceEpoch, Long workspaceRevision,
                              String groupEpoch, Long groupRevision) throws Exception {
        JSONArray collections = new JSONArray();
        JSONObject workspaces = new JSONObject().put("id", "workspaces");
        if (workspaceEpoch != null && workspaceRevision != null) {
            workspaces.put("epoch", workspaceEpoch).put("rev", workspaceRevision);
        }
        JSONObject groups = new JSONObject().put("id", "groups");
        if (groupEpoch != null && groupRevision != null) {
            groups.put("epoch", groupEpoch).put("rev", groupRevision);
        }
        return request("mobile.sync.fetch", new JSONObject()
            .put("collections", collections.put(workspaces).put(groups)));
    }

    JSONObject startBrowser(String panelId, int width, int height, double scale)
        throws Exception {
        return request("mobile.browser.stream.start", new JSONObject()
            .put("panel_id", panelId)
            .put("viewport_width", width)
            .put("viewport_height", height)
            .put("viewport_scale", scale));
    }

    void updateBrowserViewport(String panelId, int width, int height, double scale)
        throws Exception {
        request("mobile.browser.viewport", new JSONObject()
            .put("panel_id", panelId)
            .put("viewport_width", width)
            .put("viewport_height", height)
            .put("viewport_scale", scale));
    }

    void stopBrowser(String panelId) throws Exception {
        request("mobile.browser.stream.stop", new JSONObject().put("panel_id", panelId));
    }

    void acknowledgeBrowserFrame(String panelId, long sequence) throws Exception {
        request("mobile.browser.frame.ack", new JSONObject()
            .put("panel_id", panelId).put("seq", sequence));
    }

    void browserPointer(String panelId, double x, double y, String button) throws Exception {
        request("mobile.browser.input.pointer", new JSONObject()
            .put("panel_id", panelId).put("kind", "click")
            .put("x", x).put("y", y).put("click_count", 1).put("button", button));
    }

    void browserScroll(String panelId, double dx, double dy, double x, double y,
                       String phase) throws Exception {
        request("mobile.browser.input.scroll", new JSONObject()
            .put("panel_id", panelId).put("dx", dx).put("dy", dy)
            .put("phase", phase).put("x", x).put("y", y));
    }

    void browserText(String panelId, String text) throws Exception {
        request("mobile.browser.input.text", new JSONObject()
            .put("panel_id", panelId).put("text", text));
    }

    void browserKey(String panelId, String key, JSONArray modifiers) throws Exception {
        request("mobile.browser.input.key", new JSONObject()
            .put("panel_id", panelId).put("key", key).put("modifiers", modifiers));
    }

    void browserNavigate(String panelId, String address) throws Exception {
        request("mobile.browser.navigate", new JSONObject()
            .put("panel_id", panelId).put("url", address));
    }

    void browserCommand(String method, String panelId) throws Exception {
        request(method, new JSONObject().put("panel_id", panelId));
    }

    void browserDialogRespond(String panelId, String dialogId, String buttonId,
                              String text) throws Exception {
        JSONObject params = new JSONObject()
            .put("panel_id", panelId)
            .put("dialog_id", dialogId)
            .put("button_id", buttonId);
        if (text != null) params.put("text", text);
        request("mobile.browser.dialog.respond", params);
    }

    JSONObject replay(String workspaceId, String surfaceId, int maxScrollbackRows) throws Exception {
        JSONObject params = new JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId);
        if (supports("terminal.render_grid.screen_anchor.v1")) {
            params.put("anchor", "screen").put("max_scrollback_rows",
                Math.max(0, Math.min(20_000, maxScrollbackRows)));
        }
        return request("mobile.terminal.replay", params);
    }

    JSONObject viewport(String workspaceId, String surfaceId, int columns, int rows,
                        int generation) throws Exception {
        return request("mobile.terminal.viewport", new JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", "cmux-android")
            .put("viewport_columns", columns)
            .put("viewport_rows", rows)
            .put("viewport_generation", generation));
    }

    void clearViewport(String workspaceId, String surfaceId, int generation) throws Exception {
        request("mobile.terminal.viewport", new JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", "cmux-android")
            .put("clear", true)
            .put("viewport_generation", generation));
    }

    void scroll(String workspaceId, String surfaceId, double lines, int column, int row)
        throws Exception {
        request("mobile.terminal.scroll", new JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", "cmux-android")
            .put("delta_lines", lines)
            .put("col", column)
            .put("row", row));
    }

    boolean supports(String capability) {
        JSONArray capabilities = hostStatus.optJSONArray("capabilities");
        if (capabilities == null) return false;
        for (int i = 0; i < capabilities.length(); i++) {
            if (capability.equals(capabilities.optString(i))) return true;
        }
        return false;
    }

    JSONObject status() {
        return hostStatus;
    }

    void input(String workspaceId, String surfaceId, String text) throws Exception {
        request("mobile.terminal.input", new JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", "cmux-android")
            .put("text", text));
    }

    void paste(String workspaceId, String surfaceId, String text) throws Exception {
        request("terminal.paste", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", "cmux-android").put("text", text)
            .put("submit_key", "return"));
    }

    void pasteImage(String workspaceId, String surfaceId, String imageBase64, String format)
        throws Exception {
        request("terminal.paste_image", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", "cmux-android").put("image_base64", imageBase64)
            .put("image_format", format));
    }

    void terminalMouse(String workspaceId, String surfaceId, int column, int row) throws Exception {
        request("mobile.terminal.mouse", new JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", "cmux-android").put("col", column).put("row", row));
    }

    private void readLoop() {
        String failure = "Disconnected";
        try {
            while (!closed) {
                int length = input.readInt();
                FrameCodec.validateLength(length);
                byte[] payload = new byte[length];
                input.readFully(payload);
                dispatch(new JSONObject(new String(payload, StandardCharsets.UTF_8)));
            }
        } catch (Exception error) {
            if (!closed && error.getMessage() != null) failure = error.getMessage();
        } finally {
            boolean notify = !closed;
            close();
            if (notify) listener.onDisconnect(failure);
        }
    }

    private void dispatch(JSONObject envelope) {
        if ("event".equals(envelope.optString("kind"))) {
            JSONObject payload = envelope.optJSONObject("payload");
            JSONObject eventPayload = payload == null ? new JSONObject() : payload;
            if ("mobile.sync.delta".equals(envelope.optString("topic")) && !stateSyncUnavailable) {
                stateSync.applyDelta(eventPayload);
            }
            listener.onEvent(envelope.optString("topic"), eventPayload);
            return;
        }
        String id = envelope.optString("id");
        CompletableFuture<JSONObject> future = pending.get(id);
        if (future == null) return;
        if (envelope.optBoolean("ok")) {
            JSONObject result = envelope.optJSONObject("result");
            future.complete(result == null ? new JSONObject() : result);
        } else {
            JSONObject error = envelope.optJSONObject("error");
            future.completeExceptionally(new RpcException(
                error == null ? "unknown" : error.optString("code", "unknown"),
                error == null ? "RPC error" : error.optString("message", "RPC error")
            ));
        }
    }

    @Override public synchronized void close() {
        closed = true;
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
        connection = null;
        input = null;
        output = null;
        streamId = null;
        stateSyncUnavailable = false;
        stateSync.reset();
        IllegalStateException error = new IllegalStateException("Disconnected");
        for (CompletableFuture<JSONObject> future : pending.values()) {
            future.completeExceptionally(error);
        }
        pending.clear();
    }
}
