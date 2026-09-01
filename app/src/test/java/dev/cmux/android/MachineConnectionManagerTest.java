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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class MachineConnectionManagerTest {
    @Test public void recordsConnectorFailureAsError() {
        AtomicReference<MachineConnectionManager.State> state = new AtomicReference<>();
        MachineConnectionManager manager = new MachineConnectionManager((machine, events) -> {
            throw new IllegalStateException("host unavailable");
        }, new MachineConnectionManager.Listener() {
            @Override public void onStateChanged(String machineId, MachineConnectionManager.State value,
                                                  String message) {
                state.set(value);
            }

            @Override public void onEvent(String machineId, String topic, JSONObject payload) {}
            @Override public void onDisconnected(String machineId, String message) {}
        });

        try {
            org.junit.Assert.assertThrows(Exception.class, () -> manager.connect(
                MachineRegistry.Machine.tcp("100.64.0.3", 58465, "Mac")));
            assertEquals(MachineConnectionManager.State.ERROR, state.get());
        } finally {
            manager.close();
        }
    }

    @Test public void keepsIndependentClientsAndReusesConnectedMachine() throws Exception {
        List<FakeHost> hosts = new ArrayList<>();
        List<String> connected = new CopyOnWriteArrayList<>();
        MachineConnectionManager manager = new MachineConnectionManager((machine, events) -> {
            FakeHost host = new FakeHost();
            hosts.add(host);
            host.start();
            CmuxClient client = new CmuxClient(() -> "token", events);
            client.connect("127.0.0.1", host.port());
            return client;
        }, new MachineConnectionManager.Listener() {
            @Override public void onStateChanged(String machineId, MachineConnectionManager.State state,
                                                  String message) {
                if (state == MachineConnectionManager.State.CONNECTED) connected.add(machineId);
            }

            @Override public void onEvent(String machineId, String topic, JSONObject payload) {}
            @Override public void onDisconnected(String machineId, String message) {}
        });
        MachineRegistry.Machine first = MachineRegistry.Machine.tcp("100.64.0.1", 58465, "Mac A");
        MachineRegistry.Machine second = MachineRegistry.Machine.tcp("100.64.0.2", 58465, "Mac B");

        try {
            JSONObject firstSnapshot = manager.connect(first);
            CmuxClient firstClient = manager.client(first.id());
            JSONObject secondSnapshot = manager.connect(second);
            CmuxClient secondClient = manager.client(second.id());
            JSONObject reusedSnapshot = manager.connect(first);

            assertNotSame(firstClient, secondClient);
            assertSame(firstClient, manager.client(first.id()));
            assertEquals(firstSnapshot.toString(), reusedSnapshot.toString());
            assertEquals(2, connected.size());
            assertEquals(0, secondSnapshot.optJSONArray("workspaces").length());
        } finally {
            manager.close();
            for (FakeHost host : hosts) host.close();
        }
    }

    private static final class FakeHost implements AutoCloseable {
        private final ServerSocket server;
        private final CountDownLatch keepAlive = new CountDownLatch(1);
        private Thread thread;

        FakeHost() throws Exception { server = new ServerSocket(0); }
        int port() { return server.getLocalPort(); }

        void start() {
            thread = new Thread(() -> {
                try (Socket socket = server.accept();
                     DataInputStream input = new DataInputStream(socket.getInputStream());
                     DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
                    JSONObject status = read(input);
                    reply(output, status, new JSONObject().put("capabilities", new JSONArray()));
                    JSONObject sync = read(input);
                    error(output, sync, "method_not_found", "Unsupported");
                    JSONObject list = read(input);
                    reply(output, list, new JSONObject().put("workspaces", new JSONArray()));
                    keepAlive.await();
                } catch (Exception ignored) {}
            }, "cmux-manager-test-host");
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void close() throws Exception {
            keepAlive.countDown();
            server.close();
            if (thread != null) thread.join(2_000);
        }

        private static JSONObject read(DataInputStream input) throws Exception {
            int size = input.readInt();
            FrameCodec.validateLength(size);
            byte[] data = new byte[size];
            input.readFully(data);
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        }

        private static void reply(DataOutputStream output, JSONObject request, JSONObject result)
            throws Exception {
            byte[] data = new JSONObject().put("id", request.getString("id"))
                .put("ok", true).put("result", result).toString()
                .getBytes(StandardCharsets.UTF_8);
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }

        private static void error(DataOutputStream output, JSONObject request, String code,
                                  String message) throws Exception {
            byte[] data = new JSONObject().put("id", request.getString("id"))
                .put("ok", false).put("error", new JSONObject()
                    .put("code", code).put("message", message)).toString()
                .getBytes(StandardCharsets.UTF_8);
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }
}
