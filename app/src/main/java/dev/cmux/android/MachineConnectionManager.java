package dev.cmux.android;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns one cmux client per saved Mac while the app is alive. */
final class MachineConnectionManager implements AutoCloseable {
    enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    record Status(MachineRegistry.Machine machine, State state, String message) {}

    interface Listener {
        void onStateChanged(String machineId, State state, String message);
        void onEvent(String machineId, String topic, JSONObject payload);
        void onDisconnected(String machineId, String message);
    }

    interface Connector {
        CmuxClient connect(MachineRegistry.Machine machine, CmuxClient.EventListener listener)
            throws Exception;
    }

    private final Listener listener;
    private final Connector connector;
    private final Map<String, CmuxClient> clients = new HashMap<>();
    private final Map<String, JSONObject> snapshots = new HashMap<>();
    private final Map<String, Status> states = new HashMap<>();
    private boolean closed;

    MachineConnectionManager(Context context, StackAuthClient auth, Listener listener) {
        this.listener = listener;
        Context application = context.getApplicationContext();
        this.connector = (machine, events) -> {
            CmuxClient client = new CmuxClient(auth, events);
            if (machine.isIroh()) {
                client.connect(IrohWireConnection.connect(application, auth, machine.endpointId()));
            } else {
                client.connect(machine.host(), machine.port());
            }
            return client;
        };
    }

    MachineConnectionManager(Connector connector, Listener listener) {
        this.connector = connector;
        this.listener = listener;
    }

    synchronized JSONObject connect(MachineRegistry.Machine machine) throws Exception {
        if (closed) throw new IllegalStateException("Connection manager is closed");
        CmuxClient existing = clients.get(machine.id());
        if (existing != null && state(machine.id()) == State.CONNECTED) {
            return copy(snapshots.get(machine.id()));
        }
        setState(machine, State.CONNECTING, "Connecting…");
        final CmuxClient[] holder = new CmuxClient[1];
        CmuxClient next = null;
        try {
            next = connector.connect(machine, new CmuxClient.EventListener() {
                @Override public void onEvent(String topic, JSONObject payload) {
                    listener.onEvent(machine.id(), topic, payload);
                }

                @Override public void onDisconnect(String message) {
                    disconnected(machine, holder[0], message);
                }
            });
            holder[0] = next;
            if (existing != null) existing.close();
            JSONObject snapshot = next.listWorkspaces();
            clients.put(machine.id(), next);
            snapshots.put(machine.id(), snapshot);
            setState(machine, State.CONNECTED, "Connected");
            return copy(snapshot);
        } catch (Exception error) {
            if (next != null) next.close();
            clients.remove(machine.id());
            snapshots.remove(machine.id());
            setState(machine, State.ERROR, message(error));
            throw error;
        }
    }

    synchronized CmuxClient client(String machineId) {
        return clients.get(machineId);
    }

    synchronized JSONObject snapshot(String machineId) throws Exception {
        return copy(snapshots.get(machineId));
    }

    synchronized State state(String machineId) {
        Status status = states.get(machineId);
        return status == null ? State.DISCONNECTED : status.state();
    }

    synchronized List<Status> statuses(List<MachineRegistry.Machine> machines) {
        List<Status> result = new ArrayList<>();
        for (MachineRegistry.Machine machine : machines) {
            Status status = states.get(machine.id());
            result.add(status == null ? new Status(machine, State.DISCONNECTED, "Not connected") : status);
        }
        return result;
    }

    synchronized void disconnect(String machineId) {
        CmuxClient client = clients.remove(machineId);
        snapshots.remove(machineId);
        if (client != null) client.close();
        states.remove(machineId);
    }

    synchronized void disconnectAll() {
        for (CmuxClient client : clients.values()) client.close();
        clients.clear();
        snapshots.clear();
        states.clear();
    }

    private void disconnected(MachineRegistry.Machine machine, CmuxClient connection,
                              String reason) {
        synchronized (this) {
            if (clients.get(machine.id()) != connection) return;
            clients.remove(machine.id());
            snapshots.remove(machine.id());
            String message = reason == null || reason.isBlank() ? "Disconnected" : reason;
            setState(machine, State.ERROR, message);
            listener.onDisconnected(machine.id(), message);
        }
    }

    private void setState(MachineRegistry.Machine machine, State state, String message) {
        states.put(machine.id(), new Status(machine, state, message));
        listener.onStateChanged(machine.id(), state, message);
    }

    private static JSONObject copy(JSONObject value) throws Exception {
        return value == null ? null : new JSONObject(value.toString());
    }

    private static String message(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        disconnectAll();
    }
}
