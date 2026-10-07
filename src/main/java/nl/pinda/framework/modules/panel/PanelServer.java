package nl.pinda.framework.modules.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * De webserver van het paneel: levert de website (uit de jar) en de JSON-API onder /api/.
 * Draait op eigen threads, zodat de Minecraft-server er niets van merkt.
 */
final class PanelServer {

    /** Verwerkt één API-verzoek. Het resultaat wordt als JSON teruggestuurd (null = {"ok":true}). */
    @FunctionalInterface
    interface Handler {
        Object handle(PanelRequest request) throws Exception;
    }

    private record Route(String method, String[] parts, String permission, boolean auth, Handler handler) {
    }

    static final String COOKIE = "pinda_panel";
    private static final List<String> FILES = List.of("index.html", "app.js", "app.css", "favicon.svg");
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: https://mc-heads.net; connect-src 'self'; font-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final PanelModule module;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final List<Route> routes = new ArrayList<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    PanelServer(PanelModule module) {
        this.module = module;
    }

    // ============================================================ routes

    /** Een GET-route voor ingelogde gebruikers met deze permissie. */
    void get(String path, String permission, Handler handler) {
        routes.add(new Route("GET", split(path), permission, true, handler));
    }

    /** Een POST-route voor ingelogde gebruikers met deze permissie. */
    void post(String path, String permission, Handler handler) {
        routes.add(new Route("POST", split(path), permission, true, handler));
    }

    /** Een route waarvoor je niet ingelogd hoeft te zijn (alleen voor info en inloggen). */
    void open(String method, String path, Handler handler) {
        routes.add(new Route(method, split(path), null, false, handler));
    }

    // ============================================================ starten en stoppen

    void start(String bind, int port) throws IOException {
        loadFiles();
        AtomicInteger counter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "PindaFramework-Panel-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        InetSocketAddress address = bind == null || bind.isBlank()
                ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
        server = HttpServer.create(address, 32);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void loadFiles() throws IOException {
        files.clear();
        for (String name : FILES) {
            try (InputStream in = module.plugin().getResource("web/" + name)) {
                if (in == null) {
                    throw new IOException("Bestand web/" + name + " ontbreekt in de jar");
                }
                files.put(name, in.readAllBytes());
            }
        }
    }

    // ============================================================ verzoeken

    private void handle(HttpExchange exchange) {
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", CSP);
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (path.startsWith("/api/")) {
                api(exchange, path);
            } else {
                serveFile(exchange, path);
            }
        } catch (IOException ignored) {
            // De browser is al weg
        } catch (Exception e) {
            module.plugin().getLogger().log(Level.WARNING, "Fout in het webpaneel", e);
        } finally {
            exchange.close();
        }
    }

    private void api(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (method.equals("POST") && !"1".equals(exchange.getRequestHeaders().getFirst("X-Pinda"))) {
            sendError(exchange, 403, "Verzoek geweigerd.");
            return;
        }
        String[] parts = split(path);
        Route match = null;
        Map<String, String> params = null;
        boolean pathFound = false;
        for (Route route : routes) {
            Map<String, String> found = match(route.parts(), parts);
            if (found == null) {
                continue;
            }
            pathFound = true;
            if (route.method().equals(method)) {
                match = route;
                params = found;
                break;
            }
        }
        if (match == null) {
            sendError(exchange, pathFound ? 405 : 404, pathFound ? "Methode niet toegestaan." : "Onbekende API-route.");
            return;
        }

        PanelRequest request = new PanelRequest(exchange, params, module.clientIp(exchange));
        try {
            if (match.auth()) {
                authenticate(request, match.permission());
            }
            Object result = match.handler().handle(request);
            sendJson(exchange, 200, result == null ? Map.of("ok", true) : result);
        } catch (ApiException e) {
            sendError(exchange, e.status(), e.getMessage());
        } catch (Exception e) {
            ApiException api = PanelModule.unwrapApi(e);
            if (api != null) {
                sendError(exchange, api.status(), api.getMessage());
                return;
            }
            module.plugin().getLogger().log(Level.SEVERE, "Fout in paneel-API " + method + " " + path, e);
            sendError(exchange, 500, "Er ging iets mis op de server. Kijk in de console voor details.");
        }
    }

    private void authenticate(PanelRequest request, String permission) throws Exception {
        PanelSessions.Session session = module.sessions().get(request.cookie(COOKIE), module.idleMillis(), module.maxMillis());
        if (session == null) {
            throw new ApiException(401, "Je bent niet (meer) ingelogd. Typ /panel in-game voor een nieuwe link.");
        }
        PanelUser user = session.user;
        long now = System.currentTimeMillis();
        if (user == null || now - session.userLoaded > 10_000L) {
            user = PanelModule.await(module.loadUser(session.uuid, session.name));
            session.user = user;
            session.userLoaded = now;
        }
        if (!user.has(PanelUser.USE)) {
            module.sessions().remove(session.id);
            throw new ApiException(401, "Je hebt geen toegang meer tot het paneel.");
        }
        if (permission != null && !user.has(permission)) {
            throw ApiException.forbidden("Daar heb je geen rechten voor.");
        }
        request.session = session;
        request.user = user;
    }

    private void serveFile(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendError(exchange, 405, "Methode niet toegestaan.");
            return;
        }
        String name = path.equals("/") ? "index.html" : path.substring(1);
        byte[] bytes = files.get(name);
        if (bytes == null) {
            if (name.contains(".")) {
                sendError(exchange, 404, "Niet gevonden.");
                return;
            }
            name = "index.html";
            bytes = files.get(name);
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(name));
        headers.set("Cache-Control", "no-cache");
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ============================================================ antwoorden

    void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ============================================================ paden

    private static String[] split(String path) {
        return java.util.Arrays.stream(path.split("/")).filter(part -> !part.isEmpty()).toArray(String[]::new);
    }

    private static Map<String, String> match(String[] pattern, String[] parts) {
        if (pattern.length != parts.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int index = 0; index < pattern.length; index++) {
            String expected = pattern[index];
            if (expected.startsWith("{") && expected.endsWith("}")) {
                params.put(expected.substring(1, expected.length() - 1), parts[index]);
            } else if (!expected.equals(parts[index])) {
                return null;
            }
        }
        return params;
    }
}
