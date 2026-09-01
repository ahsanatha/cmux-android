package dev.cmux.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

public final class MainActivity extends Activity implements CmuxClient.EventListener {
    private static final int SIGN_IN = 0;
    private static final int CONNECT = 1;
    private static final int WORKSPACES = 2;
    private static final int TERMINAL = 3;
    private static final int NOTIFICATIONS = 4;
    private static final int BROWSERS = 5;
    private static final int CHANGES = 6;
    private static final int CHAT = 7;
    private static final int SETTINGS = 8;
    private static final int PICK_TERMINAL_IMAGE = 41;
    private static final int PICK_CHAT_IMAGE = 42;
    private static final int SAVE_ARTIFACT = 43;
    private static final int NOTIFICATION_PERMISSION = 44;
    private static final String NOTIFICATION_CHANNEL = "cmux-agent-activity";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService browserDecoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean terminalUpdatePending = new AtomicBoolean();
    private final AtomicBoolean workspaceRefreshPending = new AtomicBoolean();
    private final AtomicBoolean browserDecodeRunning = new AtomicBoolean();
    private final AtomicBoolean browserScrollRunning = new AtomicBoolean();
    private final AtomicBoolean chatRefreshPending = new AtomicBoolean();
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> reconnectAttemptsByMachine = new HashMap<>();
    private final Map<String, String> machineErrors = new HashMap<>();
    private final Object browserScrollLock = new Object();
    private final RenderGrid grid = new RenderGrid();
    private StackAuthClient auth;
    private MachineRegistry machineRegistry;
    private MachineConnectionManager connections;
    private CmuxClient client;
    private LinearLayout root;
    private TextView status;
    private TextView terminal;
    private View terminalViewport;
    private ScrollView terminalVerticalScroll;
    private String otpNonce;
    private String pendingPairingLink;
    private String workspaceId;
    private String surfaceId;
    private String surfaceTitle = "Terminal";
    private JSONObject workspaceSnapshot;
    private JSONObject notificationSnapshot;
    private JSONObject browserSnapshot;
    private volatile JSONObject pendingBrowserFrame;
    private JSONObject browserState;
    private JSONObject chatSnapshot;
    private JSONObject currentChatSession;
    private ImageView browserImage;
    private Bitmap browserBitmap;
    private EditText browserAddress;
    private String browserPanelId;
    private WebView localBrowser;
    private double browserPageWidth;
    private double browserPageHeight;
    private int browserPixelWidth;
    private int browserPixelHeight;
    private volatile long browserLastFrameSequence = -1;
    private double pendingBrowserScrollX;
    private double pendingBrowserScrollY;
    private double pendingBrowserScrollAnchorX;
    private double pendingBrowserScrollAnchorY;
    private int unreadCount;
    private int currentScreen;
    private int viewportColumns;
    private int viewportRows;
    private int viewportGeneration;
    private int effectiveViewportColumns;
    private int effectiveViewportRows;
    private float terminalTextSize;
    private int terminalScrollbackRows;
    private boolean wrapWorkspaceTitles;
    private boolean attemptedSessionRestore;
    private volatile boolean connecting;
    private int reconnectAttempts;
    private Button reconnectButton;
    private String connectedIrohEndpoint;
    private String activeMachineId;
    private String pendingImageWorkspace;
    private String pendingImageSurface;
    private byte[] pendingChatImage;
    private String pendingChatImageFormat;
    private String pendingChatImageSession;
    private String chatImagePickerSession;
    private String pendingNotificationWorkspace;
    private String pendingNotificationSurface;
    private Button chatAttachmentButton;
    private byte[] pendingArtifactBytes;

