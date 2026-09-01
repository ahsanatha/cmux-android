package dev.cmux.android;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stores Mac connection metadata. Credentials remain in the existing secure stores. */
final class MachineRegistry {
    private static final String KEY = "machines.v1";
    private static final int MAX_MACHINES = 32;
    private final SharedPreferences preferences;

    enum Kind { IROH, TCP }

    record Machine(String id, Kind kind, String displayName, String host, int port,
                   String endpointId) {
        Machine {
            if (id == null || id.isBlank() || id.length() > 256 || hasControl(id)) {
                throw new IllegalArgumentException("Invalid machine id");
            }
            if (kind == null) throw new IllegalArgumentException("Invalid machine kind");
            if (displayName == null || displayName.isBlank() || displayName.length() > 128
                || hasControl(displayName)) {
                throw new IllegalArgumentException("Invalid machine name");
            }
            if (kind == Kind.IROH) {
                if (endpointId == null || !endpointId.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid Iroh endpoint");
                }
                if (host != null || port != 0) throw new IllegalArgumentException("Invalid Iroh route");
            } else {
                if (endpointId != null || !validHost(host) || port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Invalid TCP route");
                }
            }
        }

        static Machine iroh(String endpointId, String displayName) {
            String endpoint = endpointId == null ? "" : endpointId.toLowerCase(Locale.ROOT);
            return new Machine("iroh:" + endpoint, Kind.IROH,
                cleanName(displayName, "Mac"), null, 0, endpoint);
        }

        static Machine tcp(String host, int port, String displayName) {
            String routeHost = host == null ? "" : host.trim();
            return new Machine("tcp:" + routeHost + ":" + port, Kind.TCP,
                cleanName(displayName, routeHost), routeHost, port, null);
        }

        Machine named(String name) {
            return new Machine(id, kind, cleanName(name, displayName), host, port, endpointId);
        }

        boolean isIroh() { return kind == Kind.IROH; }

        private static String cleanName(String value, String fallback) {
            String result = value == null ? "" : value.trim();
            if (result.isEmpty()) result = fallback == null ? "Mac" : fallback;
            if (result.length() > 128) result = result.substring(0, 128);
            if (hasControl(result)) throw new IllegalArgumentException("Invalid machine name");
            return result;
        }

        private static boolean validHost(String value) {
            if (value == null || value.isBlank() || value.length() > 255
                || hasControl(value) || value.contains("/") || value.chars().anyMatch(Character::isWhitespace)) {
                return false;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost")) return false;
            if (value.matches("[0-9.]+") || value.contains(":")) {
                try {
                    InetAddress address = InetAddress.getByName(value);
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return false;
                } catch (Exception ignored) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hasControl(String value) {
            return value.chars().anyMatch(Character::isISOControl);
        }
    }

    MachineRegistry(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized List<Machine> list() {
        return decode(preferences.getString(KEY, "[]"));
    }

    synchronized void upsert(Machine machine) {
        Map<String, Machine> machines = byId(list());
        machines.put(machine.id(), machine);
        while (machines.size() > MAX_MACHINES) {
            machines.remove(machines.keySet().iterator().next());
        }
        save(new ArrayList<>(machines.values()));
    }

    synchronized void remove(String id) {
        Map<String, Machine> machines = byId(list());
        if (machines.remove(id) != null) save(new ArrayList<>(machines.values()));
    }

    synchronized void clear() {
        preferences.edit().remove(KEY).apply();
    }

    private void save(List<Machine> machines) {
        if (!preferences.edit().putString(KEY, encode(machines)).commit()) {
            throw new IllegalStateException("Could not save machine list");
        }
    }

    private static Map<String, Machine> byId(List<Machine> machines) {
        Map<String, Machine> result = new LinkedHashMap<>();
        for (Machine machine : machines) result.put(machine.id(), machine);
        return result;
    }

    static String encode(List<Machine> machines) {
        try {
            JSONArray result = new JSONArray();
            int count = 0;
            for (Machine machine : machines) {
                if (count++ >= MAX_MACHINES) break;
                JSONObject value = new JSONObject().put("id", machine.id())
                    .put("kind", machine.kind() == Kind.IROH ? "iroh" : "tcp")
                    .put("name", machine.displayName());
                if (machine.isIroh()) value.put("endpoint_id", machine.endpointId());
                else value.put("host", machine.host()).put("port", machine.port());
                result.put(value);
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode machine list", error);
        }
    }

    static List<Machine> decode(String encoded) {
        List<Machine> result = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            for (int i = 0; i < values.length() && result.size() < MAX_MACHINES; i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                try {
                    String kind = value.getString("kind");
                    Machine machine;
                    if ("iroh".equals(kind)) {
                        machine = Machine.iroh(value.getString("endpoint_id"), value.getString("name"));
                    } else if ("tcp".equals(kind)) {
                        machine = Machine.tcp(value.getString("host"), value.getInt("port"),
                            value.getString("name"));
                    } else {
                        continue;
                    }
                    if (machine.id().equals(value.getString("id"))
                        && !result.stream().anyMatch(existing -> existing.id().equals(machine.id()))) {
                        result.add(machine);
                    }
                } catch (Exception ignored) {
                    // Ignore one corrupt entry without losing all saved machines.
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
}
