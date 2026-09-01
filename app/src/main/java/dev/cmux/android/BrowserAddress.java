package dev.cmux.android;

import java.net.URI;
import java.net.URLEncoder;

final class BrowserAddress {
    private BrowserAddress() {}

    static String resolve(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return null;
        if (value.chars().anyMatch(Character::isWhitespace)) {
            try {
                return "https://www.google.com/search?q="
                    + URLEncoder.encode(value, "UTF-8").replace("+", "%20");
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new AssertionError(impossible);
            }
        }
        if (!value.contains("://")) value = "https://" + value;
        try {
            URI parsed = URI.create(value);
            String scheme = parsed.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && parsed.getHost() != null ? parsed.toASCIIString() : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
