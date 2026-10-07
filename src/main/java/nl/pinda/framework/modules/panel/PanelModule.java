package nl.pinda.framework.modules.panel;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.ranks.RankModule;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.IllegalPluginAccessException;

/**
 * Het webpaneel: een eigen website om de server te beheren. Staff logt in met een eenmalige
 * link via /panel; wat je mag, hangt af van je rang.
 */
public final class PanelModule extends PindaModule {

    public static final String USE = PanelUser.USE;

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_panel_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        uuid TEXT,
                        name TEXT NOT NULL,
                        action TEXT NOT NULL,
                        target TEXT,
                        details TEXT,
                        ip TEXT
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_panel_log_time ON pinda_panel_log (time)"
            )
    );

    private final PanelSessions sessions = new PanelSessions();
    private final StatsHistory stats = new StatsHistory();
    private PanelLog log;
    private PanelServer server;
    private String runningBind;
    private int runningPort;

    public PanelModule(PindaFramework plugin) {
        super(plugin, "panel");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("panel", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de paneel-tabel niet aanmaken", e);
        }
        log = new PanelLog(plugin);
        command(new PanelCommand(plugin, this));
        startServer();
        repeat(this::sample, 20L, 20L * 60);
        repeat(() -> sessions.cleanup(idleMillis(), maxMillis()), 20L * 60, 20L * 60);
    }

    @Override
    protected void onDisable() {
        stopServer();
        sessions.clear();
        stats.clear();
    }

    @Override
    protected void onReload() {
        String bind = bind();
        int port = port();
        if (server != null && bind.equals(runningBind) && port == runningPort) {
            return;
        }
        // Even wachten, zodat een herlaadverzoek vanuit het paneel zelf nog een antwoord krijgt.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isEnabled()) {
                stopServer();
                startServer();
            }
        }, 20L);
    }

    // ============================================================ webserver

    private void startServer() {
        PanelServer created = new PanelServer(this);
        new AuthApi(this).register(created);
        new DashboardApi(this).register(created);
        new PlayersApi(this).register(created);
        new EconomyApi(this).register(created);
        new ShopsApi(this).register(created);
        new ServerApi(this).register(created);
        new ConsoleApi(this).register(created);
        new SkillsApi(this).register(created);
        String bind = bind();
        int port = port();
        try {
            created.start(bind, port);
            server = created;
            runningBind = bind;
            runningPort = port;
            plugin.getLogger().info("Webpaneel gestart: " + baseUrl());
        } catch (IOException | RuntimeException | LinkageError e) {
            // LinkageError: Java zonder de ingebouwde webserver (jdk.httpserver)
            created.stop();
            server = null;
            plugin.getLogger().log(Level.WARNING, "Kon het webpaneel niet starten op " + bind + ":" + port
                    + ". Is de poort al in gebruik? Pas hem aan in modules/panel.yml. (" + e.getMessage() + ")");
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** Draait de webserver? */
    public boolean running() {
        return server != null;
    }

    // ============================================================ instellingen

    PindaFramework plugin() {
        return plugin;
    }

    PanelSessions sessions() {
        return sessions;
    }

    PanelLog log() {
        return log;
    }

    StatsHistory stats() {
        return stats;
    }

    private String bind() {
        return config().getString("web.bind", "0.0.0.0").trim();
    }

    private int port() {
        return Math.max(1, Math.min(65535, config().getInt("web.port", 8085)));
    }

    long linkMillis() {
        return Math.max(1, config().getInt("login.link-minutes", 5)) * 60_000L;
    }

    long idleMillis() {
        return Math.max(5, config().getInt("login.idle-minutes", 60)) * 60_000L;
    }

    long maxMillis() {
        return Math.max(1, config().getInt("login.max-hours", 12)) * 3_600_000L;
    }

    int consoleLines() {
        return Math.max(50, Math.min(2000, config().getInt("console.lines", 400)));
    }

    /** Cookies alleen via https versturen als het paneel via https bereikbaar is. */
    boolean secureCookies() {
        return baseUrl().toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** De naam van de server zonder opmaak, voor de website. */
    String serverName() {
        String raw = plugin.mainConfig().getString("server-name", "PindaCraft");
        return PlainTextComponentSerializer.plainText().serialize(plugin.lang().parse(raw));
    }

    /** Het adres van het paneel, zoals in de inloglink. */
    public String baseUrl() {
        String configured = config().getString("public-url", "").trim();
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        if (!configured.isEmpty()) {
            return configured.contains("://") ? configured : "http://" + configured;
        }
        String bind = bind();
        String host = bind.isEmpty() || bind.equals("0.0.0.0") || bind.equals("::") ? detectAddress() : bind;
        return "http://" + host + ":" + port();
    }

    /** Het beste IP-adres van deze machine: een publiek IP (VPS) of anders het IP in het thuisnetwerk. */
    private String detectAddress() {
        String serverIp = plugin.getServer().getIp();
        if (serverIp != null && !serverIp.isBlank() && !serverIp.equals("0.0.0.0")) {
            return serverIp;
        }
        String best = null;
        int bestScore = 0;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                        || name.startsWith("virbr") || name.startsWith("vmnet") || name.startsWith("vboxnet")) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    int score = !address.isSiteLocalAddress() ? 4
                            : ip.startsWith("192.168.") ? 3
                            : ip.startsWith("10.") ? 2 : 1;
                    if (score > bestScore) {
                        best = ip;
                        bestScore = score;
                    }
                }
            }
        } catch (SocketException e) {
            plugin.getLogger().log(Level.FINE, "Kon netwerkkaarten niet uitlezen", e);
        }
        return best == null ? "localhost" : best;
    }

    /** Het IP van de bezoeker (achter een proxy: het IP dat de proxy doorgeeft). */
    String clientIp(HttpExchange exchange) {
        if (config().getBoolean("behind-proxy", false)) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String real = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    // ============================================================ gebruikers

    /** Bepaalt wat een speler in het paneel mag, op basis van zijn rang (ook als hij offline is). */
    CompletableFuture<PanelUser> loadUser(UUID uuid, String name) {
        RankModule ranks = plugin.modules().get(RankModule.class);
        if (ranks != null && ranks.isEnabled()) {
            return sync(() -> ranks.service().rankOf(uuid)).thenCompose(future -> future)
                    .thenCompose(rank -> sync(() -> new PanelUser(uuid, name, rank.id(), rank.displayName(),
                            rank.color().asHexString(), rank.weight(), rank.operator(),
                            rank.operator() ? Map.of() : Map.copyOf(ranks.service().resolve(rank)))));
        }
        return sync(() -> {
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
            Map<String, Boolean> permissions = new HashMap<>();
            Player online = offline.getPlayer();
            if (online != null) {
                for (PermissionAttachmentInfo info : online.getEffectivePermissions()) {
                    permissions.put(info.getPermission().toLowerCase(Locale.ROOT), info.getValue());
                }
            }
            return new PanelUser(uuid, name, null, null, null, offline.isOp() ? 1000 : 0, offline.isOp(), permissions);
        });
    }

    /** Een inloglink voor deze speler. */
    String createLink(Player player) {
        String token = sessions.createToken(player.getUniqueId(), player.getName(), linkMillis());
        return baseUrl() + "/#login=" + token;
    }

    // ============================================================ statistieken

    private void sample() {
        Runtime runtime = Runtime.getRuntime();
        stats.add(new StatsHistory.Sample(System.currentTimeMillis(), plugin.getServer().getOnlinePlayers().size(),
                Math.min(20.0, plugin.getServer().getTPS()[0]), runtime.totalMemory() - runtime.freeMemory()));
    }

    // ============================================================ threads

    /** Voert iets uit op de hoofdthread van de server (nodig voor alles met spelers en werelden). */
    <T> CompletableFuture<T> sync(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(task.call());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (IllegalPluginAccessException e) {
            future.completeExceptionally(new ApiException(503, "De server is aan het afsluiten."));
        }
        return future;
    }

    /** Wacht (op een webthread) op een resultaat, met een nette fout als het te lang duurt. */
    static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "De server reageert niet op tijd. Probeer het zo opnieuw.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Zoekt een ApiException in een keten van fouten. */
    static ApiException unwrapApi(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof ApiException api) {
                return api;
            }
            current = current.getCause();
        }
        return null;
    }
}
