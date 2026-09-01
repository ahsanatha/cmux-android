package dev.cmux.android;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

final class StackAuthClient {
    record Credentials(String accessToken, String refreshToken) {}
    private static final String API = "https://api.stack-auth.com/api/v1";
    private static final String PROJECT_ID = "9790718f-14cd-4f7e-824d-eaf527a82b82";
    private static final String PUBLISHABLE_KEY =
        "pck_kzj80gx4mh2jrzn1cx6y5e8jk0kwa01vkevh2p9zd4twr";
    private final SecureTokenStore tokens;

    StackAuthClient(SecureTokenStore tokens) {
        this.tokens = tokens;
    }

    String sendCode(String email) throws Exception {
        JSONObject body = new JSONObject()
            .put("email", email.trim())
            .put("callback_url", "https://cmux.com/auth/callback");
        return requestJson("/auth/otp/send-sign-in-code", "POST", body, null)
            .getString("nonce");
    }

    void verifyCode(String visibleCode, String nonce) throws Exception {
        JSONObject body = new JSONObject().put(
            "code",
            visibleCode.trim().toLowerCase(Locale.ROOT) + nonce
        );
        JSONObject response = requestJson("/auth/otp/sign-in", "POST", body, null);
        tokens.save(response.getString("access_token"), response.getString("refresh_token"));
    }

    synchronized String accessToken() throws Exception {
        String access = tokens.accessToken();
        if (access != null && jwtExpiresAt(access) > System.currentTimeMillis() / 1000L + 60L) {
            return access;
        }
        String refresh = tokens.refreshToken();
        if (refresh == null) throw new IllegalStateException("Sign in again");

        String form = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refresh)
            + "&client_id=" + encode(PROJECT_ID)
            + "&client_secret=" + encode(PUBLISHABLE_KEY);
        JSONObject response = requestForm("/auth/oauth/token", form);
        String renewed = response.getString("access_token");
        tokens.save(renewed, refresh);
        return renewed;
    }

    synchronized Credentials credentials() throws Exception {
        String access = accessToken();
        String refresh = tokens.refreshToken();
        if (refresh == null) throw new IllegalStateException("Sign in again");
        return new Credentials(access, refresh);
    }

    synchronized String accountFingerprint() throws Exception {
        return accountFingerprint(credentials().accessToken());
    }

    static String accountFingerprint(String accessToken) throws Exception {
        String[] parts = accessToken.split("\\.");
        if (parts.length != 3) throw new IllegalStateException("Invalid cmux session");
        JSONObject claims = new JSONObject(new String(
            Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        String subject = claims.optString("sub");
        if (subject.isBlank() || subject.length() > 1024
            || subject.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("cmux session has no account identity");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(subject.getBytes(StandardCharsets.UTF_8));
        StringBuilder fingerprint = new StringBuilder(digest.length * 2);
        for (byte value : digest) fingerprint.append(String.format("%02x", value & 0xff));
        return fingerprint.toString();
    }

    boolean hasSession() {
        return tokens.refreshToken() != null;
    }

    String refreshToken() {
        return tokens.refreshToken();
    }

    void signOut() {
        tokens.clear();
    }

    private JSONObject requestJson(
        String path,
        String method,
        JSONObject body,
        String accessToken
    ) throws Exception {
        HttpURLConnection connection = connection(path, method);
        connection.setRequestProperty("Content-Type", "application/json");
        if (accessToken != null) connection.setRequestProperty("x-stack-access-token", accessToken);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return response(connection);
    }

    private JSONObject requestForm(String path, String form) throws Exception {
        HttpURLConnection connection = connection(path, "POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(form.getBytes(StandardCharsets.UTF_8));
        }
        return response(connection);
    }

    private HttpURLConnection connection(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("x-stack-project-id", PROJECT_ID);
        connection.setRequestProperty("x-stack-publishable-client-key", PUBLISHABLE_KEY);
        connection.setRequestProperty("x-stack-access-type", "client");
        connection.setRequestProperty("x-stack-override-error-status", "true");
        connection.setRequestProperty("x-stack-client-version", "cmux-android@0.64.22");
        return connection;
    }

    private static JSONObject response(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            String actual = connection.getHeaderField("x-stack-actual-status");
            if (actual != null) status = Integer.parseInt(actual);
            InputStream input = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (input != null) try (input) {
                byte[] buffer = new byte[16_384];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (body.size() + count > 2 * 1024 * 1024) {
                        throw new IllegalStateException("Stack Auth response is too large");
                    }
                    body.write(buffer, 0, count);
                }
            }
            JSONObject json = body.size() == 0 ? new JSONObject()
                : new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            if (status < 200 || status >= 300) {
                String message = json.optString("message", json.optString("error", "HTTP " + status));
                throw new IllegalStateException(message);
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static long jwtExpiresAt(String token) {
        try {
            String[] parts = token.split("\\.");
            String payload = parts[1].replace('-', '+').replace('_', '/');
            while (payload.length() % 4 != 0) payload += "=";
            return new JSONObject(new String(
                Base64.getDecoder().decode(payload),
                StandardCharsets.UTF_8
            )).getLong("exp");
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
