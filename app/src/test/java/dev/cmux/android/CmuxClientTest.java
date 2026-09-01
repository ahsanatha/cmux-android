package dev.cmux.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CmuxClientTest {
    @Test public void attachesOnlyUsefulWorkspaceChangeSummaries() throws Exception {
        JSONObject snapshot = new JSONObject().put("workspaces", new JSONArray()
            .put(new JSONObject().put("id", "one"))
            .put(new JSONObject().put("id", "two")));
        JSONObject response = new JSONObject().put("summaries", new JSONArray()
            .put(new JSONObject().put("workspace_id", "one").put("is_repo", true)
                .put("files_changed", 2).put("additions", 7).put("deletions", 3))
            .put(new JSONObject().put("workspace_id", "two").put("is_repo", true)
                .put("files_changed", 0))
            .put(new JSONObject().put("workspace_id", "missing").put("is_repo", true)
                .put("files_changed", 9)));

        CmuxClient.attachWorkspaceChangesSummaries(snapshot, response);

        assertEquals(2, snapshot.getJSONArray("workspaces").getJSONObject(0)
            .getJSONObject("_changes_summary").getInt("files_changed"));
        assertFalse(snapshot.getJSONArray("workspaces").getJSONObject(1)
            .has("_changes_summary"));
    }

    @Test public void chatSessionsPreferInputAndHideEndedWhileAnythingIsLive() throws Exception {
        JSONArray sessions = new JSONArray()
            .put(new JSONObject().put("session_id", "ended").put("state", "ended")
                .put("last_activity_at", "2026-08-20T10:00:00Z"))
            .put(new JSONObject().put("session_id", "working").put("state", "working")
                .put("last_activity_at", "2026-08-20T10:00:00Z"))
            .put(new JSONObject().put("session_id", "input-old").put("state", "needs_input")
                .put("last_activity_at", "2026-08-20T08:00:00Z"))
            .put(new JSONObject().put("session_id", "input-new").put("state", "needs_input")
                .put("last_activity_at", "2026-08-20T09:00:00Z"));

        List<JSONObject> ordered = CmuxClient.openableChatSessions(sessions);

        assertEquals(List.of("input-new", "input-old", "working"), ordered.stream()
            .map(value -> value.optString("session_id")).toList());
    }

    @Test public void authenticatesSubscribesDispatchesAndClosesPendingRequests() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<JSONObject> requests = new ArrayList<>();
            CountDownLatch event = new CountDownLatch(1);
            CountDownLatch disconnected = new CountDownLatch(1);
            AtomicReference<JSONObject> eventPayload = new AtomicReference<>();
            AtomicReference<String> disconnectReason = new AtomicReference<>();

            Thread host = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    requests.add(status);
                    reply(output, status, new JSONObject()
                        .put("capabilities", new JSONArray().put("events.v1")
                            .put("workspace.actions.v1")
                            .put("terminal.render_grid.screen_anchor.v1")));

                    JSONObject subscribe = read(input);
                    requests.add(subscribe);
                    reply(output, subscribe, new JSONObject().put("subscribed", true));

                    JSONObject sync = read(input);
                    requests.add(sync);
                    error(output, sync, "method_not_found", "Unsupported");

                    JSONObject list = read(input);
                    requests.add(list);
                    reply(output, list, new JSONObject().put("workspaces", new JSONArray()));
                    event(output, "workspace.updated", new JSONObject().put("revision", 7));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }, "fake-cmux-host");
            host.start();

            CmuxClient client = new CmuxClient(() -> "stack-token", new CmuxClient.EventListener() {
                @Override public void onEvent(String topic, JSONObject payload) {
                    if ("workspace.updated".equals(topic)) {
                        eventPayload.set(payload);
                        event.countDown();
                    }
                }

                @Override public void onDisconnect(String message) {
                    disconnectReason.set(message);
                    disconnected.countDown();
                }
            });
            client.connect("127.0.0.1", server.getLocalPort());
            assertTrue(client.supports("workspace.actions.v1"));
            assertFalse(client.supports("browser.stream.v1"));
            assertEquals(0, client.listWorkspaces().getJSONArray("workspaces").length());
            assertTrue(event.await(2, TimeUnit.SECONDS));
            assertEquals(7, eventPayload.get().getInt("revision"));
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
            assertNotNull(disconnectReason.get());
            client.close();
            host.join(2000);

            assertEquals("mobile.host.status", requests.get(0).getString("method"));
            assertEquals("stack-token", requests.get(0).getJSONObject("auth")
                .getString("stack_access_token"));
            JSONObject subscription = requests.get(1);
            assertEquals("mobile.events.subscribe", subscription.getString("method"));
            assertEquals("stack-token", subscription.getJSONObject("auth").getString("stack_access_token"));
            assertEquals("screen", subscription.getJSONObject("params").getString("render_grid_anchor"));
            assertTrue(contains(subscription.getJSONObject("params").getJSONArray("topics"), "terminal.render_grid"));
            assertTrue(contains(subscription.getJSONObject("params").getJSONArray("topics"), "mobile.sync.delta"));
            assertEquals("mobile.sync.fetch", requests.get(2).getString("method"));
            assertEquals("mobile.workspace.list", requests.get(3).getString("method"));
            assertEquals("stack-token", requests.get(3).getJSONObject("auth").getString("stack_access_token"));
        }
    }

    @Test public void fetchesArtifactChunksWithoutSplicingOffsets() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<JSONObject> requests = new ArrayList<>();
            Thread host = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    reply(output, status, new JSONObject().put("capabilities", new JSONArray()));
                    JSONObject first = read(input);
                    requests.add(first);
                    reply(output, first, new JSONObject().put("offset", 0).put("total_size", 5)
                        .put("data_b64", Base64.getEncoder().encodeToString("hel".getBytes()))
                        .put("eof", false));
                    JSONObject second = read(input);
                    requests.add(second);
                    reply(output, second, new JSONObject().put("offset", 3).put("total_size", 5)
                        .put("data_b64", Base64.getEncoder().encodeToString("lo".getBytes()))
                        .put("eof", true));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            host.start();
            CmuxClient client = new CmuxClient(() -> "token", silentListener());
            client.connect("127.0.0.1", server.getLocalPort());
            assertEquals("hello", new String(client.chatArtifactFetch("session", "/tmp/a.txt")));
            client.close();
            host.join(2000);
            assertEquals(0, requests.get(0).getJSONObject("params").getLong("offset"));
            assertEquals(3, requests.get(1).getJSONObject("params").getLong("offset"));
        }
    }

    @Test public void surfacesRpcErrors() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread host = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    reply(output, status, new JSONObject().put("capabilities", new JSONArray()));
                    JSONObject sync = read(input);
                    error(output, sync, "method_not_found", "Unsupported");
                    JSONObject request = read(input);
                    error(output, request, "not_found", "Workspace missing");
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            host.start();
            CmuxClient client = new CmuxClient(() -> "token", silentListener());
            client.connect("127.0.0.1", server.getLocalPort());
            Exception error = assertThrows(Exception.class, client::listWorkspaces);
            assertTrue(rootMessage(error).contains("Workspace missing"));
            client.close();
            host.join(2000);
        }
    }

    @Test public void reportsAndClearsTerminalViewportWithMonotonicGeneration() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<JSONObject> requests = new ArrayList<>();
            Thread host = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    reply(output, status, new JSONObject().put("capabilities",
                        new JSONArray().put("terminal.viewport.v1")));
                    JSONObject viewport = read(input);
                    requests.add(viewport);
                    reply(output, viewport, new JSONObject().put("columns", 80).put("rows", 24));
                    JSONObject clear = read(input);
                    requests.add(clear);
                    reply(output, clear, new JSONObject().put("cleared", true));
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            host.start();
            CmuxClient client = new CmuxClient(() -> "token", silentListener());
            client.connect("127.0.0.1", server.getLocalPort());
            JSONObject effective = client.viewport("workspace", "surface", 100, 30, 7);
            assertEquals(80, effective.getInt("columns"));
            client.clearViewport("workspace", "surface", 8);
            client.close();
            host.join(2000);

            JSONObject report = requests.get(0).getJSONObject("params");
            assertEquals(100, report.getInt("viewport_columns"));
            assertEquals(30, report.getInt("viewport_rows"));
            assertEquals(7, report.getInt("viewport_generation"));
            JSONObject clear = requests.get(1).getJSONObject("params");
            assertTrue(clear.getBoolean("clear"));
            assertEquals(8, clear.getInt("viewport_generation"));
        }
    }

    @Test public void encodesWorkspaceDirectoryChatAndTerminalActions() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<JSONObject> requests = new ArrayList<>();
            Thread host = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    reply(output, status, new JSONObject().put("capabilities", new JSONArray()));
                    for (int i = 0; i < 9; i++) {
                        JSONObject request = read(input);
                        requests.add(request);
                        reply(output, request, new JSONObject());
                    }
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            host.start();
            CmuxClient client = new CmuxClient(() -> "token", silentListener());
            client.connect("127.0.0.1", server.getLocalPort());
            client.createWorkspace("Task", "~/Code", "claude", "group");
            client.createWorkspaceGroup("Roadmap");
            client.workspaceGroupAction("group", "rename", "Work");
            client.moveWorkspace("workspace", "window", "group");
            client.listDirectories("~/Code", 4);
            client.chatAnswer("session", 1);
            client.paste("workspace", "surface", "one\ntwo");
            client.pasteImage("workspace", "surface", "aGVsbG8=", "png");
            client.terminalMouse("workspace", "surface", 12, 7);
            client.close();
            host.join(2000);

            assertEquals("workspace.create", requests.get(0).getString("method"));
            JSONObject create = requests.get(0).getJSONObject("params");
            assertEquals("group", create.getString("group_id"));
            assertFalse(create.getString("operation_id").isBlank());
            assertEquals("workspace.group.create", requests.get(1).getString("method"));
            assertEquals("rename", requests.get(2).getJSONObject("params").getString("action"));
            assertEquals("workspace.move", requests.get(3).getString("method"));
            assertEquals(100, requests.get(4).getJSONObject("params").getInt("limit"));
            assertEquals(1, requests.get(5).getJSONObject("params").getInt("option_index"));
            assertEquals("terminal.paste", requests.get(6).getString("method"));
            assertEquals("return", requests.get(6).getJSONObject("params").getString("submit_key"));
            assertEquals("terminal.paste_image", requests.get(7).getString("method"));
            assertEquals(12, requests.get(8).getJSONObject("params").getInt("col"));
        }
    }

    private static CmuxClient.EventListener silentListener() {
        return new CmuxClient.EventListener() {
            @Override public void onEvent(String topic, JSONObject payload) {}
            @Override public void onDisconnect(String message) {}
        };
    }

    private static JSONObject read(DataInputStream input) throws Exception {
        int size = input.readInt();
        FrameCodec.validateLength(size);
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void reply(DataOutputStream output, JSONObject request, JSONObject result)
        throws Exception {
        write(output, new JSONObject().put("id", request.getString("id"))
            .put("ok", true).put("result", result));
    }

    private static void error(DataOutputStream output, JSONObject request, String code,
                              String message) throws Exception {
        write(output, new JSONObject().put("id", request.getString("id"))
            .put("ok", false).put("error", new JSONObject()
                .put("code", code).put("message", message)));
    }

    private static void event(DataOutputStream output, String topic, JSONObject payload)
        throws Exception {
        write(output, new JSONObject().put("kind", "event").put("topic", topic).put("payload", payload));
    }

    private static void write(DataOutputStream output, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true;
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "" : current.getMessage();
    }
}
