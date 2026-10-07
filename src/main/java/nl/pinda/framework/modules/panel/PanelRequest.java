package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Eén verzoek aan de paneel-API, met handige helpers om de invoer te lezen. */
final class PanelRequest {

    private static final int MAX_BODY = 64 * 1024;

    private final HttpExchange exchange;
    private final Map<String, String> params;
    private final Map<String, String> query;
    private final String ip;
    private JsonObject body;
    PanelSessions.Session session;
    PanelUser user;

    PanelRequest(HttpExchange exchange, Map<String, String> params, String ip) {
        this.exchange = exchange;
        this.params = params;
        this.query = parseQuery(exchange.getRequestURI().getRawQuery());
        this.ip = ip;
    }

    HttpExchange exchange() {
        return exchange;
    }

    String ip() {
        return ip;
    }

    PanelUser user() {
        return user;
    }

    // ============================================================ pad en query

    /** Een stuk van het pad, zoals {uuid} in /api/players/{uuid}. */
    String param(String name) {
        return params.get(name);
    }

    UUID uuidParam(String name) throws ApiException {
        try {
            return UUID.fromString(params.get(name));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("Ongeldige speler-id.");
        }
    }

    String query(String name) {
        return query.get(name);
    }

    int queryInt(String name, int fallback, int min, int max) {
        String value = query.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    long queryLong(String name, long fallback) {
        String value = query.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ============================================================ body (JSON)

    JsonObject body() throws ApiException {
        if (body != null) {
            return body;
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw ApiException.badRequest("Het verzoek is te groot.");
            }
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                body = new JsonObject();
                return body;
            }
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw ApiException.badRequest("Ongeldig verzoek.");
            }
            body = element.getAsJsonObject();
            return body;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldig verzoek.");
        }
    }

    /** Een tekstveld uit de body, of null als het ontbreekt of leeg is. */
    String optString(String key) throws ApiException {
        JsonElement element = body().get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            String value = element.getAsString().trim();
            return value.isEmpty() ? null : value;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldige waarde voor '" + key + "'.");
        }
    }

    /** Een verplicht tekstveld uit de body. */
    String string(String key, String missingMessage) throws ApiException {
        String value = optString(key);
        if (value == null) {
            throw ApiException.badRequest(missingMessage);
        }
        return value;
    }

    // ============================================================ cookies

    String cookie(String name) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0 && part.substring(0, equals).trim().equals(name)) {
                    return part.substring(equals + 1).trim();
                }
            }
        }
        return null;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Kapotte invoer negeren
            }
        }
        return result;
    }
}