    private boolean dark;
    private int background;
    private int surface;
    private int surfaceRaised;
    private int ink;
    private int muted;
    private int outline;
    private int accent;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(new NotificationChannel(
            NOTIFICATION_CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT));
        resolveTheme();
        terminalTextSize = getSharedPreferences("display", MODE_PRIVATE)
            .getFloat("terminal_text_size", 12f);
        terminalScrollbackRows = getSharedPreferences("display", MODE_PRIVATE)
            .getInt("terminal_scrollback_rows", 4000);
        wrapWorkspaceTitles = getSharedPreferences("display", MODE_PRIVATE)
            .getBoolean("wrap_workspace_titles", false);
        auth = new StackAuthClient(new SecureTokenStore(this));
        machineRegistry = new MachineRegistry(getSharedPreferences("machines", MODE_PRIVATE));
        connections = new MachineConnectionManager(this, auth, new MachineConnectionManager.Listener() {
            @Override public void onStateChanged(String machineId, MachineConnectionManager.State state,
                                                  String message) {
                runOnUiThread(() -> onMachineStateChanged(machineId, state, message));
            }

            @Override public void onEvent(String machineId, String topic, JSONObject payload) {
                if (machineId.equals(activeMachineId)) MainActivity.this.onEvent(topic, payload);
                else onBackgroundMachineEvent(machineId, topic, payload);
            }

            @Override public void onDisconnected(String machineId, String message) {
                runOnUiThread(() -> onMachineDisconnected(machineId, message));
            }
        });
        if (state != null) {
            otpNonce = state.getString("otp_nonce");
            pendingPairingLink = state.getString("pairing_link");
        }
        capturePairingIntent(getIntent());
        captureNotificationIntent(getIntent());
        if (auth.hasSession()) showConnect(); else showSignIn();
        reconnectHandler.postDelayed(
            () -> updateChecker().checkSilently(), 4_000);
    }

    private UpdateChecker updateCheckerInstance;
    private UpdateChecker updateChecker() {
        if (updateCheckerInstance == null) updateCheckerInstance = new UpdateChecker(this);
        return updateCheckerInstance;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("otp_nonce", otpNonce);
        out.putString("pairing_link", pendingPairingLink);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        capturePairingIntent(intent);
        captureNotificationIntent(intent);
        if (client != null && workspaceSnapshot != null && pendingNotificationWorkspace != null) {
            openPendingNotification();
        } else if (auth != null && auth.hasSession()) showConnect();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == SAVE_ARTIFACT) {
            byte[] bytes = pendingArtifactBytes;
            pendingArtifactBytes = null;
            if (bytes == null) return;
            worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) throw new IllegalStateException("The selected file is unavailable");
                    output.write(bytes);
                    runOnUiThread(() -> message("File saved"));
                } catch (Exception error) {
                    runOnUiThread(() -> message(errorMessage(error)));
                }
            });
            return;
        }
        if (requestCode != PICK_TERMINAL_IMAGE && requestCode != PICK_CHAT_IMAGE) return;
        String selectedWorkspace = pendingImageWorkspace;
        String selectedSurface = pendingImageSurface;
        boolean chatImage = requestCode == PICK_CHAT_IMAGE;
        android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("Image is unavailable");
                byte[] buffer = new byte[16_384];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > 8 * 1024 * 1024) throw new IllegalArgumentException("Image must be 8 MB or smaller");
                    output.write(buffer, 0, read);
                }
                String mime = getContentResolver().getType(uri);
                String format = mime != null && mime.contains("jpeg") ? "jpg"
                    : mime != null && mime.contains("webp") ? "webp" : "png";
                if (chatImage) {
                    String sessionId = chatImagePickerSession;
                    chatImagePickerSession = null;
                    if (sessionId == null) throw new IllegalStateException("The chat session was closed");
                    pendingChatImage = output.toByteArray();
                    pendingChatImageFormat = "jpg".equals(format) ? "jpeg" : format;
                    pendingChatImageSession = sessionId;
                    runOnUiThread(() -> {
                        if (currentChatSession != null
                            && sessionId.equals(currentChatSession.optString("session_id"))) {
                            if (chatAttachmentButton != null) chatAttachmentButton.setText("Image attached");
                            message("Image attached. Send your message when ready.");
                        }
                    });
                } else {
                    client.pasteImage(selectedWorkspace, selectedSurface,
                        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), format);
                    runOnUiThread(() -> message("Image sent to terminal"));
                }
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            }
        });
    }

    @Override protected void onDestroy() {
        reconnectHandler.removeCallbacksAndMessages(null);
        clearTerminalViewport();
        if (localBrowser != null) {
            localBrowser.destroy();
            localBrowser = null;
        }
        if (connections != null) connections.close();
        if (client != null) client.close();
        worker.shutdownNow();
        browserDecoder.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onPause() {
        if (localBrowser != null) localBrowser.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (localBrowser != null) localBrowser.onResume();
    }

    @Override public void onBackPressed() {
        if (currentScreen == BROWSERS && localBrowser != null && localBrowser.canGoBack()) {
            localBrowser.goBack();
        } else if (currentScreen == BROWSERS && (browserPanelId != null || localBrowser != null)
            && browserSnapshot != null) {
            showBrowsers(browserSnapshot);
        } else if ((currentScreen == TERMINAL || currentScreen == NOTIFICATIONS || currentScreen == BROWSERS
            || currentScreen == CHANGES || currentScreen == CHAT || currentScreen == SETTINGS)
            && workspaceSnapshot != null) {
            showWorkspaces(workspaceSnapshot);
        } else if (currentScreen == WORKSPACES) {
            showConnect();
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (currentScreen == BROWSERS && browserPanelId != null
            && event.getAction() == KeyEvent.ACTION_DOWN && !(getCurrentFocus() instanceof EditText)) {
            String key = browserKeyToken(event);
            if (key != null) {
                sendBrowserKey(key, browserModifiers(event));
                return true;
            }
        }
        if (currentScreen != TERMINAL || event.getAction() != KeyEvent.ACTION_DOWN
            || getCurrentFocus() instanceof EditText) {
            return super.dispatchKeyEvent(event);
        }
        String input = terminalKey(event);
        if (input == null) return super.dispatchKeyEvent(event);
        sendInput(input);
        return true;
    }

    private void showSignIn() {
        currentScreen = SIGN_IN;
        screen("cmux", "Your Mac terminals, wherever you are.");
        sectionLabel("Sign in");
        EditText email = field("Email address", "you@example.com",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        Button send = button("Send verification code");
        EditText code = field("Verification code", "6-character code",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        Button verify = button("Continue");
        code.setVisibility(otpNonce == null ? View.GONE : View.VISIBLE);
        verify.setVisibility(code.getVisibility());

        send.setOnClickListener(view -> {
            String value = email.getText().toString().trim();
            if (!value.contains("@")) {
                message("Enter a valid email address.");
                return;
            }
            busy(send, true, "Sending…");
            worker.execute(() -> {
                try {
                    otpNonce = auth.sendCode(value);
                    runOnUiThread(() -> {
                        busy(send, false, "Resend code");
                        code.setVisibility(View.VISIBLE);
                        verify.setVisibility(View.VISIBLE);
                        message("Code sent. Check your email.");
                        code.requestFocus();
                    });
                } catch (Exception error) {
                    fail(send, "Send verification code", error);
                }
            });
        });

        verify.setOnClickListener(view -> {
            String value = code.getText().toString().trim();
            if (otpNonce == null || value.isEmpty()) {
                message("Request and enter the email code first.");
                return;
            }
            busy(verify, true, "Verifying…");
            worker.execute(() -> {
                try {
                    auth.verifyCode(value, otpNonce);
                    otpNonce = null;
                    runOnUiThread(this::showConnect);
                } catch (Exception error) {
                    fail(verify, "Continue", error);
                }
            });
        });
    }

    private void showConnect() {
        currentScreen = CONNECT;
        screen("Connect to Macs", "Choose any Mac on your cmux account or add a Tailscale host.");
        Button findMacs = button("Find my Macs");
        reconnectButton = findMacs;
        findMacs.setOnClickListener(view -> discoverMacs(findMacs));
        activeMachineId = getSharedPreferences("connection", MODE_PRIVATE)
            .getString("active_machine_id", activeMachineId);
        java.util.List<MachineRegistry.Machine> savedMachines = machineRegistry.list();
        if (!savedMachines.isEmpty()) {
            sectionLabel("Saved Macs");
            for (MachineRegistry.Machine machine : savedMachines) {
                Button saved = secondaryButton(machine.displayName() + " · " + machineStatusLabel(machine));
                saved.setOnClickListener(view -> connectMachine(machine, saved, false));
            }
            if (savedMachines.size() > 1) {
                Button connectAll = secondaryButton("Connect all saved Macs");
                connectAll.setOnClickListener(view -> connectAllMachines(savedMachines, connectAll));
            }
        }
        sectionLabel("Private network");
        SharedPreferences prefs = getSharedPreferences("connection", MODE_PRIVATE);
        EditText host = field("Tailscale IP", "100.x.x.x", InputType.TYPE_CLASS_TEXT);
        host.setText(prefs.getString("host", ""));
        EditText port = field("Port", "58465", InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(prefs.getInt("port", 58465)));
        Button connect = button("Connect");
        sectionLabel("Pairing link");
        EditText pairing = field("Pairing link", "cmux-ios://attach?…",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (pendingPairingLink != null) pairing.setText(pendingPairingLink);
        Button usePairing = secondaryButton("Use pairing link");
        Button scanPairing = secondaryButton("Scan QR code");
        Button checkUpdates = secondaryButton("Check for updates");
        Button signOut = secondaryButton("Sign out");

        TextView privacy = plainText("Encrypted account proof; terminal traffic stays on your Tailscale network.", 13, muted);
        LinearLayout.LayoutParams privacyParams = (LinearLayout.LayoutParams) privacy.getLayoutParams();
        privacyParams.setMargins(0, dp(22), 0, 0);
        privacy.setLayoutParams(privacyParams);

        connect.setOnClickListener(view -> {
            String address = host.getText().toString().trim();
            int portNumber;
            try { portNumber = Integer.parseInt(port.getText().toString()); }
            catch (NumberFormatException error) { portNumber = -1; }
            if (address.isEmpty() || address.contains(" ") || portNumber < 1 || portNumber > 65535) {
                message("Enter a valid host and port.");
                return;
            }
            connectToMac(address, portNumber, connect);
        });
        usePairing.setOnClickListener(view -> connectPairing(pairing.getText().toString(), usePairing));
        scanPairing.setOnClickListener(view -> {
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(code -> {
                    String value = code.getRawValue();
                    if (value == null || value.isBlank()) {
                        message("That QR code is empty.");
                        return;
                    }
                    pairing.setText(value);
                    connectPairing(value, scanPairing);
                })
                .addOnFailureListener(error -> message("Could not scan QR code: " + error.getMessage()));
        });
        checkUpdates.setOnClickListener(view -> updateChecker().checkManually());
        signOut.setOnClickListener(view -> {
            connectedIrohEndpoint = null;
            reconnectHandler.removeCallbacksAndMessages(null);
            if (connections != null) connections.disconnectAll();
            client = null;
            machineRegistry.clear();
            activeMachineId = null;
            auth.signOut();
            showSignIn();
        });
        if (pendingPairingLink != null) {
            connectPairing(pendingPairingLink, usePairing);
        } else if (!attemptedSessionRestore) {
            attemptedSessionRestore = true;
            String endpoint = prefs.getString("iroh_endpoint", null);
            String account = prefs.getString("iroh_account", null);
            try {
                MachineRegistry.Machine saved = findMachine(activeMachineId);
                if (saved != null && auth.accountFingerprint().equals(account)) {
                    connectMachine(saved, findMacs, true);
                } else if (endpoint != null && auth.accountFingerprint().equals(account)) {
                    MachineRegistry.Machine legacy = MachineRegistry.Machine.iroh(endpoint, "Mac");
                    machineRegistry.upsert(legacy);
                    connectMachine(legacy, findMacs, true);
                } else {
                    String legacyHost = prefs.getString("host", null);
                    int legacyPort = prefs.getInt("port", -1);
                    if (legacyHost != null && legacyPort > 0 && auth.accountFingerprint().equals(account)) {
                        MachineRegistry.Machine legacy = MachineRegistry.Machine.tcp(
                            legacyHost, legacyPort, legacyHost);
                        machineRegistry.upsert(legacy);
                        connectMachine(legacy, findMacs, true);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private MachineRegistry.Machine findMachine(String id) {
        if (id == null || id.isBlank()) return null;
        for (MachineRegistry.Machine machine : machineRegistry.list()) {
            if (id.equals(machine.id())) return machine;
        }
        return null;
    }

    private void connectMachine(MachineRegistry.Machine machine, Button source, boolean automatic) {
        if (machine.isIroh()) {
            connectIroh(machine.endpointId(), source, automatic);
        } else {
            connectToRoutes(java.util.List.of(
                new PairingLink.Route(machine.host(), machine.port())), source, automatic);
        }
    }

    private void discoverMacs(Button source) {
        busy(source, true, "Finding Macs…");
        worker.execute(() -> {
            try {
                JSONArray macs = IrohWireConnection.discoverMacs(getApplicationContext(), auth);
                for (int i = 0; i < macs.length(); i++) {
                    JSONObject mac = macs.optJSONObject(i);
                    if (mac == null) continue;
                    try {
                        machineRegistry.upsert(MachineRegistry.Machine.iroh(
                            mac.getString("endpoint_id"), mac.optString("display_name", "Mac")));
                    } catch (Exception ignored) {
                        // Discovery entries are untrusted; leave invalid ones unselectable.
                    }
                }
                runOnUiThread(() -> {
                    busy(source, false, "Find my Macs");
                    if (macs.length() == 0) {
                        message("No pairable Mac is online. Open cmux → Mobile Connect on your Mac.");
                        return;
                    }
                    String[] labels = new String[macs.length()];
                    for (int i = 0; i < macs.length(); i++) {
                        JSONObject mac = macs.optJSONObject(i);
                        String name = mac == null ? "Mac" : mac.optString("display_name", "Mac");
                        String tag = mac == null ? "" : mac.optString("tag");
                        labels[i] = tag.isBlank() ? name : name + " · " + tag;
                    }
                    new AlertDialog.Builder(this).setTitle("Your Macs").setItems(labels,
                        (dialog, which) -> {
                            JSONObject mac = macs.optJSONObject(which);
                            if (mac != null) {
                                try {
                                    MachineRegistry.Machine machine = MachineRegistry.Machine.iroh(
                                        mac.getString("endpoint_id"), mac.optString("display_name", "Mac"));
                                    connectMachine(machine, source, false);
                                } catch (Exception error) {
                                    message("That Mac entry is invalid.");
                                }
                            }
                        }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception error) {
                fail(source, "Find my Macs", error);
            }
        });
    }

    private void capturePairingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            pendingPairingLink = intent.getData().toString();
        }
    }

    private void captureNotificationIntent(Intent intent) {
        if (intent == null) return;
        String workspace = intent.getStringExtra("notification_workspace_id");
        String surface = intent.getStringExtra("notification_surface_id");
        if (safeIdentifier(workspace)) pendingNotificationWorkspace = workspace;
        if (safeIdentifier(surface)) pendingNotificationSurface = surface;
        intent.removeExtra("notification_workspace_id");
        intent.removeExtra("notification_surface_id");
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.length() <= 128 && !value.isBlank()
            && value.chars().noneMatch(Character::isISOControl);
    }

    private void connectPairing(String link, Button source) {
        try {
            String endpointId = PairingLink.parseIrohEndpointId(link);
            pendingPairingLink = null;
            connectIroh(endpointId, source);
            return;
        } catch (IllegalArgumentException ignored) {
            // Not a valid v3 link; legacy parsing below provides the user-facing error.
        }
        try {
            var routes = PairingLink.parse(link);
            pendingPairingLink = null;
            connectToRoutes(routes, source);
        } catch (Exception error) {
            message(error.getMessage() == null ? "This cmux pairing link is invalid." : error.getMessage());
        }
    }

    private void connectIroh(String endpointId, Button source) {
        connectIroh(endpointId, source, false);
    }

    private void connectIroh(String endpointId, Button source, boolean automatic) {
        String retryLabel = source.getText().toString();
        connecting = true;
        busy(source, true, "Connecting…");
        worker.execute(() -> {
            try {
                MachineRegistry.Machine candidate = MachineRegistry.Machine.iroh(endpointId, "Mac");
                JSONObject snapshot = connections.connect(candidate);
                CmuxClient connected = connections.client(candidate.id());
                if (connected == null) throw new IllegalStateException("Mac connection disappeared");
                JSONObject host = connected.status();
                MachineRegistry.Machine machine = candidate.named(
                    host.optString("mac_display_name", "Mac"));
                machineRegistry.upsert(machine);
                connecting = false;
                reconnectAttempts = 0;
                reconnectAttemptsByMachine.remove(machine.id());
                runOnUiThread(() -> activateMachine(machine, snapshot));
            } catch (Exception error) {
                connecting = false;
                if (automatic) scheduleReconnect(errorMessage(error));
                else fail(source, retryLabel, error);
            }
        });
    }

    private void scheduleReconnect(String reason) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || !auth.hasSession()) return;
            showConnect();
            SharedPreferences preferences = getSharedPreferences("connection", MODE_PRIVATE);
            MachineRegistry.Machine machine = findMachine(activeMachineId);
            String account = preferences.getString("iroh_account", null);
            try {
                if (machine == null || !auth.accountFingerprint().equals(account)) {
                    message("Disconnected: " + reason);
                    return;
                }
            } catch (Exception error) {
                message("Disconnected: " + reason);
                return;
            }
            int delaySeconds = Math.min(30, 1 << Math.min(reconnectAttempts, 5));
            reconnectAttempts++;
            message("Reconnecting in " + delaySeconds + "s…");
            reconnectHandler.removeCallbacksAndMessages(null);
            reconnectHandler.postDelayed(() -> {
                if (connections.state(machine.id()) != MachineConnectionManager.State.CONNECTED
                    && connections.state(machine.id()) != MachineConnectionManager.State.CONNECTING
                    && currentScreen == CONNECT && reconnectButton != null) {
                    connectMachine(machine, reconnectButton, true);
                }
            }, delaySeconds * 1000L);
        });
    }

    private void onMachineStateChanged(String machineId, MachineConnectionManager.State state,
                                       String message) {
        if (state == MachineConnectionManager.State.CONNECTED) machineErrors.remove(machineId);
        else if (message != null && !message.isBlank()) machineErrors.put(machineId, message);
        if (machineId.equals(activeMachineId) && status != null
            && state != MachineConnectionManager.State.CONNECTED) {
            message(currentMachineLabel() + ": " + message);
        }
    }

    private void onMachineDisconnected(String machineId, String reason) {
        if (machineId.equals(activeMachineId)) scheduleReconnect(reason);
    }

    private void onBackgroundMachineEvent(String machineId, String topic, JSONObject payload) {
        if (!"notification.badge".equals(topic)) return;
        int count = Math.max(0, payload.optInt("unread_count", 0));
        if (count == 0) machineErrors.remove(machineId);
        else machineErrors.put(machineId, count + " unread notification" + (count == 1 ? "" : "s"));
    }

    private String machineStatusLabel(MachineRegistry.Machine machine) {
        MachineConnectionManager.State state = connections.state(machine.id());
        if (state == MachineConnectionManager.State.CONNECTED) return "online";
        if (state == MachineConnectionManager.State.CONNECTING) return "connecting";
        if (state == MachineConnectionManager.State.ERROR) return "offline";
        return "saved";
    }

    private void connectAllMachines(java.util.List<MachineRegistry.Machine> machines, Button source) {
        busy(source, true, "Connecting Macs…");
        worker.execute(() -> {
            int connectedCount = 0;
            MachineRegistry.Machine first = null;
            JSONObject firstSnapshot = null;
            try {
                for (MachineRegistry.Machine machine : machines) {
                    try {
                        JSONObject snapshot = connections.connect(machine);
                        connectedCount++;
                        if (first == null) {
                            first = machine;
                            firstSnapshot = snapshot;
                        }
                    } catch (Exception ignored) {}
                }
                MachineRegistry.Machine active = findMachine(activeMachineId);
                JSONObject activeSnapshot = active == null ? null : connections.snapshot(active.id());
                if (active != null && activeSnapshot != null) {
                    first = active;
                    firstSnapshot = activeSnapshot;
                }
                MachineRegistry.Machine selected = first;
                JSONObject selectedSnapshot = firstSnapshot;
                int total = connectedCount;
                runOnUiThread(() -> {
                    busy(source, false, "Connect all saved Macs");
                    if (selected != null) activateMachine(selected, selectedSnapshot);
                    message(total + " Mac" + (total == 1 ? "" : "s") + " connected");
                });
            } catch (Exception error) {
                fail(source, "Connect all saved Macs", error);
            }
        });
    }

    private void connectToMac(String address, int port, Button source) {
        connectToRoutes(java.util.List.of(new PairingLink.Route(address, port)), source, false);
    }

    private void connectToRoutes(java.util.List<PairingLink.Route> routes, Button source) {
        connectToRoutes(routes, source, false);
    }

    private void connectToRoutes(java.util.List<PairingLink.Route> routes, Button source,
                                 boolean automatic) {
        String retryLabel = source.getText().toString();
        connecting = true;
        busy(source, true, "Connecting…");
        worker.execute(() -> {
            try {
                Exception last = null;
                for (PairingLink.Route route : routes) {
                    try {
                        MachineRegistry.Machine routeMachine = MachineRegistry.Machine.tcp(
                            route.host(), route.port(), route.host());
                        JSONObject snapshot = connections.connect(routeMachine);
                        CmuxClient connected = connections.client(routeMachine.id());
                        if (connected == null) throw new IllegalStateException("Mac connection disappeared");
                        connectedIrohEndpoint = null;
                        MachineRegistry.Machine machine = routeMachine.named(
                            connected.status().optString("mac_display_name", route.host()));
                        machineRegistry.upsert(machine);
                        connecting = false;
                        runOnUiThread(() -> activateMachine(machine, snapshot));
                        return;
                    } catch (Exception error) {
                        last = error;
                    }
                }
                throw last == null ? new IllegalStateException("No pairing routes") : last;
            } catch (Exception error) {
                connecting = false;
                if (automatic) scheduleReconnect(errorMessage(error));
                else fail(source, retryLabel, error);
            }
        });
    }

    private void activateMachine(MachineRegistry.Machine machine, JSONObject snapshot) {
        clearTerminalViewport();
        activeMachineId = machine.id();
        client = connections.client(machine.id());
        workspaceSnapshot = snapshot;
        SharedPreferences.Editor preferences = getSharedPreferences("connection", MODE_PRIVATE).edit()
            .putString("active_machine_id", machine.id());
        try {
            preferences.putString("iroh_account", auth.accountFingerprint());
        } catch (Exception ignored) {}
        if (machine.isIroh()) {
            preferences.remove("host").remove("port")
                .putString("iroh_endpoint", machine.endpointId());
        } else {
            preferences.remove("iroh_endpoint")
                .putString("host", machine.host()).putInt("port", machine.port());
        }
        preferences.apply();
        showWorkspaces(snapshot);
    }

    private void showWorkspaces(JSONObject snapshot) {
        clearTerminalViewport();
        currentScreen = WORKSPACES;
        screen("Workspaces", "Running terminals on " + currentMachineLabel() + ".");
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button switchMac = smallButton("Mac: " + bounded(currentMachineLabel(), 18));
        Button create = smallButton("+ New workspace");
        Button createGroup = smallButton("+ Group");
        Button refresh = smallButton("Refresh");
        actions.addView(switchMac, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        createParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(create, createParams);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        groupParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(createGroup, groupParams);
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        refreshParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(refresh, refreshParams);
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        root.addView(actionScroll, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(12)));
        switchMac.setOnClickListener(view -> showMachinePicker());
        create.setOnClickListener(view -> showCreateWorkspaceDialog());
        createGroup.setVisibility(client != null && client.supports("workspace.group_create.v1")
            ? View.VISIBLE : View.GONE);
        createGroup.setOnClickListener(view -> showCreateGroupDialog());
        refresh.setOnClickListener(view -> {
            busy(refresh, true, "Refreshing…");
            worker.execute(() -> {
                try {
                    workspaceSnapshot = client.listWorkspaces();
                    runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
                } catch (Exception error) {
                    fail(refresh, "Refresh", error);
                }
            });
        });

        JSONArray workspaces = snapshot.optJSONArray("workspaces");
        if (workspaces == null || workspaces.length() == 0) {
            emptyState("No open workspaces", "Start a terminal in cmux on your Mac, then refresh.");
            return;
        }
        EditText search = field("Search workspaces", "Name, directory, or terminal",
            InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        java.util.ArrayList<View> workspaceBlocks = new java.util.ArrayList<>();
        java.util.ArrayList<View> workspaceContents = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> workspaceCollapsed = new java.util.ArrayList<>();
        java.util.ArrayList<String> workspaceTerms = new java.util.ArrayList<>();
        String lastGroup = null;
        for (int i = 0; i < workspaces.length(); i++) {
            JSONObject workspace = workspaces.optJSONObject(i);
            if (workspace == null) continue;
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            String group = workspace.optString("group_id", "");
            JSONObject groupObject = findGroup(snapshot, group);
            if (!group.isEmpty() && !group.equals(lastGroup)) {
                block.addView(workspaceGroupHeader(groupObject), marginParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, dp(14), 0, 0));
            }
            lastGroup = group;
            boolean collapsed = groupObject != null && groupObject.optBoolean("is_collapsed");
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            content.addView(workspaceHeader(workspace), marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, dp(16), 0, dp(2)));
            JSONArray terminals = workspace.optJSONArray("terminals");
            StringBuilder terms = new StringBuilder(workspace.optString("title")).append(' ')
                .append(workspace.optString("description")).append(' ')
                .append(workspace.optString("current_directory")).append(' ')
                .append(groupObject == null ? "" : groupObject.optString("name"));
            if (terminals != null) for (int j = 0; j < terminals.length(); j++) {
                    JSONObject item = terminals.optJSONObject(j);
                    if (item == null || !item.optBoolean("is_ready", true)) continue;
                    String title = item.optString("title", "Terminal");
                    terms.append(' ').append(title);
                    View row = terminalRow(title);
                    String selectedWorkspace = workspace.optString("id");
                    String selectedSurface = item.optString("id");
                    row.setOnClickListener(view ->
                        openTerminal(selectedWorkspace, selectedSurface, title, row));
                    content.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(6), 0, 0));
            }
            Button addTerminal = smallButton("+ New terminal");
            String selectedWorkspace = workspace.optString("id");
            addTerminal.setOnClickListener(view -> createTerminal(selectedWorkspace, addTerminal));
            content.addView(addTerminal, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44), 0, dp(7), 0, 0));
            block.addView(content);
            root.addView(block);
            workspaceBlocks.add(block);
            workspaceContents.add(content);
            workspaceCollapsed.add(collapsed);
            workspaceTerms.add(terms.toString().toLowerCase(java.util.Locale.ROOT));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                String query = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for (int i = 0; i < workspaceBlocks.size(); i++) {
                    boolean matches = query.isEmpty() || workspaceTerms.get(i).contains(query);
                    workspaceBlocks.get(i).setVisibility(matches ? View.VISIBLE : View.GONE);
                    workspaceContents.get(i).setVisibility(matches
                        && (!query.isEmpty() || !workspaceCollapsed.get(i)) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        openPendingNotification();
    }

    private String currentMachineLabel() {
        MachineRegistry.Machine machine = findMachine(activeMachineId);
        return machine == null ? "Mac" : machine.displayName();
    }

    private void showMachinePicker() {
        java.util.List<MachineRegistry.Machine> machines = machineRegistry.list();
        if (machines.isEmpty()) {
            showConnect();
            return;
        }
        String[] labels = new String[machines.size()];
        for (int i = 0; i < machines.size(); i++) {
            MachineRegistry.Machine machine = machines.get(i);
            labels[i] = machine.displayName()
                + " · " + machineStatusLabel(machine)
                + (machineErrors.containsKey(machine.id())
                    ? " · " + bounded(machineErrors.get(machine.id()), 80) : "");
        }
        new AlertDialog.Builder(this).setTitle("Switch Mac").setItems(labels, (dialog, which) -> {
            MachineRegistry.Machine machine = machines.get(which);
            if (!machine.id().equals(activeMachineId)) connectMachine(machine,
                reconnectButton == null ? smallButton("Connect") : reconnectButton, false);
        }).setNegativeButton("Cancel", null)
            .setPositiveButton("Add Mac", (dialog, which) -> showConnect()).show();
    }

    private static String groupName(JSONObject snapshot, String id) {
        JSONObject group = findGroup(snapshot, id);
        return group == null ? "Group" : group.optString("name", "Group");
    }

    private static JSONObject findGroup(JSONObject snapshot, String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray groups = snapshot.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null && id.equals(group.optString("id"))) return group;
        }
        return null;
    }

    private void showSettings() {
        currentScreen = SETTINGS;
        JSONObject host = client == null ? new JSONObject() : client.status();
        screen("Settings", "Display and connected Mac details.");
        Button back = smallButton("‹ Workspaces");
        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        root.addView(back, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), 0, 0, 0, dp(14)));
        sectionLabel("Terminal text");
        TextView size = plainText(String.format(java.util.Locale.ROOT, "%.0f sp", terminalTextSize), 15, muted);
        LinearLayout zoom = new LinearLayout(this);
        Button smaller = smallButton("Smaller");
        Button larger = smallButton("Larger");
        Button reset = smallButton("Reset");
        zoom.addView(smaller, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        zoomParams.setMargins(dp(8), 0, 0, 0);
        zoom.addView(larger, zoomParams);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        resetParams.setMargins(dp(8), 0, 0, 0);
        zoom.addView(reset, resetParams);
        root.addView(zoom, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(8), 0, dp(18)));
        View.OnClickListener updateSize = view -> {
            if (view == smaller) terminalTextSize = Math.max(8, terminalTextSize - 1);
            else if (view == larger) terminalTextSize = Math.min(22, terminalTextSize + 1);
            else terminalTextSize = 12;
            getSharedPreferences("display", MODE_PRIVATE).edit()
                .putFloat("terminal_text_size", terminalTextSize).apply();
            size.setText(String.format(java.util.Locale.ROOT, "%.0f sp", terminalTextSize));
        };
        smaller.setOnClickListener(updateSize);
        larger.setOnClickListener(updateSize);
        reset.setOnClickListener(updateSize);

        sectionLabel("Terminal scrollback");
        Button scrollback = secondaryButton(String.format(java.util.Locale.ROOT,
            "%,d rows", terminalScrollbackRows));
        scrollback.setOnClickListener(view -> {
            int[] values = {1000, 4000, 10000, 20000};
            String[] labels = {"1,000 rows", "4,000 rows", "10,000 rows", "20,000 rows"};
            int checked = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == terminalScrollbackRows) checked = i;
            }
            new AlertDialog.Builder(this).setTitle("Terminal scrollback")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    terminalScrollbackRows = values[which];
                    getSharedPreferences("display", MODE_PRIVATE).edit()
                        .putInt("terminal_scrollback_rows", terminalScrollbackRows).apply();
                    scrollback.setText(labels[which]);
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
        });

        sectionLabel("Workspace list");
        Switch wrap = new Switch(this);
        wrap.setText("Wrap workspace titles");
        wrap.setTextColor(ink);
        wrap.setTextSize(15);
        wrap.setMinHeight(dp(48));
        wrap.setChecked(wrapWorkspaceTitles);
        wrap.setOnCheckedChangeListener((view, checked) -> {
            wrapWorkspaceTitles = checked;
            getSharedPreferences("display", MODE_PRIVATE).edit()
                .putBoolean("wrap_workspace_titles", checked).apply();
        });
        root.addView(wrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        sectionLabel("Connected Mac");
        plainText(host.optString("mac_display_name", "cmux host"), 16, ink);
        String version = host.optString("mac_app_version", "Unknown version");
        plainText("cmux " + version, 13, muted);
        JSONArray capabilities = host.optJSONArray("capabilities");
        plainText((capabilities == null ? 0 : capabilities.length()) + " mobile capabilities", 13, muted);
        Button disconnect = secondaryButton("Disconnect");
        disconnect.setOnClickListener(view -> {
            connectedIrohEndpoint = null;
            reconnectHandler.removeCallbacksAndMessages(null);
            if (activeMachineId != null) connections.disconnect(activeMachineId);
            client = null;
            showConnect();
        });
        if (findMachine(activeMachineId) != null) {
            Button forget = secondaryButton("Forget this Mac");
            forget.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Forget this Mac?")
                .setMessage("The saved connection will be removed from this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    String forgottenMachineId = activeMachineId;
                    connectedIrohEndpoint = null;
                    reconnectHandler.removeCallbacksAndMessages(null);
                    machineRegistry.remove(forgottenMachineId);
                    activeMachineId = null;
                    getSharedPreferences("connection", MODE_PRIVATE).edit()
                        .remove("iroh_endpoint").remove("host").remove("port")
                        .remove("active_machine_id").remove("iroh_account").apply();
                    if (forgottenMachineId != null) connections.disconnect(forgottenMachineId);
                    client = null;
                    showConnect();
                }).show());
        }

        sectionLabel("Notifications");
        Button notifications = secondaryButton(notificationsEnabled()
            ? "Turn off agent alerts" : "Enable agent alerts");
        notifications.setOnClickListener(view -> {
            if (!canPostNotifications() && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"},
                    NOTIFICATION_PERMISSION);
                return;
            }
            boolean enabled = !notificationsEnabled();
            getSharedPreferences("notifications", MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled).apply();
            if (!enabled) clearSystemNotifications();
            showSettings();
        });

        sectionLabel("About");
        try {
            String appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            plainText("cmux " + appVersion, 14, muted).setTextIsSelectable(true);
        } catch (Exception ignored) {
            plainText("cmux for Android", 14, muted);
        }
    }

    private void loadChatSessions(Button source) {
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                chatSnapshot = client.chatSessions(null);
                runOnUiThread(() -> showChatSessions(chatSnapshot));
            } catch (Exception error) {
                fail(source, "Agents", error);
            }
        });
    }

    private void showChatSessions(JSONObject snapshot) {
        currentScreen = CHAT;
        currentChatSession = null;
        screen("Agent sessions", "Claude, Codex, and terminal conversations discovered by cmux.");
        Button back = smallButton("‹ Workspaces");
        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        root.addView(back, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), 0, 0, 0, dp(12)));
        java.util.List<JSONObject> sessions = CmuxClient.openableChatSessions(
            snapshot.optJSONArray("sessions"));
        if (sessions.isEmpty()) {
            emptyState("No agent sessions", "Start Claude Code or Codex in a cmux terminal.");
            return;
        }
        for (JSONObject session : sessions) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(shape(surface, 12, outline));
            TextView title = new TextView(this);
            title.setText(bounded(session.optString("title",
                session.optString("agent_kind", "Agent") + " session"), 256));
            title.setTextColor(ink);
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            TextView detail = new TextView(this);
            detail.setText(session.optString("agent_kind", "agent") + " · "
                + session.optString("state", "idle") + " · "
                + shortPath(session.optString("cwd", "")));
            detail.setTextColor(muted);
            detail.setTextSize(13);
            row.addView(title);
            row.addView(detail);
            row.setOnClickListener(view -> loadChat(session, row));
            root.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(7), 0, 0));
        }
    }

    private void loadChat(JSONObject session, View source) {
        source.setEnabled(false);
        worker.execute(() -> {
            try {
                JSONObject history = client.chatHistory(session.optString("session_id"));
                runOnUiThread(() -> showChat(session, history));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    source.setEnabled(true);
                    message(errorMessage(error));
                });
            }
        });
    }

    private void showChat(JSONObject session, JSONObject history) {
        if (pendingChatImage != null
            && !session.optString("session_id").equals(pendingChatImageSession)) {
            pendingChatImage = null;
            pendingChatImageFormat = null;
            pendingChatImageSession = null;
        }
        currentScreen = CHAT;
        currentChatSession = session;
        screen(bounded(session.optString("title", session.optString("agent_kind", "Agent")), 80),
            session.optString("agent_kind", "agent") + " · " + session.optString("state", "idle"));
        LinearLayout actions = new LinearLayout(this);
        Button back = smallButton("‹ Sessions");
        Button interrupt = smallButton("Interrupt");
        Button files = smallButton("Files");
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams interruptParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        interruptParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(interrupt, interruptParams);
        LinearLayout.LayoutParams filesParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        filesParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(files, filesParams);
        root.addView(actions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(12)));
        back.setOnClickListener(view -> showChatSessions(chatSnapshot));
        interrupt.setOnClickListener(view -> interruptChat(session, interrupt));
        files.setVisibility(client.supports("chat.artifact.gallery.v1") ? View.VISIBLE : View.GONE);
        files.setOnClickListener(view -> loadChatArtifacts(session, files));

        JSONArray messages = history.optJSONArray("messages");
        if (history.optBoolean("has_more") && messages != null && messages.length() > 0) {
            Button older = smallButton("Load older messages");
            older.setOnClickListener(view -> loadOlderChat(session, history, older));
            root.addView(older, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
                0, 0, 0, dp(8)));
        }
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            JSONObject kind = item.optJSONObject("kind");
            String role = item.optString("role", "agent");
            TextView bubble = new TextView(this);
            bubble.setText(chatMessage(kind));
            bubble.setTextColor(ink);
            bubble.setTextSize(14);
            bubble.setTextIsSelectable(true);
            bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
            bubble.setBackground(shape("user".equals(role) ? surfaceRaised : surface, 12, outline));
            String messageType = kind == null ? "" : kind.optString("type");
            if ("terminal".equals(messageType) || "file_edit".equals(messageType)) {
                bubble.setTypeface(Typeface.MONOSPACE);
            }
            LinearLayout.LayoutParams bubbleParams = marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                "user".equals(role) ? dp(36) : 0, dp(6), "user".equals(role) ? 0 : dp(20), 0);
            root.addView(bubble, bubbleParams);
            addChatChoices(session, kind);
        }
        EditText composer = new EditText(this);
        composer.setHint("Message agent…");
        composer.setTextColor(ink);
        composer.setHintTextColor(muted);
        composer.setMinHeight(dp(54));
        composer.setMaxLines(5);
        composer.setPadding(dp(14), dp(10), dp(14), dp(10));
        composer.setBackground(shape(surface, 12, outline));
        root.addView(composer, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(16), 0, 0));
        LinearLayout composerActions = new LinearLayout(this);
        Button attach = smallButton(pendingChatImage == null ? "Attach image" : "Image attached");
        chatAttachmentButton = attach;
        Button send = smallButton("Send");
        composerActions.addView(attach, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams chatSendParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        chatSendParams.setMargins(dp(8), 0, 0, 0);
        composerActions.addView(send, chatSendParams);
        root.addView(composerActions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48), 0, dp(10), 0, 0));
        attach.setOnClickListener(view -> {
            if (pendingChatImage == null) {
                pickChatImage();
            } else {
                new AlertDialog.Builder(this).setTitle("Attached image")
                    .setItems(new String[] {"Replace image", "Remove image"}, (dialog, which) -> {
                        if (which == 0) pickChatImage();
                        else {
                            pendingChatImage = null;
                            pendingChatImageFormat = null;
                            pendingChatImageSession = null;
                            attach.setText("Attach image");
                        }
                    }).setNegativeButton("Cancel", null).show();
            }
        });
        send.setOnClickListener(view -> sendChat(session, composer, send));
    }

    private void pickChatImage() {
        chatImagePickerSession = currentChatSession == null ? null
            : currentChatSession.optString("session_id");
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/png").putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] {"image/png", "image/jpeg"}).addCategory(Intent.CATEGORY_OPENABLE),
            PICK_CHAT_IMAGE);
    }

    private String chatMessage(JSONObject kind) {
        if (kind == null) return "Unsupported message";
        String type = kind.optString("type", "message");
        if ("question".equals(type)) {
            String selected = kind.optString("selected_option_label", "");
            return bounded(kind.optString("prompt", "Question")
                + (selected.isEmpty() ? "" : "\n\n✓ " + selected), 20000);
        }
        if ("permission_request".equals(type)) {
            String resolution = kind.optString("resolution", "");
            return bounded(kind.optString("title", "Permission requested") + "\n"
                + kind.optString("subject", "")
                + (resolution.isEmpty() ? "" : "\n\n" + resolution), 20000);
        }
        String text = kind.optString("text", "");
        if (!text.isEmpty()) return bounded(("thought".equals(type) ? "Thought\n" : "") + text, 20000);
        if ("terminal".equals(type)) {
            String command = kind.optString("command", "command");
            String output = kind.optString("output", "");
            String state = kind.optBoolean("is_running") ? "running"
                : kind.has("exit_code") ? "exit " + kind.optInt("exit_code") : "finished";
            return bounded("$ " + command + "\n\n" + output + "\n\n[" + state + "]", 20000);
        }
        if ("file_edit".equals(type)) {
            String stats = (kind.has("additions") ? " +" + kind.optInt("additions") : "")
                + (kind.has("deletions") ? " −" + kind.optInt("deletions") : "");
            return bounded(kind.optString("operation", "edit") + " · "
                + kind.optString("file_path", "file") + stats + "\n\n"
                + kind.optString("unified_diff", ""), 20000);
        }
        if ("tool_use".equals(type)) {
            String detail = kind.optString("input_detail", "");
            String output = kind.optString("output", "");
            return bounded(kind.optString("status", "running") + " · "
                + kind.optString("summary", kind.optString("tool_name", "Tool"))
                + (detail.isEmpty() ? "" : "\n\n" + detail)
                + (output.isEmpty() ? "" : "\n\n" + output), 20000);
        }
        if ("status".equals(type)) {
            return bounded(kind.optString("event", "Status").replace('_', ' ')
                + (kind.optString("detail").isEmpty() ? "" : " · " + kind.optString("detail")), 1000);
        }
        if ("attachment".equals(type)) {
            return bounded("Attachment · " + kind.optString("display_name",
                kind.optString("media", "file"))
                + (kind.optString("host_path").isEmpty() ? "" : "\n" + kind.optString("host_path")), 4000);
        }
        String summary = kind.optString("summary", kind.optString("tool_name", type.replace('_', ' ')));
        String output = kind.optString("output", kind.optString("content", ""));
        return bounded(summary + (output.isEmpty() ? "" : "\n\n" + output), 20000);
    }

    private void addChatChoices(JSONObject session, JSONObject kind) {
        if (kind == null) return;
        String type = kind.optString("type");
        JSONArray options = kind.optJSONArray("options");
        if ("question".equals(type) && kind.optString("selected_option_label", "").isEmpty()
            && options != null) {
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                String label = option.optString("label", "Option " + (i + 1));
                String detail = option.optString("detail", "");
                Button choice = smallButton(label + (detail.isEmpty() ? "" : "\n" + detail));
                int index = i;
                choice.setOnClickListener(view -> answerChat(session, index, choice));
                root.addView(choice, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), dp(5), dp(16), 0));
            }
        } else if ("permission_request".equals(type) && kind.optString("resolution", "").isEmpty()) {
            LinearLayout decisions = new LinearLayout(this);
            Button approve = smallButton("Approve");
            approve.setTextColor(Color.WHITE);
            approve.setBackground(shape(accent, 12, accent));
            Button deny = smallButton("Deny");
            approve.setOnClickListener(view -> answerChat(session, 0, approve));
            deny.setOnClickListener(view -> answerChat(session, 1, deny));
            decisions.addView(approve, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams denyParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            denyParams.setMargins(dp(8), 0, 0, 0);
            decisions.addView(deny, denyParams);
            root.addView(decisions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48), dp(16), dp(5), dp(16), 0));
        }
    }

    private void answerChat(JSONObject session, int optionIndex, Button source) {
        String retryLabel = source.getText().toString();
        busy(source, true, "Sending…");
        worker.execute(() -> {
            try {
                String sessionId = session.optString("session_id");
                client.chatAnswer(sessionId, optionIndex);
                JSONObject history = client.chatHistory(sessionId);
                runOnUiThread(() -> showChat(session, history));
            } catch (Exception error) {
                fail(source, retryLabel, error);
            }
        });
    }

    private void loadOlderChat(JSONObject session, JSONObject current, Button source) {
        JSONArray currentMessages = current.optJSONArray("messages");
        if (currentMessages == null || currentMessages.length() == 0) return;
        JSONObject first = currentMessages.optJSONObject(0);
        int before = first == null ? -1 : first.optInt("seq", -1);
        if (before < 0) return;
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                JSONObject older = client.chatHistory(session.optString("session_id"), before);
                JSONArray combined = new JSONArray();
                JSONArray olderMessages = older.optJSONArray("messages");
                if (olderMessages != null) for (int i = 0; i < olderMessages.length(); i++) {
                    combined.put(olderMessages.get(i));
                }
                for (int i = 0; i < currentMessages.length(); i++) combined.put(currentMessages.get(i));
                JSONObject merged = new JSONObject().put("messages", combined)
                    .put("has_more", older.optBoolean("has_more"));
                runOnUiThread(() -> showChat(session, merged));
            } catch (Exception error) {
                fail(source, "Load older messages", error);
            }
        });
    }

    private void sendChat(JSONObject session, EditText composer, Button source) {
        String text = composer.getText().toString();
        if (text.isBlank() && pendingChatImage == null) return;
        busy(source, true, "Sending…");
        worker.execute(() -> {
            try {
                client.chatSend(session.optString("session_id"), text,
                    pendingChatImage, pendingChatImageFormat);
                pendingChatImage = null;
                pendingChatImageFormat = null;
                pendingChatImageSession = null;
                JSONObject history = client.chatHistory(session.optString("session_id"));
                runOnUiThread(() -> showChat(session, history));
            } catch (Exception error) {
                fail(source, "Send", error);
            }
        });
    }

    private void interruptChat(JSONObject session, Button source) {
        busy(source, true, "Stopping…");
        worker.execute(() -> {
            try {
                client.chatInterrupt(session.optString("session_id"), false);
                runOnUiThread(() -> busy(source, false, "Interrupt"));
            } catch (Exception error) {
                fail(source, "Interrupt", error);
            }
        });
    }

    private void loadChatArtifacts(JSONObject session, Button source) {
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                JSONObject page = client.chatArtifactGallery(session.optString("session_id"), null, null);
                JSONArray items = new JSONArray();
                for (String key : new String[] {"created", "attached", "referenced"}) {
                    JSONArray section = page.optJSONArray(key);
                    if (section != null) for (int i = 0; i < section.length(); i++) items.put(section.get(i));
                }
                runOnUiThread(() -> showArtifactList("Session files", items,
                    session.optString("session_id"), null, null));
            } catch (Exception error) {
                fail(source, "Files", error);
            }
        });
    }

    private void loadTerminalArtifacts(Button source) {
        busy(source, true, "…");
        String selectedWorkspace = workspaceId;
        String selectedSurface = surfaceId;
        worker.execute(() -> {
            try {
                JSONObject scan = client.terminalArtifactScan(selectedWorkspace, selectedSurface, false);
                JSONArray items = scan.optJSONArray("artifacts");
                runOnUiThread(() -> showArtifactList("Terminal files",
                    items == null ? new JSONArray() : items, null, selectedWorkspace, selectedSurface));
            } catch (Exception error) {
                fail(source, "Files", error);
            }
        });
    }

    private void showArtifactList(String title, JSONArray items, String sessionId,
                                  String selectedWorkspace, String selectedSurface) {
        if (items.length() == 0) {
            message("No referenced files found.");
            return;
        }
        String[] labels = new String[items.length()];
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            labels[i] = item == null ? "File" : item.optString("display_name",
                new java.io.File(item.optString("path", "File")).getName());
        }
        new AlertDialog.Builder(this).setTitle(title).setItems(labels, (dialog, which) -> {
            JSONObject item = items.optJSONObject(which);
            if (item == null) return;
            openArtifact(item.optString("path"), item.optString("kind", "binary"),
                sessionId, selectedWorkspace, selectedSurface);
        }).setNegativeButton("Done", null).show();
    }

    private void openArtifact(String path, String kind, String sessionId,
                              String selectedWorkspace, String selectedSurface) {
        worker.execute(() -> {
            try {
                if ("directory".equals(kind)) {
                    JSONObject listing = sessionId != null
                        ? client.chatArtifactList(sessionId, path)
                        : client.terminalArtifactList(selectedWorkspace, selectedSurface, path);
                    JSONArray entries = listing.optJSONArray("entries");
                    runOnUiThread(() -> showArtifactDirectory(path,
                        entries == null ? new JSONArray() : entries, sessionId,
                        selectedWorkspace, selectedSurface));
                    return;
                }
                byte[] bytes = sessionId != null
                    ? client.chatArtifactFetch(sessionId, path)
                    : client.terminalArtifactFetch(selectedWorkspace, selectedSurface, path);
                runOnUiThread(() -> showArtifact(path, kind, bytes));
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            }
        });
    }

    private void showArtifactDirectory(String path, JSONArray entries, String sessionId,
                                       String selectedWorkspace, String selectedSurface) {
        String[] labels = new String[entries.length()];
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            labels[i] = entry == null ? "Item" : (entry.optBoolean("is_directory") ? "▸ " : "")
                + entry.optString("name", "Item");
        }
        new AlertDialog.Builder(this).setTitle(new java.io.File(path).getName())
            .setItems(labels, (dialog, which) -> {
                JSONObject entry = entries.optJSONObject(which);
                if (entry == null) return;
                String child = path.endsWith("/") ? path + entry.optString("name")
                    : path + "/" + entry.optString("name");
                openArtifact(child, entry.optString("kind",
                    entry.optBoolean("is_directory") ? "directory" : "binary"),
                    sessionId, selectedWorkspace, selectedSurface);
            }).setNegativeButton("Done", null).show();
    }

    private void showArtifact(String path, String kind, byte[] bytes) {
        View preview;
        if ("image".equals(kind) && bytes.length <= 16 * 1024 * 1024) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
            preview = image;
        } else if ("text".equals(kind) && bytes.length <= 4 * 1024 * 1024) {
            TextView text = new TextView(this);
            text.setText(new String(bytes, StandardCharsets.UTF_8));
            text.setTextIsSelectable(true);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(12);
            text.setTextColor(ink);
            text.setPadding(dp(16), dp(12), dp(16), dp(12));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(text);
            preview = scroll;
        } else {
            TextView detail = new TextView(this);
            detail.setText(bytes.length + " bytes\n"
                + ("text".equals(kind) || "image".equals(kind)
                    ? "Preview is disabled for large files. Save it to open locally."
                    : "No inline preview is available for this file."));
            detail.setTextColor(ink);
            detail.setPadding(dp(20), dp(20), dp(20), dp(20));
            preview = detail;
        }
        String name = new java.io.File(path).getName();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name).setView(preview)
            .setNegativeButton("Done", null).setPositiveButton("Save", (ignored, which) -> {
                pendingArtifactBytes = bytes;
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name)
                    .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(save, SAVE_ARTIFACT);
            }).create();
        dialog.setOnShowListener(ignored -> {
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.68);
            preview.setMinimumHeight(Math.min(maxHeight, dp(240)));
        });
        dialog.show();
    }

    private void loadBrowsers(Button source) {
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                JSONArray panels = new JSONArray();
                JSONArray workspaces = workspaceSnapshot.optJSONArray("workspaces");
                if (workspaces != null) {
                    for (int i = 0; i < workspaces.length(); i++) {
                        JSONObject workspace = workspaces.optJSONObject(i);
                        if (workspace == null) continue;
                        JSONObject result = client.listBrowsers(workspace.optString("id"));
                        JSONArray found = result.optJSONArray("panels");
                        if (found == null) continue;
                        for (int j = 0; j < found.length(); j++) panels.put(found.optJSONObject(j));
                    }
                }
                browserSnapshot = new JSONObject().put("panels", panels);
                runOnUiThread(() -> showBrowsers(browserSnapshot));
            } catch (Exception error) {
                fail(source, "Web", error);
            }
        });
    }

    private void showBrowsers(JSONObject snapshot) {
        stopActiveBrowser();
        currentScreen = BROWSERS;
        screen("Browsers", "Browser panels running inside cmux.");
        Button back = smallButton("‹ Workspaces");
        Button local = smallButton("+ New browser");
        root.addView(back, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48), 0, 0, 0, dp(8)));
        root.addView(local, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48), 0, 0, 0, dp(14)));
        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        local.setOnClickListener(view -> showLocalBrowser());
        JSONArray panels = snapshot.optJSONArray("panels");
        if (panels == null || panels.length() == 0) {
            emptyState("No browser panels", "Open a browser in cmux, then return here.");
            return;
        }
        for (int i = 0; i < panels.length(); i++) {
            JSONObject panel = panels.optJSONObject(i);
            if (panel == null) continue;
            View row = browserRow(panel);
            row.setOnClickListener(view -> openBrowser(panel));
            root.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(7), 0, 0));
        }
    }

    private View browserRow(JSONObject panel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        row.setMinimumHeight(dp(70));
        row.setBackground(shape(surface, 12, outline));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(bounded(panel.optString("title", "Browser"), 256));
        title.setTextColor(ink);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView url = new TextView(this);
        url.setText(bounded(panel.optString("url", ""), 512));
        url.setTextColor(muted);
        url.setTextSize(13);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title);
        copy.addView(url);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(muted);
        arrow.setTextSize(28);
        arrow.setGravity(Gravity.CENTER);
        row.addView(copy, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(48)));
        return row;
    }

    private void showLocalBrowser() {
        stopActiveBrowser();
        currentScreen = BROWSERS;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(4));
        toolbar.setBackgroundColor(surface);
        Button close = smallButton("‹");
        Button back = smallButton("←");
        Button forward = smallButton("→");
        Button reload = smallButton("↻");
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(forward, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        addressRow.setBackgroundColor(surface);
        EditText address = new EditText(this);
        address.setHint("Search or enter address");
        address.setTextColor(ink);
        address.setHintTextColor(muted);
        address.setSingleLine(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setBackground(shape(surfaceRaised, 10, outline));
        address.setPadding(dp(12), 0, dp(12), 0);
        Button go = smallButton("Go");
        addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(58), dp(46));
        goParams.setMargins(dp(8), 0, 0, 0);
        addressRow.addView(go, goParams);
        root.addView(addressRow);

        WebView web = new WebView(this);
        localBrowser = web;
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                message("Only http and https links can open here.");
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!address.hasFocus()) address.setText(url);
                back.setEnabled(view.canGoBack());
                forward.setEnabled(view.canGoForward());
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.setContentDescription("Android browser");
        root.addView(web, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mount(root, false);

        close.setOnClickListener(view -> showBrowsers(browserSnapshot));
        back.setOnClickListener(view -> { if (web.canGoBack()) web.goBack(); });
        forward.setOnClickListener(view -> { if (web.canGoForward()) web.goForward(); });
        reload.setOnClickListener(view -> web.reload());
        View.OnClickListener navigate = view -> {
            String url = BrowserAddress.resolve(address.getText().toString());
            if (url != null) web.loadUrl(url);
        };
        go.setOnClickListener(navigate);
        address.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            navigate.onClick(view);
            return true;
        });
        web.loadUrl("https://www.google.com");
    }

    private void openBrowser(JSONObject panel) {
        currentScreen = BROWSERS;
        status = null;
        browserPanelId = panel.optString("panel_id");
        browserLastFrameSequence = -1;
        browserState = panel;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(4), dp(6), dp(4));
        toolbar.setBackgroundColor(surface);
        Button close = smallButton("‹");
        Button backward = smallButton("←");
        Button forward = smallButton("→");
        Button reload = smallButton("↻");
        TextView title = new TextView(this);
        title.setText(panel.optString("title", "Browser"));
        title.setTextColor(ink);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(backward, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(forward, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);
        status = new TextView(this);
        status.setTextColor(ink);
        status.setTextSize(13);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        status.setBackgroundColor(surfaceRaised);
        status.setVisibility(View.GONE);
        root.addView(status);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        addressRow.setBackgroundColor(surface);
        browserAddress = new EditText(this);
        browserAddress.setText(panel.optString("url", ""));
        browserAddress.setTextColor(ink);
        browserAddress.setHintTextColor(muted);
        browserAddress.setTextSize(14);
        browserAddress.setSingleLine(true);
        browserAddress.setSelectAllOnFocus(true);
        browserAddress.setImeOptions(EditorInfo.IME_ACTION_GO);
        browserAddress.setBackground(shape(surfaceRaised, 10, outline));
        browserAddress.setPadding(dp(12), 0, dp(12), 0);
        Button go = smallButton("Go");
        addressRow.addView(browserAddress, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(58), dp(46));
        goParams.setMargins(dp(8), 0, 0, 0);
        addressRow.addView(go, goParams);
        root.addView(addressRow);

        browserImage = new ImageView(this);
        browserImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        browserImage.setBackgroundColor(Color.rgb(18, 18, 20));
        browserImage.setContentDescription("Remote cmux browser");
        root.addView(browserImage, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setPadding(dp(10), dp(8), dp(10), dp(10));
        inputRow.setBackgroundColor(surface);
        EditText pageInput = new EditText(this);
        pageInput.setHint("Type into focused page field");
        pageInput.setTextColor(ink);
        pageInput.setHintTextColor(muted);
        pageInput.setSingleLine(true);
        pageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        pageInput.setBackground(shape(surfaceRaised, 10, outline));
        pageInput.setPadding(dp(12), 0, dp(12), 0);
        Button send = smallButton("Send");
        inputRow.addView(pageInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(72), dp(48));
        sendParams.setMargins(dp(8), 0, 0, 0);
        inputRow.addView(send, sendParams);
        root.addView(inputRow);
        mount(root, false);

        close.setOnClickListener(view -> showBrowsers(browserSnapshot));
        backward.setOnClickListener(view -> sendBrowserCommand("mobile.browser.back"));
        forward.setOnClickListener(view -> sendBrowserCommand("mobile.browser.forward"));
        reload.setOnClickListener(view -> sendBrowserCommand("mobile.browser.reload"));
        View.OnClickListener navigate = view -> {
            String address = browserAddress.getText().toString().trim();
            if (!address.isEmpty()) worker.execute(() -> {
                try { client.browserNavigate(browserPanelId, address); }
                catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
            });
        };
        go.setOnClickListener(navigate);
        browserAddress.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            navigate.onClick(view);
            return true;
        });
        View.OnClickListener submitText = view -> {
            String text = pageInput.getText().toString();
            if (text.isEmpty()) return;
            pageInput.setText("");
            worker.execute(() -> {
                try {
                    client.browserText(browserPanelId, text);
                    client.browserKey(browserPanelId, "return", new JSONArray());
                }
                catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
            });
        };
        send.setOnClickListener(submitText);
        pageInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            submitText.onClick(view);
            return true;
        });
        installBrowserGestures();
        final int[] lastSize = {0, 0};
        browserImage.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            if (width == lastSize[0] && height == lastSize[1]) return;
            boolean first = lastSize[0] == 0;
            lastSize[0] = width;
            lastSize[1] = height;
            double scale = getResources().getDisplayMetrics().density;
            worker.execute(() -> {
                try {
                    if (first) client.startBrowser(browserPanelId, width, height, scale);
                    else client.updateBrowserViewport(browserPanelId, width, height, scale);
                } catch (Exception error) {
                    runOnUiThread(() -> message(errorMessage(error)));
                }
            });
        });
    }

    private void sendBrowserCommand(String method) {
        String panel = browserPanelId;
        worker.execute(() -> {
            try { client.browserCommand(method, panel); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    private void installBrowserGestures() {
        GestureDetector gestures = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) {
                    double[] point = browserPagePoint(event.getX(), event.getY());
                    sendBrowserScrollPhase("began", point[0], point[1]);
                    return true;
                }

                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    double[] point = browserPagePoint(event.getX(), event.getY());
                    String panel = browserPanelId;
                    worker.execute(() -> {
                        try { client.browserPointer(panel, point[0], point[1], "left"); }
                        catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
                    });
                    return true;
                }

                @Override public boolean onScroll(MotionEvent first, MotionEvent current,
                                                  float distanceX, float distanceY) {
                    double[] point = browserPagePoint(current.getX(), current.getY());
                    String panel = browserPanelId;
                    enqueueBrowserScroll(panel, distanceX, distanceY, point[0], point[1]);
                    return true;
                }

                @Override public void onLongPress(MotionEvent event) {
                    double[] point = browserPagePoint(event.getX(), event.getY());
                    String panel = browserPanelId;
                    worker.execute(() -> {
                        try { client.browserPointer(panel, point[0], point[1], "right"); }
                        catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
                    });
                }
            });
        browserImage.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            boolean handled = gestures.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                double[] point = browserPagePoint(event.getX(), event.getY());
                sendBrowserScrollPhase("ended", point[0], point[1]);
            } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                double[] point = browserPagePoint(event.getX(), event.getY());
                sendBrowserScrollPhase("cancelled", point[0], point[1]);
            }
            return handled;
        });
    }

    private void sendBrowserScrollPhase(String phase, double x, double y) {
        String panel = browserPanelId;
        if (panel == null) return;
        worker.execute(() -> {
            try { client.browserScroll(panel, 0, 0, x, y, phase); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    private void sendBrowserKey(String key, JSONArray modifiers) {
        String panel = browserPanelId;
        worker.execute(() -> {
            try { client.browserKey(panel, key, modifiers); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    private static JSONArray browserModifiers(KeyEvent event) {
        JSONArray result = new JSONArray();
        if (event.isMetaPressed()) result.put("command");
        if (event.isCtrlPressed()) result.put("control");
        if (event.isAltPressed()) result.put("option");
        if (event.isShiftPressed()) result.put("shift");
        return result;
    }

    private static String browserKeyToken(KeyEvent event) {
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER -> "return";
            case KeyEvent.KEYCODE_DEL -> "delete";
            case KeyEvent.KEYCODE_TAB -> "tab";
            case KeyEvent.KEYCODE_ESCAPE -> "escape";
            case KeyEvent.KEYCODE_DPAD_UP -> "up";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "down";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "right";
            default -> {
                int unicode = event.getUnicodeChar(0);
                yield unicode > 0 ? new String(Character.toChars(unicode)).toLowerCase(java.util.Locale.ROOT) : null;
            }
        };
    }

    private double[] browserPagePoint(float x, float y) {
        if (browserImage == null || browserPixelWidth <= 0 || browserPixelHeight <= 0
            || browserPageWidth <= 0 || browserPageHeight <= 0) return new double[] {0, 0};
        double scale = Math.min((double) browserImage.getWidth() / browserPixelWidth,
            (double) browserImage.getHeight() / browserPixelHeight);
        double shownWidth = browserPixelWidth * scale;
        double shownHeight = browserPixelHeight * scale;
        double left = (browserImage.getWidth() - shownWidth) / 2;
        double top = (browserImage.getHeight() - shownHeight) / 2;
        double pageX = Math.max(0, Math.min(browserPageWidth,
            (x - left) / Math.max(1, shownWidth) * browserPageWidth));
        double pageY = Math.max(0, Math.min(browserPageHeight,
            (y - top) / Math.max(1, shownHeight) * browserPageHeight));
        return new double[] {pageX, pageY};
    }

    private void enqueueBrowserScroll(String panel, double dx, double dy, double x, double y) {
        synchronized (browserScrollLock) {
            pendingBrowserScrollX += dx;
            pendingBrowserScrollY += dy;
            pendingBrowserScrollAnchorX = x;
            pendingBrowserScrollAnchorY = y;
        }
        if (!browserScrollRunning.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                while (panel.equals(browserPanelId)) {
                    double sendX;
                    double sendY;
                    double anchorX;
                    double anchorY;
                    synchronized (browserScrollLock) {
                        sendX = pendingBrowserScrollX;
                        sendY = pendingBrowserScrollY;
                        anchorX = pendingBrowserScrollAnchorX;
                        anchorY = pendingBrowserScrollAnchorY;
                        pendingBrowserScrollX = 0;
                        pendingBrowserScrollY = 0;
                    }
                    if (sendX == 0 && sendY == 0) break;
                    client.browserScroll(panel, sendX, sendY, anchorX, anchorY, "changed");
                }
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            } finally {
                browserScrollRunning.set(false);
                synchronized (browserScrollLock) {
                    if ((pendingBrowserScrollX != 0 || pendingBrowserScrollY != 0)
                        && panel.equals(browserPanelId)) {
                        enqueueBrowserScroll(panel, 0, 0,
                            pendingBrowserScrollAnchorX, pendingBrowserScrollAnchorY);
                    }
                }
            }
        });
    }

    private void stopActiveBrowser() {
        WebView local = localBrowser;
        localBrowser = null;
        if (local != null) {
            local.stopLoading();
            local.loadUrl("about:blank");
            local.clearHistory();
            local.removeAllViews();
            local.destroy();
        }
        String panel = browserPanelId;
        browserPanelId = null;
        browserLastFrameSequence = -1;
        browserImage = null;
        browserAddress = null;
        pendingBrowserFrame = null;
        if (browserBitmap != null) {
            browserBitmap.recycle();
            browserBitmap = null;
        }
        if (panel != null && client != null) worker.execute(() -> {
            try { client.stopBrowser(panel); } catch (Exception ignored) {}
        });
    }

    private void loadNotifications(Button source) {
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                notificationSnapshot = client.notificationFeed();
                runOnUiThread(() -> showNotifications(notificationSnapshot));
            } catch (Exception error) {
                fail(source, "Activity", error);
            }
        });
    }

    private void showNotifications(JSONObject snapshot) {
        currentScreen = NOTIFICATIONS;
        screen("Activity", "Agent notifications from your Mac.");
        LinearLayout actions = new LinearLayout(this);
        Button back = smallButton("‹ Workspaces");
        Button markAll = smallButton("Mark all read");
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        markParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(markAll, markParams);
        root.addView(actions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(14)));
        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        markAll.setOnClickListener(view -> {
            busy(markAll, true, "Updating…");
            worker.execute(() -> {
                try {
                    client.markAllNotificationsRead();
                    clearSystemNotifications();
                    notificationSnapshot = client.notificationFeed();
                    unreadCount = 0;
                    runOnUiThread(() -> showNotifications(notificationSnapshot));
                } catch (Exception error) {
                    fail(markAll, "Mark all read", error);
                }
            });
        });

        JSONArray notifications = snapshot.optJSONArray("notifications");
        if (notifications == null || notifications.length() == 0) {
            emptyState("All clear", "Agent notifications will appear here.");
            return;
        }
        int limit = Math.min(200, notifications.length());
        for (int i = 0; i < limit; i++) {
            JSONObject item = notifications.optJSONObject(i);
            if (item == null || item.optString("id").isEmpty()) continue;
            View row = notificationRow(item);
            row.setOnClickListener(view -> openNotification(item, row));
            row.setOnLongClickListener(view -> {
                showNotificationMenu(view, item);
                return true;
            });
            root.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(7), 0, 0));
        }
    }

    private View notificationRow(JSONObject item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(72));
        row.setBackground(shape(surface, 12, outline));
        row.setClickable(true);
        row.setFocusable(true);
        View dot = new View(this);
        dot.setBackground(shape(item.optBoolean("is_read") ? Color.TRANSPARENT : accent,
            4, Color.TRANSPARENT));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMargins(0, dp(6), dp(10), 0);
        row.addView(dot, dotParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(bounded(item.optString("title", "Notification"), 256));
        title.setTextColor(ink);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView body = new TextView(this);
        body.setText(bounded(item.optString("body", ""), 2048));
        body.setTextColor(muted);
        body.setTextSize(14);
        body.setMaxLines(3);
        body.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String context = item.optString("workspace_title", "");
        if (context.isEmpty()) context = item.optString("surface_title", "");
        copy.addView(title);
        if (!body.getText().toString().isEmpty()) copy.addView(body);
        if (!context.isEmpty()) {
            TextView metadata = new TextView(this);
            metadata.setText(bounded(context, 256));
            metadata.setTextColor(accent);
            metadata.setTextSize(12);
            metadata.setPadding(0, dp(5), 0, 0);
            copy.addView(metadata);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void openNotification(JSONObject item, View source) {
        String id = item.optString("id");
        if (!item.optBoolean("is_read")) {
            worker.execute(() -> {
                try { client.setNotificationsRead(new JSONArray().put(id), true); }
                catch (Exception ignored) {}
            });
            dismissSystemNotification(id);
        }
        String targetWorkspace = item.optString("workspace_id");
        String targetSurface = item.optString("surface_id");
        JSONObject workspace = findWorkspace(targetWorkspace);
        if (workspace == null) {
            message("The target workspace is no longer open.");
            return;
        }
        JSONObject terminalItem = findTerminal(workspace, targetSurface);
        if (terminalItem == null) {
            JSONArray terminals = workspace.optJSONArray("terminals");
            terminalItem = terminals == null ? null : terminals.optJSONObject(0);
        }
        if (terminalItem == null) {
            message("The target terminal is no longer open.");
            return;
        }
        openTerminal(targetWorkspace, terminalItem.optString("id"),
            terminalItem.optString("title", "Terminal"), source);
    }

    private void showNotificationMenu(View anchor, JSONObject item) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean read = item.optBoolean("is_read");
        popup.getMenu().add(read ? "Mark as unread" : "Mark as read");
        popup.getMenu().add("Dismiss");
        popup.setOnMenuItemClickListener(menuItem -> {
            boolean dismiss = "Dismiss".contentEquals(menuItem.getTitle());
            worker.execute(() -> {
                try {
                    JSONArray ids = new JSONArray().put(item.optString("id"));
                    if (dismiss) client.dismissNotifications(ids);
                    else client.setNotificationsRead(ids, !read);
                    if (dismiss || !read) dismissSystemNotification(item.optString("id"));
                    notificationSnapshot = client.notificationFeed();
                    runOnUiThread(() -> showNotifications(notificationSnapshot));
                } catch (Exception error) {
                    runOnUiThread(() -> message(errorMessage(error)));
                }
            });
            return true;
        });
        popup.show();
    }

    private void showCreateWorkspaceDialog() {
        LinearLayout form = dialogForm();
        EditText title = dialogField(form, "Workspace name", "Optional");
        EditText directory = dialogField(form, "Working directory", "~/Developer/project");
        if (client != null && client.supports("workspace.directory_browse.v1")) {
            Button browse = smallButton("Browse Mac folders");
            browse.setOnClickListener(view -> showDirectoryPicker(directory,
                directory.getText().toString().isBlank() ? "~" : directory.getText().toString().trim()));
            form.addView(browse, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48),
                0, dp(8), 0, dp(4)));
        }
        EditText command = dialogField(form, "Initial command", "claude");
        LinearLayout agents = new LinearLayout(this);
        Button claude = smallButton("Claude Code");
        Button codex = smallButton("Codex");
        Button shell = smallButton("Shell");
        agents.addView(claude, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams agentParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        agentParams.setMargins(dp(8), 0, 0, 0);
        agents.addView(codex, agentParams);
        LinearLayout.LayoutParams shellParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        shellParams.setMargins(dp(8), 0, 0, 0);
        agents.addView(shell, shellParams);
        claude.setOnClickListener(view -> command.setText("claude"));
        codex.setOnClickListener(view -> command.setText("codex"));
        shell.setOnClickListener(view -> command.setText(""));
        form.addView(agents, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48), 0, dp(8), 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("New workspace")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(view -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                worker.execute(() -> {
                    try {
                        workspaceSnapshot = client.createWorkspace(
                            title.getText().toString(), directory.getText().toString(),
                            command.getText().toString());
                        runOnUiThread(() -> {
                            dialog.dismiss();
                            showWorkspaces(workspaceSnapshot);
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            message(errorMessage(error));
                        });
                    }
                });
            }));
        dialog.show();
    }

    private void showCreateGroupDialog() {
        EditText input = new EditText(this);
        input.setHint("Group name");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", (dialog, which) -> worker.execute(() -> {
                try {
                    client.createWorkspaceGroup(input.getText().toString());
                    workspaceSnapshot = client.listWorkspaces();
                    runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
                } catch (Exception error) {
                    runOnUiThread(() -> message(errorMessage(error)));
                }
            })).show();
    }

    private void showDirectoryPicker(EditText target, String path) {
        worker.execute(() -> {
            try {
                JSONObject page = client.listDirectories(path, 0);
                runOnUiThread(() -> {
                    String current = page.optString("current_path", path);
                    JSONArray entries = page.optJSONArray("entries");
                    java.util.ArrayList<String> labels = new java.util.ArrayList<>();
                    java.util.ArrayList<String> paths = new java.util.ArrayList<>();
                    labels.add("✓ Use this folder"); paths.add(current);
                    String parent = page.optString("parent_path", "");
                    if (!parent.isEmpty()) { labels.add("↑ Parent"); paths.add(parent); }
                    if (entries != null) for (int i = 0; i < entries.length(); i++) {
                        JSONObject entry = entries.optJSONObject(i);
                        if (entry == null || !entry.optBoolean("is_readable", true)) continue;
                        labels.add(entry.optString("name", "Folder"));
                        paths.add(entry.optString("path"));
                    }
                    new AlertDialog.Builder(this)
                        .setTitle(shortPath(current))
                        .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                            if (which == 0) target.setText(paths.get(0));
                            else showDirectoryPicker(target, paths.get(which));
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("Couldn’t browse folders")
                    .setMessage(errorMessage(error)).setPositiveButton("OK", null).show());
            }
        });
    }

    private View workspaceGroupHeader(JSONObject group) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, 0, 0);
        row.setBackground(shape(surfaceRaised, 10, Color.TRANSPARENT));
        boolean collapsed = group != null && group.optBoolean("is_collapsed");
        TextView title = new TextView(this);
        title.setText((collapsed ? "›  " : "⌄  ") + (group == null ? "Group" : group.optString("name", "Group")));
        title.setTextSize(15);
        title.setTextColor(ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        if (group != null && client != null && client.supports("workspace.group_actions.v1")) {
            Button menu = smallButton("⋮");
            menu.setContentDescription("Group actions");
            menu.setOnClickListener(view -> showGroupMenu(menu, group));
            row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.setOnClickListener(view -> mutateGroup(group, collapsed ? "expand" : "collapse", null));
        }
        return row;
    }

    private void showGroupMenu(View anchor, JSONObject group) {
        boolean pinned = group.optBoolean("is_pinned");
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Rename group");
        popup.getMenu().add(pinned ? "Unpin group" : "Pin group");
        popup.getMenu().add("Dissolve group");
        popup.getMenu().add("Delete group and workspaces");
        popup.setOnMenuItemClickListener(item -> {
            String choice = item.getTitle().toString();
            if (choice.startsWith("Rename")) showRenameGroupDialog(group);
            else if (choice.startsWith("Delete")) confirmDeleteGroup(group);
            else if (choice.startsWith("Dissolve")) mutateGroup(group, "ungroup", null);
            else mutateGroup(group, pinned ? "unpin" : "pin", null);
            return true;
        });
        popup.show();
    }

    private void showRenameGroupDialog(JSONObject group) {
        EditText input = new EditText(this);
        input.setText(group.optString("name"));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename group").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename", (dialog, which) ->
                mutateGroup(group, "rename", input.getText().toString())).show();
    }

    private void confirmDeleteGroup(JSONObject group) {
        new AlertDialog.Builder(this).setTitle("Delete group and workspaces?")
            .setMessage("This closes every workspace in " + group.optString("name", "this group") + ".")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> mutateGroup(group, "delete", null)).show();
    }

    private void mutateGroup(JSONObject group, String action, String title) {
        worker.execute(() -> {
            try {
                client.workspaceGroupAction(group.optString("id"), action, title);
                workspaceSnapshot = client.listWorkspaces();
                runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            }
        });
    }

    private void createTerminal(String workspace, Button source) {
        busy(source, true, "Creating…");
        worker.execute(() -> {
            try {
                workspaceSnapshot = client.createTerminal(workspace);
                runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
            } catch (Exception error) {
                fail(source, "+ New terminal", error);
            }
        });
    }

    private View workspaceHeader(JSONObject workspace) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String color = workspace.optString("custom_color", "");
        View rail = new View(this);
        rail.setBackground(shape(parseColor(color, accent), 2, Color.TRANSPARENT));
        header.addView(rail, new LinearLayout.LayoutParams(dp(3), dp(42)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(10), 0, dp(6), 0);
        String prefix = workspace.optBoolean("is_pinned") ? "◆  " : "";
        if (workspace.optBoolean("has_unread")) prefix += "●  ";
        TextView heading = new TextView(this);
        heading.setText(prefix + workspace.optString("title", "Workspace"));
        heading.setTextSize(18);
        heading.setTextColor(ink);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(!wrapWorkspaceTitles);
        heading.setMaxLines(wrapWorkspaceTitles ? 2 : 1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(heading);
        String preview = workspace.optString("preview", "");
        String description = workspace.optString("description", "");
        String directory = shortPath(workspace.optString("current_directory", ""));
        String supporting = !preview.isEmpty() ? preview : !description.isEmpty() ? description : directory;
        if (!supporting.isEmpty()) {
            TextView detail = new TextView(this);
            detail.setText(supporting);
            detail.setTextSize(13);
            detail.setTextColor(muted);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.START);
            copy.addView(detail);
        }
        JSONObject changes = workspace.optJSONObject("_changes_summary");
        if (changes != null) {
            TextView chip = new TextView(this);
            chip.setText(changes.optInt("files_changed") + " files  "
                + "+" + changes.optInt("additions") + "  −" + changes.optInt("deletions"));
            chip.setTextSize(12);
            chip.setTextColor(accent);
            chip.setContentDescription(changes.optInt("files_changed") + " changed files, "
                + changes.optInt("additions") + " additions, "
                + changes.optInt("deletions") + " deletions");
            copy.addView(chip);
        }
        header.addView(copy, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button menu = smallButton("⋮");
        menu.setContentDescription("Workspace actions");
        menu.setPadding(0, 0, 0, 0);
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menu.setOnClickListener(view -> showWorkspaceMenu(menu, workspace));
        return header;
    }

    private void showWorkspaceMenu(View anchor, JSONObject workspace) {
        boolean pinned = workspace.optBoolean("is_pinned");
        boolean unread = workspace.optBoolean("has_unread");
        PopupMenu popup = new PopupMenu(this, anchor);
        if (client != null && client.supports("workspace.actions.v1")) {
            popup.getMenu().add("Rename");
            popup.getMenu().add(pinned ? "Unpin" : "Pin");
        }
        if (client != null && client.supports("workspace.metadata.v1")) {
            popup.getMenu().add("Edit description");
            popup.getMenu().add("Set color");
        }
        if (client != null && client.supports("workspace.read_state.v1")) {
            popup.getMenu().add(unread ? "Mark as read" : "Mark as unread");
        }
        if (client != null && client.supports("workspace.move.v1")) popup.getMenu().add("Move to group");
        if (client != null && client.supports("workspace.changes.v1")) popup.getMenu().add("View changes");
        if (client != null && client.supports("workspace.close.v1")) popup.getMenu().add("Close workspace");
        popup.setOnMenuItemClickListener(item -> {
            String choice = item.getTitle().toString();
            if ("Rename".equals(choice)) {
                showRenameWorkspaceDialog(workspace);
            } else if ("Edit description".equals(choice)) {
                showWorkspaceMetadataDialog(workspace, "Description", "description", "set_description", "clear_description");
            } else if ("Set color".equals(choice)) {
                showWorkspaceColorDialog(workspace);
            } else if ("Move to group".equals(choice)) {
                showMoveWorkspaceDialog(workspace);
            } else if ("View changes".equals(choice)) {
                loadWorkspaceChanges(workspace, anchor);
            } else if (choice.startsWith("Close")) {
                confirmCloseWorkspace(workspace);
            } else if (choice.toLowerCase(java.util.Locale.ROOT).contains("pin")) {
                mutateWorkspace(workspace, pinned ? "unpin" : "pin", null, null);
            } else if (choice.startsWith("Mark")) {
                mutateWorkspace(workspace, unread ? "mark_read" : "mark_unread", null, null);
            }
            return true;
        });
        popup.show();
    }

    private void showWorkspaceMetadataDialog(JSONObject workspace, String title, String field,
                                             String setAction, String clearAction) {
        EditText input = new EditText(this);
        input.setText(workspace.optString(field, ""));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", (dialog, which) ->
                mutateWorkspace(workspace, clearAction, null, null))
            .setPositiveButton("Save", (dialog, which) -> {
                String value = input.getText().toString().trim();
                mutateWorkspace(workspace, value.isEmpty() ? clearAction : setAction,
                    value.isEmpty() ? null : field, value.isEmpty() ? null : value);
            }).show();
    }

    private void showWorkspaceColorDialog(JSONObject workspace) {
        String[] labels = {"Indigo", "Blue", "Green", "Amber", "Red", "Clear color"};
        String[] colors = {"#6366F1", "#3B82F6", "#22C55E", "#F59E0B", "#EF4444", ""};
        new AlertDialog.Builder(this).setTitle("Workspace color")
            .setItems(labels, (dialog, which) -> mutateWorkspace(workspace,
                colors[which].isEmpty() ? "clear_color" : "set_color",
                colors[which].isEmpty() ? null : "color",
                colors[which].isEmpty() ? null : colors[which]))
            .setNegativeButton("Cancel", null).show();
    }

    private void showMoveWorkspaceDialog(JSONObject workspace) {
        JSONArray groups = workspaceSnapshot == null ? null : workspaceSnapshot.optJSONArray("groups");
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        labels.add("No group"); ids.add("");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            labels.add(group.optString("name", "Group"));
            ids.add(group.optString("id"));
        }
        new AlertDialog.Builder(this).setTitle("Move workspace")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> worker.execute(() -> {
                try {
                    client.moveWorkspace(workspace.optString("id"),
                        workspace.optString("window_id", null), ids.get(which));
                    workspaceSnapshot = client.listWorkspaces();
                    runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
                } catch (Exception error) {
                    runOnUiThread(() -> message(errorMessage(error)));
                }
            })).setNegativeButton("Cancel", null).show();
    }

    private void loadWorkspaceChanges(JSONObject workspace, View source) {
        source.setEnabled(false);
        worker.execute(() -> {
            try {
                JSONObject changes = client.workspaceChanges(workspace.optString("id"));
                runOnUiThread(() -> showWorkspaceChanges(workspace, changes));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    source.setEnabled(true);
                    message(errorMessage(error));
                });
            }
        });
    }

    private void showWorkspaceChanges(JSONObject workspace, JSONObject changes) {
        currentScreen = CHANGES;
        String branch = changes.optString("branch", "Detached HEAD");
        screen("Changes", workspace.optString("title", "Workspace") + " · " + branch);
        Button back = smallButton("‹ Workspaces");
        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        root.addView(back, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), 0, 0, 0, dp(12)));
        plainText(changes.optInt("files_changed") + " files   +" + changes.optInt("additions")
            + "  −" + changes.optInt("deletions"), 14, muted);
        JSONArray files = changes.optJSONArray("files");
        if (files == null || files.length() == 0) {
            emptyState("Working tree clean", "No changed files in this workspace.");
            return;
        }
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(shape(surface, 12, outline));
            row.setClickable(true);
            row.setFocusable(true);
            TextView name = new TextView(this);
            name.setText(file.optString("path", "File"));
            name.setTextSize(15);
            name.setTextColor(ink);
            name.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            TextView stat = new TextView(this);
            stat.setText(file.optString("status", "changed") + "   +"
                + file.optInt("additions") + "  −" + file.optInt("deletions"));
            stat.setTextSize(12);
            stat.setTextColor(muted);
            stat.setPadding(0, dp(4), 0, 0);
            row.addView(name);
            row.addView(stat);
            String path = file.optString("path");
            row.setOnClickListener(view -> loadWorkspaceDiff(workspace, changes, path, row));
            root.addView(row, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(7), 0, 0));
        }
        if (changes.optBoolean("truncated")) plainText("Some files were omitted by the Mac response limit.", 12, muted);
    }

    private void loadWorkspaceDiff(JSONObject workspace, JSONObject changes, String path, View source) {
        source.setEnabled(false);
        worker.execute(() -> {
            try {
                JSONObject diff = client.workspaceDiff(workspace.optString("id"), path, 6000);
                runOnUiThread(() -> showWorkspaceDiff(workspace, changes, diff));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    source.setEnabled(true);
                    message(errorMessage(error));
                });
            }
        });
    }

    private void showWorkspaceDiff(JSONObject workspace, JSONObject changes, JSONObject diff) {
        currentScreen = CHANGES;
        screen(diff.optString("path", "Diff"), "+" + diff.optInt("additions")
            + "  −" + diff.optInt("deletions"));
        Button back = smallButton("‹ Changes");
        back.setOnClickListener(view -> showWorkspaceChanges(workspace, changes));
        root.addView(back, marginParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), 0, 0, 0, dp(12)));
        if (client.supports("workspace.changes.v1")) {
            LinearLayout revisions = new LinearLayout(this);
            Button current = smallButton("Current file");
            Button base = smallButton("Base file");
            revisions.addView(current, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams baseParams = new LinearLayout.LayoutParams(0, dp(48), 1);
            baseParams.setMargins(dp(8), 0, 0, 0);
            revisions.addView(base, baseParams);
            root.addView(revisions, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(12)));
            String path = diff.optString("path");
            current.setOnClickListener(view -> loadWorkspaceFile(
                workspace.optString("id"), path, "current", current));
            base.setOnClickListener(view -> loadWorkspaceFile(
                workspace.optString("id"), path, "base", base));
        }
        if (diff.optBoolean("is_binary")) {
            emptyState("Binary file", "A text diff is not available.");
            return;
        }
        TextView text = new TextView(this);
        text.setText(diffSpans(bounded(diff.optString("unified_diff", "No diff available."), 2_000_000)));
        text.setTextSize(11);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextColor(ink);
        text.setTextIsSelectable(true);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        text.setBackground(shape(surfaceRaised, 12, outline));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.addView(text);
        root.addView(horizontal, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        if (diff.optBoolean("truncated")) plainText("Diff truncated by the Mac response limit.", 12, muted);
    }

    private void loadWorkspaceFile(String workspace, String path, String revision, Button source) {
        busy(source, true, "Loading…");
        worker.execute(() -> {
            try {
                JSONObject stat = client.workspaceFileStat(workspace, path, revision);
                if (!stat.optBoolean("exists", true) || stat.optBoolean("is_directory")) {
                    throw new IllegalStateException("File revision is unavailable");
                }
                byte[] bytes = client.workspaceFileFetch(workspace, path, revision);
                String kind = stat.optString("kind", "binary");
                runOnUiThread(() -> {
                    busy(source, false, "current".equals(revision) ? "Current file" : "Base file");
                    showArtifact(path + " · " + revision, kind, bytes);
                });
            } catch (Exception error) {
                fail(source, "current".equals(revision) ? "Current file" : "Base file", error);
            }
        });
    }

    private CharSequence diffSpans(String raw) {
        SpannableStringBuilder result = new SpannableStringBuilder(raw);
        int start = 0;
        while (start < raw.length()) {
            int end = raw.indexOf('\n', start);
            if (end < 0) end = raw.length();
            int color = raw.startsWith("+", start) && !raw.startsWith("+++", start)
                ? Color.rgb(42, 142, 78)
                : raw.startsWith("-", start) && !raw.startsWith("---", start)
                    ? Color.rgb(204, 70, 70) : muted;
            result.setSpan(new ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        return result;
    }

    private void showRenameWorkspaceDialog(JSONObject workspace) {
        EditText input = new EditText(this);
        input.setText(workspace.optString("title", ""));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        int inset = dp(20);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(inset, 0, inset, 0);
        container.addView(input, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle("Rename workspace")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename", (dialog, which) -> {
                String title = input.getText().toString().trim();
                if (!title.isEmpty()) mutateWorkspace(workspace, "rename", "title", title);
            })
            .show();
    }

    private void confirmCloseWorkspace(JSONObject workspace) {
        new AlertDialog.Builder(this)
            .setTitle("Close workspace?")
            .setMessage(workspace.optString("title", "Workspace") + " will close on your Mac.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close", (dialog, which) -> {
                worker.execute(() -> {
                    try {
                        client.closeWorkspace(workspace.optString("id"),
                            workspace.optString("window_id", null));
                        workspaceSnapshot = client.listWorkspaces();
                        runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
                    } catch (Exception error) {
                        runOnUiThread(() -> message(errorMessage(error)));
                    }
                });
            })
            .show();
    }

    private void mutateWorkspace(JSONObject workspace, String action, String key, String value) {
        worker.execute(() -> {
            try {
                client.workspaceAction(workspace.optString("id"),
                    workspace.optString("window_id", null), action, key, value);
                workspaceSnapshot = client.listWorkspaces();
                runOnUiThread(() -> showWorkspaces(workspaceSnapshot));
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            }
        });
    }

    private void openTerminal(String selectedWorkspace, String selectedSurface, String title, View source) {
        source.setEnabled(false);
        workspaceId = selectedWorkspace;
        surfaceId = selectedSurface;
        surfaceTitle = title;
        grid.selectSurface(selectedSurface);
        worker.execute(() -> {
            try {
                JSONObject replay = client.attach(
                    selectedWorkspace, selectedSurface, terminalScrollbackRows);
                JSONObject frame = replay.optJSONObject("render_grid");
                if (frame != null) grid.apply(frame);
                runOnUiThread(this::showTerminal);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    source.setEnabled(true);
                    message(errorMessage(error));
                });
            }
        });
    }

    private void showTerminal() {
        currentScreen = TERMINAL;
        status = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 11, 14));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        toolbar.setBackgroundColor(Color.rgb(18, 19, 24));
        Button back = toolbarButton("‹");
        Button zoomOut = toolbarButton("−");
        Button zoomIn = toolbarButton("+");
        TextView title = new TextView(this);
        title.setText(surfaceTitle);
        title.setTextColor(Color.rgb(242, 242, 246));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Button refresh = toolbarButton("↻");
        Button files = toolbarButton("Files");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (client.supports("terminal.artifact.v1")) {
            toolbar.addView(files, new LinearLayout.LayoutParams(dp(58), dp(48)));
        }
        toolbar.addView(zoomOut, new LinearLayout.LayoutParams(dp(44), dp(48)));
        toolbar.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(48)));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);
        status = new TextView(this);
        status.setTextColor(Color.rgb(255, 205, 112));
        status.setTextSize(13);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        status.setBackgroundColor(Color.rgb(48, 40, 24));
        status.setVisibility(View.GONE);
        root.addView(status);

        terminal = new TextView(this);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setTextColor(Color.rgb(231, 232, 239));
        terminal.setTextSize(terminalTextSize);
        terminal.setIncludeFontPadding(false);
        terminal.setLineSpacing(dp(2), 1f);
        terminal.setGravity(Gravity.TOP | Gravity.START);
        terminal.setPadding(dp(14), dp(14), dp(14), dp(14));
        terminal.setHorizontallyScrolling(true);
        terminal.setContentDescription("Live terminal output");

        HorizontalScrollView terminalScroll = new HorizontalScrollView(this);
        terminalScroll.setFillViewport(true);
        terminalScroll.setBackgroundColor(Color.rgb(10, 11, 14));
        terminalScroll.addView(terminal, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView verticalScroll = new ScrollView(this);
        verticalScroll.setFillViewport(true);
        verticalScroll.setBackgroundColor(Color.rgb(10, 11, 14));
        verticalScroll.addView(terminalScroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        terminalVerticalScroll = verticalScroll;
        terminalViewport = verticalScroll;
        root.addView(verticalScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        HorizontalScrollView specialScroll = new HorizontalScrollView(this);
        specialScroll.setHorizontalScrollBarEnabled(false);
        specialScroll.setBackgroundColor(Color.rgb(18, 19, 24));
        LinearLayout special = new LinearLayout(this);
        special.setPadding(dp(8), dp(6), dp(8), dp(6));
        addKey(special, "Esc", "\u001b");
        addKey(special, "Tab", "\t");
        addKey(special, "Ctrl C", "\u0003");
        addKey(special, "Ctrl D", "\u0004");
        addKey(special, "←", "\u001b[D");
        addKey(special, "→", "\u001b[C");
        addKey(special, "↑", "\u001b[A");
        addKey(special, "↓", "\u001b[B");
        addKey(special, "⌫", "\u007f");
        addKey(special, "Enter", "\r");
        specialScroll.addView(special);
        root.addView(specialScroll);

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(8), dp(10), dp(10));
        composer.setBackgroundColor(Color.rgb(18, 19, 24));
        EditText input = new EditText(this);
        input.setHint("Message or command");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(157, 159, 170));
        input.setTextSize(16);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setMinHeight(dp(48));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(shape(Color.rgb(34, 35, 42), 12, Color.rgb(62, 64, 74)));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        Button send = toolbarButton("Send");
        Button attachImage = toolbarButton("+");
        attachImage.setContentDescription("Attach image");
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams attachParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        attachParams.setMargins(dp(8), 0, 0, 0);
        composer.addView(attachImage, attachParams);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(72), dp(48));
        sendParams.setMargins(dp(8), 0, 0, 0);
        composer.addView(send, sendParams);
        root.addView(composer);
        mount(root, true);
        applyTerminalSnapshot(grid.snapshot());
        installTerminalViewportBehavior(terminalScroll, verticalScroll);

        back.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        zoomOut.setOnClickListener(view -> changeTerminalZoom(-1));
        zoomIn.setOnClickListener(view -> changeTerminalZoom(1));
        refresh.setOnClickListener(view -> refreshTerminal(refresh));
        files.setOnClickListener(view -> loadTerminalArtifacts(files));
        View.OnClickListener submit = view -> {
            String text = input.getText().toString();
            if (text.trim().isEmpty()) return;
            sendPaste(text, input, send);
        };
        send.setOnClickListener(submit);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            submit.onClick(view);
            return true;
        });
        attachImage.setOnClickListener(view -> {
            pendingImageWorkspace = workspaceId;
            pendingImageSurface = surfaceId;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, PICK_TERMINAL_IMAGE);
        });
    }

    private void refreshTerminal(Button source) {
        busy(source, true, "…");
        worker.execute(() -> {
            try {
                JSONObject replay = client.replay(
                    workspaceId, surfaceId, terminalScrollbackRows);
                JSONObject frame = replay.optJSONObject("render_grid");
                if (frame != null) grid.apply(frame);
                runOnUiThread(() -> {
                    busy(source, false, "↻");
                    updateTerminal();
                });
            } catch (Exception error) {
                fail(source, "↻", error);
            }
        });
    }

    private void changeTerminalZoom(float delta) {
        terminalTextSize = Math.max(8f, Math.min(22f, terminalTextSize + delta));
        getSharedPreferences("display", MODE_PRIVATE).edit()
            .putFloat("terminal_text_size", terminalTextSize).apply();
        if (terminal != null) terminal.setTextSize(terminalTextSize);
    }

    private void addKey(LinearLayout row, String label, String input) {
        Button key = keyButton(label);
        key.setOnClickListener(view -> sendInput(input));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(0, 0, dp(7), 0);
        row.addView(key, params);
    }

    private void sendInput(String text) {
        if (client == null || workspaceId == null || surfaceId == null) return;
        worker.execute(() -> {
            try { client.input(workspaceId, surfaceId, text); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    private void sendPaste(String text, EditText composer, Button source) {
        String selectedWorkspace = workspaceId;
        String selectedSurface = surfaceId;
        busy(source, true, "Sending…");
        worker.execute(() -> {
            try {
                client.paste(selectedWorkspace, selectedSurface, text);
                runOnUiThread(() -> {
                    composer.setText("");
                    busy(source, false, "Send");
                });
            } catch (Exception error) {
                fail(source, "Send", error);
            }
        });
    }

    private static String terminalKey(KeyEvent event) {
        switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_ENTER: return "\r";
        case KeyEvent.KEYCODE_TAB: return "\t";
        case KeyEvent.KEYCODE_ESCAPE: return "\u001b";
        case KeyEvent.KEYCODE_DEL: return "\u007f";
        case KeyEvent.KEYCODE_DPAD_UP: return "\u001b[A";
        case KeyEvent.KEYCODE_DPAD_DOWN: return "\u001b[B";
        case KeyEvent.KEYCODE_DPAD_RIGHT: return "\u001b[C";
        case KeyEvent.KEYCODE_DPAD_LEFT: return "\u001b[D";
        case KeyEvent.KEYCODE_MOVE_HOME: return "\u001b[H";
        case KeyEvent.KEYCODE_MOVE_END: return "\u001b[F";
        case KeyEvent.KEYCODE_PAGE_UP: return "\u001b[5~";
        case KeyEvent.KEYCODE_PAGE_DOWN: return "\u001b[6~";
        default:
            if (event.isCtrlPressed() && event.getKeyCode() >= KeyEvent.KEYCODE_A
                && event.getKeyCode() <= KeyEvent.KEYCODE_Z) {
                return String.valueOf((char) (event.getKeyCode() - KeyEvent.KEYCODE_A + 1));
            }
            int unicode = event.getUnicodeChar();
            if (unicode <= 0) return null;
            String text = new String(Character.toChars(unicode));
            return event.isAltPressed() ? "\u001b" + text : text;
        }
    }

    private void installTerminalViewportBehavior(HorizontalScrollView viewport, ScrollView vertical) {
        viewportColumns = 0;
        viewportRows = 0;
        effectiveViewportColumns = 0;
        effectiveViewportRows = 0;
        vertical.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.max(0, right - left - terminal.getPaddingLeft() - terminal.getPaddingRight());
            int height = Math.max(0, bottom - top - terminal.getPaddingTop() - terminal.getPaddingBottom());
            float cellWidth = Math.max(1f, terminal.getPaint().measureText("M"));
            int lineHeight = Math.max(1, terminal.getLineHeight());
            int columns = Math.max(20, Math.min(240, (int) (width / cellWidth)));
            int rows = Math.max(5, Math.min(120, height / lineHeight));
            if (columns == viewportColumns && rows == viewportRows) return;
            viewportColumns = columns;
            viewportRows = rows;
            reportTerminalViewport(columns, rows);
        });

        GestureDetector gestures = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                private double pendingLines;

                @Override public boolean onDown(MotionEvent event) { return true; }

                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    float cellWidth = Math.max(1f, terminal.getPaint().measureText("M"));
                    int column = Math.max(0, (int) ((event.getX() + viewport.getScrollX()
                        - terminal.getPaddingLeft()) / cellWidth));
                    int row = (int) ((event.getY() - terminal.getPaddingTop())
                        / Math.max(1, terminal.getLineHeight())) - grid.historyRows();
                    if (row < 0) return true;
                    clickTerminal(column, row);
                    return true;
                }

                @Override public boolean onScroll(MotionEvent first, MotionEvent current,
                                                  float distanceX, float distanceY) {
                    if (Math.abs(distanceY) <= Math.abs(distanceX)) return false;
                    if (grid.isPrimaryScreen()) return false;
                    pendingLines += distanceY / Math.max(1, terminal.getLineHeight());
                    if (Math.abs(pendingLines) < 0.5) return true;
                    double delivery = pendingLines;
                    pendingLines = 0;
                    scrollTerminal(delivery);
                    return true;
                }
            });
        viewport.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
            return gestures.onTouchEvent(event);
        });
    }

    private void clickTerminal(int column, int row) {
        String selectedWorkspace = workspaceId;
        String selectedSurface = surfaceId;
        if (client == null || selectedWorkspace == null || selectedSurface == null) return;
        worker.execute(() -> {
            try { client.terminalMouse(selectedWorkspace, selectedSurface, column, row); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    private void reportTerminalViewport(int columns, int rows) {
        if (client == null || !client.supports("terminal.viewport.v1")) return;
        int generation = ++viewportGeneration;
        String selectedWorkspace = workspaceId;
        String selectedSurface = surfaceId;
        worker.execute(() -> {
            try {
                JSONObject response = client.viewport(
                    selectedWorkspace, selectedSurface, columns, rows, generation);
                int effectiveColumns = response.optInt("columns", 0);
                int effectiveRows = response.optInt("rows", 0);
                if (generation != viewportGeneration
                    || !selectedWorkspace.equals(workspaceId)
                    || !selectedSurface.equals(surfaceId)
                    || effectiveColumns <= 0 || effectiveRows <= 0) return;
                boolean resized = effectiveColumns != effectiveViewportColumns
                    || effectiveRows != effectiveViewportRows;
                effectiveViewportColumns = effectiveColumns;
                effectiveViewportRows = effectiveRows;
                if (!resized) return;
                JSONObject replay = client.replay(
                    selectedWorkspace, selectedSurface, terminalScrollbackRows);
                if (generation != viewportGeneration
                    || !selectedWorkspace.equals(workspaceId)
                    || !selectedSurface.equals(surfaceId)) return;
                JSONObject frame = replay.optJSONObject("render_grid");
                if (frame != null) grid.apply(frame);
                runOnUiThread(this::updateTerminal);
            } catch (Exception error) {
                runOnUiThread(() -> message(errorMessage(error)));
            }
        });
    }

    private void clearTerminalViewport() {
        if (surfaceId == null || workspaceId == null) return;
        String detachedWorkspace = workspaceId;
        String detachedSurface = surfaceId;
        int generation = ++viewportGeneration;
        workspaceId = null;
        surfaceId = null;
        terminal = null;
        terminalViewport = null;
        terminalVerticalScroll = null;
        if (client == null || !client.supports("terminal.viewport.v1") || worker.isShutdown()) return;
        worker.execute(() -> {
            try { client.clearViewport(detachedWorkspace, detachedSurface, generation); }
            catch (Exception ignored) { /* Connection close also clears viewport pins. */ }
        });
    }

    private void scrollTerminal(double lines) {
        if (client == null || !client.supports("terminal.viewport.v1")) return;
        String selectedWorkspace = workspaceId;
        String selectedSurface = surfaceId;
        worker.execute(() -> {
            try { client.scroll(selectedWorkspace, selectedSurface, lines, 0, 0); }
            catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
        });
    }

    @Override public void onEvent(String topic, JSONObject payload) {
        if ("terminal.render_grid".equals(topic)) {
            if (!grid.apply(payload) || !terminalUpdatePending.compareAndSet(false, true)) return;
            runOnUiThread(() -> {
                if (terminal == null) {
                    terminalUpdatePending.set(false);
                    return;
                }
                terminal.postDelayed(() -> {
                    terminalUpdatePending.set(false);
                    updateTerminal();
                }, 16);
            });
        } else if ("workspace.updated".equals(topic)) {
            refreshWorkspacesFromEvent();
        } else if ("mobile.sync.delta".equals(topic)) {
            refreshWorkspacesFromEvent();
        } else if ("notification.badge".equals(topic)) {
            unreadCount = Math.max(0, payload.optInt("unread_count", unreadCount));
        } else if ("notification.feed.changed".equals(topic)
            && currentScreen == NOTIFICATIONS) {
            refreshNotificationsFromEvent();
        } else if ("browser.frame".equals(topic)) {
            acceptBrowserFrame(payload);
        } else if ("browser.state".equals(topic)) {
            acceptBrowserState(payload);
        } else if ("browser.closed".equals(topic)) {
            if (payload.optString("panel_id").equals(browserPanelId)) {
                runOnUiThread(() -> {
                    if (browserSnapshot != null) showBrowsers(browserSnapshot);
                });
            }
        } else if ("browser.dialog".equals(topic)) {
            if (payload.optString("panel_id").equals(browserPanelId)) {
                runOnUiThread(() -> showBrowserDialog(payload));
            }
        } else if ("chat.session".equals(topic) && currentScreen == CHAT
            && currentChatSession == null) {
            refreshChatSessionsFromEvent();
        } else if (("chat.message".equals(topic) || "chat.session".equals(topic))
            && currentScreen == CHAT && currentChatSession != null
            && payload.optString("session_id").equals(currentChatSession.optString("session_id"))) {
            refreshCurrentChat();
        }
    }

    private void refreshChatSessionsFromEvent() {
        if (!chatRefreshPending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                JSONObject sessions = client.chatSessions(null);
                chatSnapshot = sessions;
                runOnUiThread(() -> {
                    if (currentScreen == CHAT && currentChatSession == null) showChatSessions(sessions);
                });
            } catch (Exception ignored) {
            } finally {
                chatRefreshPending.set(false);
            }
        });
    }

    private void refreshCurrentChat() {
        if (!chatRefreshPending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                Thread.sleep(120);
                JSONObject session = currentChatSession;
                if (session == null) return;
                JSONObject history = client.chatHistory(session.optString("session_id"));
                runOnUiThread(() -> {
                    if (currentScreen == CHAT && currentChatSession == session) showChat(session, history);
                });
            } catch (Exception ignored) {
            } finally {
                chatRefreshPending.set(false);
            }
        });
    }

    private void acceptBrowserFrame(JSONObject payload) {
        if (!payload.optString("panel_id").equals(browserPanelId)) return;
        long incomingSequence = payload.optLong("seq", -1);
        if (incomingSequence < 0 || incomingSequence <= browserLastFrameSequence) return;
        JSONObject queued = pendingBrowserFrame;
        if (queued != null && incomingSequence <= queued.optLong("seq", -1)) return;
        pendingBrowserFrame = payload;
        if (!browserDecodeRunning.compareAndSet(false, true)) return;
        browserDecoder.execute(() -> {
            try {
                JSONObject frame;
                while ((frame = pendingBrowserFrame) != null) {
                    pendingBrowserFrame = null;
                    String format = frame.optString("format");
                    if (!"jpeg".equals(format) && !"png".equals(format)) continue;
                    String encoded = frame.optString("data_b64");
                    if (encoded.length() > 6 * 1024 * 1024) continue;
                    byte[] data;
                    try { data = Base64.decode(encoded, Base64.DEFAULT); }
                    catch (IllegalArgumentException ignored) { continue; }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bitmap == null) continue;
                    long sequence = frame.optLong("seq", 0);
                    if (sequence <= browserLastFrameSequence) {
                        bitmap.recycle();
                        continue;
                    }
                    browserLastFrameSequence = sequence;
                    String panel = frame.optString("panel_id");
                    double pageWidth = frame.optDouble("page_width", bitmap.getWidth());
                    double pageHeight = frame.optDouble("page_height", bitmap.getHeight());
                    int pixelWidth = frame.optInt("pixel_width", bitmap.getWidth());
                    int pixelHeight = frame.optInt("pixel_height", bitmap.getHeight());
                    runOnUiThread(() -> {
                        if (!panel.equals(browserPanelId) || browserImage == null) {
                            bitmap.recycle();
                            return;
                        }
                        browserPageWidth = pageWidth;
                        browserPageHeight = pageHeight;
                        browserPixelWidth = pixelWidth;
                        browserPixelHeight = pixelHeight;
                        Bitmap previous = browserBitmap;
                        browserBitmap = bitmap;
                        browserImage.setImageBitmap(bitmap);
                        if (previous != null && previous != bitmap) previous.recycle();
                        worker.execute(() -> {
                            try { client.acknowledgeBrowserFrame(panel, sequence); }
                            catch (Exception ignored) {}
                        });
                    });
                }
            } finally {
                browserDecodeRunning.set(false);
                if (pendingBrowserFrame != null) acceptBrowserFrame(pendingBrowserFrame);
            }
        });
    }

    private void acceptBrowserState(JSONObject payload) {
        if (!payload.optString("panel_id").equals(browserPanelId)) return;
        browserState = payload;
        runOnUiThread(() -> {
            if (browserAddress == null) return;
            String address = payload.optString("url", "");
            if (!browserAddress.hasFocus() && !address.isEmpty()) browserAddress.setText(address);
        });
    }

    private void showBrowserDialog(JSONObject payload) {
        JSONArray buttons = payload.optJSONArray("buttons");
        if (buttons == null || buttons.length() == 0) return;
        String[] labels = new String[Math.min(buttons.length(), 8)];
        for (int i = 0; i < labels.length; i++) {
            JSONObject button = buttons.optJSONObject(i);
            labels[i] = bounded(button == null ? "Continue"
                : button.optString("label", "Continue"), 80);
        }
        JSONObject textField = payload.optJSONObject("text_field");
        EditText input = null;
        if (textField != null) {
            input = new EditText(this);
            input.setHint(bounded(textField.optString("placeholder", ""), 120));
            input.setText(bounded(textField.optString("initial", ""), 4096));
            if (textField.optBoolean("secure")) {
                input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
        }
        EditText responseInput = input;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(bounded(payload.optString("title", "Browser"), 180))
            .setMessage(bounded(payload.optString("message", ""), 2048));
        if (input != null) {
            FrameLayout holder = new FrameLayout(this);
            holder.setPadding(dp(20), 0, dp(20), 0);
            holder.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            builder.setView(holder);
        }
        builder.setItems(labels, (dialog, which) -> {
            JSONObject selected = buttons.optJSONObject(which);
            if (selected == null) return;
            String panel = payload.optString("panel_id");
            String dialogId = payload.optString("dialog_id");
            String buttonId = selected.optString("id");
            String text = responseInput == null ? null : responseInput.getText().toString();
            worker.execute(() -> {
                try { client.browserDialogRespond(panel, dialogId, buttonId, text); }
                catch (Exception error) { runOnUiThread(() -> message(errorMessage(error))); }
            });
        }).show();
    }

    private void refreshWorkspacesFromEvent() {
        if (!workspaceRefreshPending.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                workspaceSnapshot = client.listWorkspaces();
                runOnUiThread(() -> {
                    workspaceRefreshPending.set(false);
                    if (currentScreen == WORKSPACES) showWorkspaces(workspaceSnapshot);
                });
            } catch (Exception ignored) {
                workspaceRefreshPending.set(false);
            }
        });
    }

    private void refreshNotificationsFromEvent() {
        worker.execute(() -> {
            try {
                notificationSnapshot = client.notificationFeed();
                publishUnreadNotifications(notificationSnapshot);
                runOnUiThread(() -> {
                    if (currentScreen == NOTIFICATIONS) showNotifications(notificationSnapshot);
                });
            } catch (Exception ignored) {}
        });
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(
            "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsEnabled() {
        return canPostNotifications() && getSharedPreferences("notifications", MODE_PRIVATE)
            .getBoolean("enabled", true);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_PERMISSION) return;
        boolean granted = grantResults.length > 0
            && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        getSharedPreferences("notifications", MODE_PRIVATE).edit()
            .putBoolean("enabled", granted).apply();
        if (currentScreen == SETTINGS) showSettings();
    }

    private void publishUnreadNotifications(JSONObject snapshot) {
        if (!notificationsEnabled()) return;
        JSONArray items = snapshot.optJSONArray("notifications");
        if (items == null) return;
        SharedPreferences preferences = getSharedPreferences("notifications", MODE_PRIVATE);
        Set<String> delivered = new HashSet<>(preferences.getStringSet("delivered", Set.of()));
        NotificationManager manager = getSystemService(NotificationManager.class);
        Set<String> unread = new HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && !item.optBoolean("is_read") && !item.optString("id").isBlank()) {
                unread.add(item.optString("id"));
            }
        }
        for (String id : new HashSet<>(delivered)) {
            if (!unread.contains(id)) {
                manager.cancel(id.hashCode());
                delivered.remove(id);
            }
        }
        int posted = 0;
        for (int i = 0; i < items.length() && posted < 5; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || item.optBoolean("is_read")) continue;
            String id = item.optString("id");
            if (id.isBlank() || !delivered.add(id)) continue;
            Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("notification_workspace_id", item.optString("workspace_id"))
                .putExtra("notification_surface_id", item.optString("surface_id"));
            PendingIntent pending = PendingIntent.getActivity(this, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(bounded(item.optString("title", "cmux activity"), 180))
                .setContentText(bounded(item.optString("body", "Agent activity on your Mac"), 300))
                .setStyle(new Notification.BigTextStyle().bigText(
                    bounded(item.optString("body", "Agent activity on your Mac"), 1200)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
            manager.notify(id.hashCode(), notification);
            posted++;
        }
        preferences.edit().putStringSet("delivered", delivered).apply();
        try {
            client.notificationReconcile(new JSONArray(delivered));
        } catch (Exception ignored) {}
    }

    private void dismissSystemNotification(String id) {
        if (id == null || id.isBlank()) return;
        getSystemService(NotificationManager.class).cancel(id.hashCode());
        SharedPreferences preferences = getSharedPreferences("notifications", MODE_PRIVATE);
        Set<String> delivered = new HashSet<>(preferences.getStringSet("delivered", Set.of()));
        if (delivered.remove(id)) preferences.edit().putStringSet("delivered", delivered).apply();
    }

    private void clearSystemNotifications() {
        SharedPreferences preferences = getSharedPreferences("notifications", MODE_PRIVATE);
        Set<String> delivered = new HashSet<>(preferences.getStringSet("delivered", Set.of()));
        NotificationManager manager = getSystemService(NotificationManager.class);
        for (String id : delivered) manager.cancel(id.hashCode());
        preferences.edit().remove("delivered").apply();
    }

    private void openPendingNotification() {
        String targetWorkspace = pendingNotificationWorkspace;
        if (targetWorkspace == null || workspaceSnapshot == null || client == null) return;
        pendingNotificationWorkspace = null;
        String targetSurface = pendingNotificationSurface;
        pendingNotificationSurface = null;
        JSONObject workspace = findWorkspace(targetWorkspace);
        if (workspace == null) {
            message("The notification's workspace is no longer open.");
            return;
        }
        JSONObject terminalItem = findTerminal(workspace, targetSurface);
        if (terminalItem == null) {
            JSONArray terminals = workspace.optJSONArray("terminals");
            terminalItem = terminals == null ? null : terminals.optJSONObject(0);
        }
        if (terminalItem == null) {
            message("The notification's terminal is no longer open.");
            return;
        }
        openTerminal(targetWorkspace, terminalItem.optString("id"),
            terminalItem.optString("title", "Terminal"), new View(this));
    }

    @Override public void onDisconnect(String reason) {
        if (connecting) return;
        scheduleReconnect(reason);
    }

    private void updateTerminal() {
        if (terminal == null || currentScreen != TERMINAL) return;
        applyTerminalSnapshot(grid.snapshot());
    }

    private void applyTerminalSnapshot(TerminalSnapshot snapshot) {
        if (terminal == null) return;
        ScrollView scroll = terminalVerticalScroll;
        int previousY = scroll == null ? 0 : scroll.getScrollY();
        int previousHeight = terminal.getHeight();
        boolean followsOutput = scroll == null || previousHeight <= scroll.getHeight()
            || previousY + scroll.getHeight() >= previousHeight - terminal.getLineHeight() * 2;
        int defaultForeground = parseColor(snapshot.foreground, Color.rgb(231, 232, 239));
        int defaultBackground = parseColor(snapshot.background, Color.rgb(10, 11, 14));
        SpannableStringBuilder styled = new SpannableStringBuilder(snapshot.text);
        for (TerminalSnapshot.Run run : snapshot.runs) {
            if (run.start < 0 || run.end > styled.length() || run.start >= run.end) continue;
            TerminalSnapshot.Style style = run.style;
            int foregroundColor = parseColor(style.foreground, defaultForeground);
            int backgroundColor = parseColor(style.background, defaultBackground);
            if (style.inverse) {
                int swap = foregroundColor;
                foregroundColor = backgroundColor;
                backgroundColor = swap;
            }
            if (style.invisible) foregroundColor = backgroundColor;
            if (style.faint) foregroundColor = blend(foregroundColor, backgroundColor, 0.55f);
            styled.setSpan(new ForegroundColorSpan(foregroundColor), run.start, run.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (style.background != null || style.inverse || style.invisible) {
                styled.setSpan(new BackgroundColorSpan(backgroundColor), run.start, run.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int face = style.bold && style.italic ? Typeface.BOLD_ITALIC
                : style.bold ? Typeface.BOLD : style.italic ? Typeface.ITALIC : Typeface.NORMAL;
            if (face != Typeface.NORMAL) {
                styled.setSpan(new StyleSpan(face), run.start, run.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.underline) {
                styled.setSpan(new UnderlineSpan(), run.start, run.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (style.strikethrough) {
                styled.setSpan(new StrikethroughSpan(), run.start, run.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (snapshot.cursorVisible && snapshot.cursorOffset >= 0
            && snapshot.cursorOffset < styled.length()) {
            int cursorEnd = snapshot.cursorOffset + 1;
            styled.setSpan(new BackgroundColorSpan(parseColor(snapshot.cursorColor, defaultForeground)),
                snapshot.cursorOffset, cursorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(parseColor(snapshot.cursorTextColor, defaultBackground)),
                snapshot.cursorOffset, cursorEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        terminal.setTextColor(defaultForeground);
        terminal.setBackgroundColor(defaultBackground);
        if (terminalViewport != null) terminalViewport.setBackgroundColor(defaultBackground);
        terminal.setText(styled);
        if (scroll != null) scroll.post(() -> {
            if (terminal != null && followsOutput) scroll.fullScroll(View.FOCUS_DOWN);
            else scroll.scrollTo(scroll.getScrollX(), previousY);
        });
    }

    private void screen(String title, String subtitle) {
        terminal = null;
        chatAttachmentButton = null;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        root.setBackgroundColor(background);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        scroll.addView(root);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(background);
        shell.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (client != null && currentScreen >= WORKSPACES && currentScreen != TERMINAL) {
            shell.addView(bottomNavigation(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        }
        mount(shell, false);

        TextView heading = plainText(title, 26, ink);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(-0.015f);
        LinearLayout.LayoutParams headingParams = (LinearLayout.LayoutParams) heading.getLayoutParams();
        headingParams.setMargins(0, dp(4), 0, 0);
        heading.setLayoutParams(headingParams);
        TextView detail = plainText(subtitle, 14, muted);
        detail.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams detailParams = (LinearLayout.LayoutParams) detail.getLayoutParams();
        detailParams.setMargins(0, dp(5), 0, dp(18));
        detail.setLayoutParams(detailParams);
        status = plainText("", 14, ink);
        status.setVisibility(View.GONE);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(shape(surfaceRaised, 10, outline));
        LinearLayout.LayoutParams statusParams = (LinearLayout.LayoutParams) status.getLayoutParams();
        statusParams.setMargins(0, 0, 0, dp(12));
        status.setLayoutParams(statusParams);
    }

    private View bottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(6), dp(5), dp(6), dp(5));
        nav.setBackground(shape(surface, 0, outline));
        Button workspaces = navButton("Workspaces", currentScreen == WORKSPACES);
        Button activity = navButton(unreadCount > 0 ? "Activity · " + unreadCount : "Activity",
            currentScreen == NOTIFICATIONS);
        Button web = navButton("Web", currentScreen == BROWSERS);
        Button agents = navButton("Agents", currentScreen == CHAT);
        Button settings = navButton("Settings", currentScreen == SETTINGS);
        nav.addView(workspaces, new LinearLayout.LayoutParams(0, dp(54), 1));
        nav.addView(activity, new LinearLayout.LayoutParams(0, dp(54), 1));
        nav.addView(web, new LinearLayout.LayoutParams(0, dp(54), 1));
        nav.addView(agents, new LinearLayout.LayoutParams(0, dp(54), 1));
        nav.addView(settings, new LinearLayout.LayoutParams(0, dp(54), 1));
        workspaces.setOnClickListener(view -> showWorkspaces(workspaceSnapshot));
        activity.setOnClickListener(view -> loadNotifications(activity));
        web.setOnClickListener(view -> {
            if (client.supports("browser.stream.v1")) loadBrowsers(web);
            else showBrowsers(new JSONObject());
        });
        agents.setOnClickListener(view -> loadChatSessions(agents));
        settings.setOnClickListener(view -> showSettings());
        activity.setVisibility(client.supports("notification.feed.v1") ? View.VISIBLE : View.GONE);
        return nav;
    }

    private Button navButton(String text, boolean selected) {
        Button view = baseButton(text);
        view.setTextSize(11);
        view.setPadding(dp(2), 0, dp(2), 0);
        view.setTextColor(selected ? accent : muted);
        view.setBackground(shape(selected ? surfaceRaised : Color.TRANSPARENT, 12,
            Color.TRANSPARENT));
        view.setContentDescription(text + (selected ? ", selected" : ""));
        view.setSelected(selected);
        return view;
    }

    private void sectionLabel(String text) {
        TextView label = plainText(text, 18, ink);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
        params.setMargins(0, dp(4), 0, dp(5));
        label.setLayoutParams(params);
    }

    private TextView plainText(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        root.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private EditText field(String label, String hint, int inputType) {
        TextView caption = plainText(label, 14, ink);
        caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams captionParams = (LinearLayout.LayoutParams) caption.getLayoutParams();
        captionParams.setMargins(0, dp(13), 0, dp(7));
        caption.setLayoutParams(captionParams);

        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(muted);
        view.setTextColor(ink);
        view.setTextSize(16);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setMinHeight(dp(54));
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(shape(surface, 12, outline));
        root.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private LinearLayout dialogForm() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);
        return form;
    }

    private EditText dialogField(LinearLayout form, String label, String hint) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(13);
        caption.setTextColor(ink);
        caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        form.addView(caption, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(12), 0, dp(4)));
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setBackground(shape(surface, 10, outline));
        input.setPadding(dp(12), 0, dp(12), 0);
        form.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return input;
    }

    private Button button(String text) {
        Button view = baseButton(text);
        view.setTextColor(Color.WHITE);
        view.setBackground(shape(accent, 12, accent));
        root.addView(view, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, dp(18), 0, 0));
        return view;
    }

    private Button secondaryButton(String text) {
        Button view = baseButton(text);
        view.setTextColor(ink);
        view.setBackground(shape(Color.TRANSPARENT, 12, outline));
        root.addView(view, marginParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), 0, dp(10), 0, 0));
        return view;
    }

    private Button smallButton(String text) {
        Button view = baseButton(text);
        view.setTextColor(accent);
        view.setMinWidth(dp(48));
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackground(shape(surfaceRaised, 12, outline));
        return view;
    }

    private Button toolbarButton(String text) {
        Button view = baseButton(text);
        view.setTextColor(Color.rgb(228, 229, 237));
        view.setTextSize(14);
        view.setPadding(dp(6), 0, dp(6), 0);
        view.setBackground(shape(Color.TRANSPARENT, 10, Color.TRANSPARENT));
        return view;
    }

    private Button keyButton(String text) {
        Button view = baseButton(text);
        view.setTextColor(Color.rgb(221, 222, 230));
        view.setTextSize(13);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setMinWidth(dp(48));
        view.setBackground(shape(Color.rgb(35, 36, 44), 9, Color.rgb(62, 64, 74)));
        return view;
    }

    private Button baseButton(String text) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(0);
        view.setStateListAnimator(null);
        return view;
    }

    private void workspaceHeading(String title, String directory) {
        TextView heading = plainText(title, 18, ink);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) heading.getLayoutParams();
        params.setMargins(0, dp(18), 0, 0);
        heading.setLayoutParams(params);
        if (!directory.isEmpty()) {
            TextView path = plainText(shortPath(directory), 13, muted);
            path.setSingleLine(true);
            path.setEllipsize(android.text.TextUtils.TruncateAt.START);
            LinearLayout.LayoutParams pathParams = (LinearLayout.LayoutParams) path.getLayoutParams();
            pathParams.setMargins(0, dp(2), 0, dp(4));
            path.setLayoutParams(pathParams);
        }
    }

    private View terminalRow(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(68));
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        row.setBackground(shape(surface, 12, outline));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(ink);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView state = new TextView(this);
        state.setText(R.string.terminal_live);
        state.setTextColor(muted);
        state.setTextSize(13);
        copy.addView(name);
        copy.addView(state);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(muted);
        arrow.setTextSize(28);
        arrow.setGravity(Gravity.CENTER);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(36), dp(48)));
        return row;
    }

    private void emptyState(String title, String detail) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(34), dp(24), dp(34));
        box.setBackground(shape(surface, 12, outline));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(ink);
        heading.setTextSize(17);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        TextView body = new TextView(this);
        body.setText(detail);
        body.setTextColor(muted);
        body.setTextSize(14);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(6), 0, 0);
        box.addView(heading);
        box.addView(body);
        root.addView(box, marginParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0, dp(16), 0, 0));
    }

    private JSONObject findWorkspace(String id) {
        JSONArray workspaces = workspaceSnapshot == null
            ? null : workspaceSnapshot.optJSONArray("workspaces");
        if (workspaces == null) return null;
        for (int i = 0; i < workspaces.length(); i++) {
            JSONObject workspace = workspaces.optJSONObject(i);
            if (workspace != null && id.equals(workspace.optString("id"))) return workspace;
        }
        return null;
    }

    private static JSONObject findTerminal(JSONObject workspace, String id) {
        JSONArray terminals = workspace.optJSONArray("terminals");
        if (terminals == null) return null;
        for (int i = 0; i < terminals.length(); i++) {
            JSONObject terminal = terminals.optJSONObject(i);
            if (terminal != null && id.equals(terminal.optString("id"))) return terminal;
        }
        return null;
    }

    private static String bounded(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) return value == null ? "" : value;
        return value.substring(0, maxCharacters) + "…";
    }

    private void busy(Button button, boolean busy, String text) {
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.62f : 1f);
        button.setText(text);
    }

    private void fail(Button button, String retryLabel, Exception error) {
        runOnUiThread(() -> {
            busy(button, false, retryLabel);
            message(errorMessage(error));
        });
    }

    private void message(String text) {
        if (status == null) return;
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void mount(View content, boolean terminalBars) {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(terminalBars ? Color.rgb(10, 11, 14) : background);
        host.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(host);
        getWindow().setStatusBarColor(terminalBars ? Color.rgb(18, 19, 24) : background);
        getWindow().setNavigationBarColor(terminalBars ? Color.rgb(18, 19, 24) : background);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (!terminalBars && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!terminalBars && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void resolveTheme() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#101114" : "#F7F7F8");
        surface = Color.parseColor(dark ? "#1A1B20" : "#FFFFFF");
        surfaceRaised = Color.parseColor(dark ? "#23242B" : "#EEEEF2");
        ink = Color.parseColor(dark ? "#F1F1F4" : "#18181C");
        muted = Color.parseColor(dark ? "#B2B3BD" : "#5E5F69");
        outline = Color.parseColor(dark ? "#3E404A" : "#D8D8DF");
        accent = Color.parseColor(dark ? "#8A88FF" : "#5B5BD6");
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (Color.alpha(stroke) > 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height,
                                                    int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private static String shortPath(String path) {
        return path.replaceFirst("^/Users/[^/]+", "~");
    }

    private static String errorMessage(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static int blend(int foreground, int background, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
            Math.round(Color.red(foreground) * amount + Color.red(background) * inverse),
            Math.round(Color.green(foreground) * amount + Color.green(background) * inverse),
            Math.round(Color.blue(foreground) * amount + Color.blue(background) * inverse));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
