package dev.cmux.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps saved Mac subscriptions alive when the Activity is not visible. */
public final class BackgroundMonitorService extends Service {
    private static final int FOREGROUND_ID = 64001;
    private static final String CHANNEL = "cmux-background-monitor";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<String> refreshPending = new HashSet<>();
    private MachineConnectionManager connections;
    private MachineRegistry registry;

    public static void start(android.content.Context context) {
        android.content.Context app = context.getApplicationContext();
        androidx.core.content.ContextCompat.startForegroundService(app,
            new Intent(app, BackgroundMonitorService.class));
    }

    public static void stop(android.content.Context context) {
        context.getApplicationContext().stopService(
            new Intent(context.getApplicationContext(), BackgroundMonitorService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        registry = new MachineRegistry(getSharedPreferences("machines", MODE_PRIVATE));
        createChannel();
        startForeground(FOREGROUND_ID, notification("Starting monitor"));
        StackAuthClient auth = new StackAuthClient(new SecureTokenStore(this));
        if (!auth.hasSession() || !getSharedPreferences("notifications", MODE_PRIVATE)
            .getBoolean("enabled", true) || !getSharedPreferences("notifications", MODE_PRIVATE)
            .getBoolean("background_monitor", false)) {
            stopSelf();
            return;
        }
        connections = new MachineConnectionManager(this, auth, new MachineConnectionManager.Listener() {
            @Override public void onStateChanged(String machineId, MachineConnectionManager.State state,
                                                  String message) {
                updateForeground();
            }

            @Override public void onEvent(String machineId, String topic, JSONObject payload) {
                if ("notification.badge".equals(topic)
                    || "notification.feed.changed".equals(topic)) refreshNotifications(machineId);
            }

            @Override public void onDisconnected(String machineId, String message) {
                updateForeground();
            }
        });
        worker.execute(this::connectSavedMachines);
    }

    private void connectSavedMachines() {
        for (MachineRegistry.Machine machine : registry.list()) {
            try {
                connections.connect(machine);
                refreshNotifications(machine.id());
            } catch (Exception ignored) {
                updateForeground();
            }
        }
        updateForeground();
    }

    private void refreshNotifications(String machineId) {
        synchronized (refreshPending) {
            if (!refreshPending.add(machineId)) return;
        }
        worker.execute(() -> {
            try {
                CmuxClient client = connections.client(machineId);
                if (client == null) return;
                JSONObject feed = client.notificationFeed();
                JSONArray notifications = feed.optJSONArray("notifications");
                int unread = unreadCount(notifications);
                JSONObject latest = latestUnread(notifications);
                if (unread > 0) postMachineNotification(machineId, unread, latest);
                else cancelMachineNotification(machineId);
            } catch (Exception ignored) {
            } finally {
                synchronized (refreshPending) {
                    refreshPending.remove(machineId);
                }
            }
        });
    }

    private void postMachineNotification(String machineId, int unread, JSONObject latest) {
        MachineRegistry.Machine machine = findMachine(machineId);
        if (machine == null) return;
        Intent open = new Intent(this, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("notification_machine_id", machineId)
            .putExtra("notification_workspace_id", latest == null ? "" : latest.optString("workspace_id"))
            .putExtra("notification_surface_id", latest == null ? "" : latest.optString("surface_id"));
        PendingIntent pending = PendingIntent.getActivity(this, machineId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(machine.displayName())
            .setContentText(unread + " unread cmux notification" + (unread == 1 ? "" : "s"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build();
        getSystemService(NotificationManager.class).notify(machineId.hashCode(), notification);
    }

    private void cancelMachineNotification(String machineId) {
        getSystemService(NotificationManager.class).cancel(machineId.hashCode());
    }

    private void updateForeground() {
        if (connections == null) return;
        int online = 0;
        for (MachineConnectionManager.Status status : connections.statuses(registry.list())) {
            if (status.state() == MachineConnectionManager.State.CONNECTED) online++;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(FOREGROUND_ID, notification(online + " Mac" + (online == 1 ? "" : "s") + " monitored"));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("cmux background monitor")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private void createChannel() {
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "cmux background monitor", NotificationManager.IMPORTANCE_LOW));
    }

    private MachineRegistry.Machine findMachine(String id) {
        for (MachineRegistry.Machine machine : registry.list()) {
            if (machine.id().equals(id)) return machine;
        }
        return null;
    }

    private static int unreadCount(JSONArray values) {
        if (values == null) return 0;
        int count = 0;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && !value.optBoolean("is_read")) count++;
        }
        return count;
    }

    private static JSONObject latestUnread(JSONArray values) {
        if (values == null) return null;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && !value.optBoolean("is_read")) return value;
        }
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (connections != null) connections.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
