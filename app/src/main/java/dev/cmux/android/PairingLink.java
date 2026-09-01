package dev.cmux.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PairingLink {
    record Route(String host, int port) {}

    private PairingLink() {}

    static List<Route> parse(String raw) throws Exception {
        URI uri = URI.create(raw.trim());
        String scheme = uri.getScheme();
        if (!("cmux-ios".equals(scheme) || "cmux-ios-dev".equals(scheme))
            || !("attach".equals(uri.getHost()) || "pair".equals(uri.getHost()))) {
            throw new IllegalArgumentException("This is not a cmux pairing link.");
        }
        Map<String, List<String>> query = query(uri.getRawQuery());
        int version = integer(first(query, "v"), -1);
        if ("attach".equals(uri.getHost()) && version == 2) {
            List<Route> routes = new ArrayList<>();
            for (String value : query.getOrDefault("r", List.of())) routes.add(hostPort(value));
            if (routes.isEmpty() || routes.size() > 8) throw invalid();
            return routes;
        }
        if ("attach".equals(uri.getHost()) && version == 3) {
            throw new IllegalArgumentException("This is an Iroh pairing link.");
        }
        String payload = first(query, "payload");
        if (payload == null) throw invalid();
        JSONObject ticket = new JSONObject(new String(base64Url(payload), StandardCharsets.UTF_8));
        List<Route> routes = routes(ticket);
        if (routes.isEmpty() || routes.size() > 8) throw invalid();
        return routes;
    }

    static String parseIrohEndpointId(String raw) {
        URI uri = URI.create(raw.trim());
        Map<String, List<String>> values = query(uri.getRawQuery());
        String endpoint = first(values, "i");
        if (!"cmux-ios".equals(uri.getScheme()) || !"attach".equals(uri.getHost())
            || integer(first(values, "v"), -1) != 3 || values.size() != 2
            || values.get("v").size() != 1 || values.get("i").size() != 1
            || endpoint == null || !endpoint.matches("[0-9a-f]{64}")) throw invalid();
        return endpoint;
    }

    private static List<Route> routes(JSONObject ticket) throws Exception {
        List<Route> result = new ArrayList<>();
        JSONArray routes = ticket.optJSONArray(ticket.has("r") ? "r" : "routes");
        if (routes == null) {
            String host = ticket.optString("host", "");
            int port = ticket.optInt("port", -1);
            if (!host.isBlank()) result.add(checked(host, port));
            return result;
        }
        for (int i = 0; i < routes.length(); i++) {
            JSONObject route = routes.optJSONObject(i);
            if (route == null) continue;
            JSONObject endpoint = route.optJSONObject(route.has("e") ? "e" : "endpoint");
            if (endpoint == null) continue;
            String host = endpoint.optString(endpoint.has("h") ? "h" : "host", "");
            int port = endpoint.optInt(endpoint.has("p") ? "p" : "port", -1);
            if (!host.isBlank()) result.add(checked(host, port));
        }
        return result;
    }

    private static Route hostPort(String raw) throws Exception {
        String value = raw.trim();
        String host;
        String port;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 2 || end + 2 > value.length() || value.charAt(end + 1) != ':') throw invalid();
            host = value.substring(1, end);
            port = value.substring(end + 2);
        } else {
            int split = value.lastIndexOf(':');
            if (split < 1) throw invalid();
            host = value.substring(0, split);
            port = value.substring(split + 1);
        }
        return checked(host, integer(port, -1));
    }

    private static Route checked(String host, int port) throws Exception {
        String value = host.trim();
        if (value.isEmpty() || value.contains("/") || value.contains(" ") || port < 1 || port > 65535) throw invalid();
        InetAddress address = null;
        try { address = InetAddress.getByName(value); } catch (Exception ignored) {}
        String lower = value.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost")
            || address != null && (address.isLoopbackAddress() || address.isAnyLocalAddress())) {
            throw new IllegalArgumentException("Pairing links cannot point back to this phone.");
        }
        return new Route(value, port);
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String item : raw.split("&")) {
            int split = item.indexOf('=');
            String key = decode(split < 0 ? item : item.substring(0, split));
            String value = decode(split < 0 ? "" : item.substring(split + 1));
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid pairing link encoding.", error); }
    }

    private static byte[] base64Url(String value) {
        String padded = value + "=".repeat((4 - value.length() % 4) % 4);
        return Base64.getUrlDecoder().decode(padded);
    }

    private static String first(Map<String, List<String>> values, String key) {
        List<String> found = values.get(key);
        return found == null || found.isEmpty() ? null : found.get(0);
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("This cmux pairing link is invalid.");
    }
}
