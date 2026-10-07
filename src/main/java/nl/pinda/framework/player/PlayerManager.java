package nl.pinda.framework.player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Laadt spelersgegevens bij het inloggen, houdt ze in het geheugen zolang de speler online is
 * en slaat ze op bij wijzigingen en bij het uitloggen.
 */
public final class PlayerManager implements Listener {

    private static final long PRELOAD_TIMEOUT_MS = 60_000L;

    private final PindaFramework plugin;
    private final Map<UUID, PindaPlayer> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> preloaded = new ConcurrentHashMap<>();

    public PlayerManager(PindaFramework plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- opvragen

    /** De gegevens van een online speler, of null als ze niet geladen zijn. */
    public PindaPlayer get(UUID uuid) {
        return cache.get(uuid);
    }

    /** De gegevens van een online speler. Laadt ze direct als dat nog niet gebeurd is. */
    public PindaPlayer get(Player player) {
        PindaPlayer data = cache.get(player.getUniqueId());
        if (data == null) {
            data = loadBlocking(player.getUniqueId(), player.getName());
            cache.put(player.getUniqueId(), data);
        }
        return data;
    }

    // ------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            PindaPlayer data = load(uuid, event.getName()).get(10, TimeUnit.SECONDS);
            cache.put(uuid, data);
            preloaded.put(uuid, System.currentTimeMillis());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.SEVERE, "Kon de gegevens van " + event.getName() + " niet laden", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.lang().component(plugin.lang().defaultLanguage(), "general.data-load-failed"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        preloaded.remove(player.getUniqueId());
        PindaPlayer data = get(player);
        data.name(player.getName());
        data.lastSeen(System.currentTimeMillis());
        save(data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        preloaded.remove(uuid);
        PindaPlayer data = cache.remove(uuid);
        if (data != null) {
            data.lastSeen(System.currentTimeMillis());
            save(data);
        }
    }

    // ------------------------------------------------------- laden en opslaan

    /** Laadt de gegevens van een speler, of maakt nieuwe aan voor een nieuwe speler. */
    public CompletableFuture<PindaPlayer> load(UUID uuid, String name) {
        return plugin.database().query(connection -> {
            PindaPlayer data;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, language, setup_completed, first_join, last_seen FROM pinda_players WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        data = new PindaPlayer(uuid, result.getString("name"), result.getString("language"),
                                result.getInt("setup_completed") == 1, result.getLong("first_join"),
                                result.getLong("last_seen"), false);
                    } else {
                        long now = System.currentTimeMillis();
                        data = new PindaPlayer(uuid, name, null, false, now, now, false);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT setting, value FROM pinda_player_settings WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        data.rawSettings().put(result.getString("setting"), result.getString("value"));
                    }
                }
            }
            return data;
        });
    }

    /** Slaat de gegevens van een speler op de achtergrond op. */
    public CompletableFuture<Void> save(PindaPlayer data) {
        if (data.loadFailed()) {
            return CompletableFuture.completedFuture(null);
        }
        // Momentopname maken op de huidige thread, zodat de databasethread een vaste versie opslaat.
        final String uuid = data.uuid().toString();
        final String name = data.name();
        final String language = data.language();
        final boolean setupCompleted = data.setupCompleted();
        final long firstJoin = data.firstJoin();
        final long lastSeen = data.lastSeen();
        final Map<String, String> settings = data.settingsSnapshot();

        return plugin.database().execute(connection -> Database.transaction(connection, tx -> {
            try (PreparedStatement statement = tx.prepareStatement("""
                    INSERT INTO pinda_players (uuid, name, language, setup_completed, first_join, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        name = excluded.name,
                        language = excluded.language,
                        setup_completed = excluded.setup_completed,
                        last_seen = excluded.last_seen""")) {
                statement.setString(1, uuid);
                statement.setString(2, name);
                if (language == null) {
                    statement.setNull(3, Types.VARCHAR);
                } else {
                    statement.setString(3, language);
                }
                statement.setInt(4, setupCompleted ? 1 : 0);
                statement.setLong(5, firstJoin);
                statement.setLong(6, lastSeen);
                statement.executeUpdate();
            }
            if (!settings.isEmpty()) {
                try (PreparedStatement statement = tx.prepareStatement(
                        "INSERT INTO pinda_player_settings (uuid, setting, value) VALUES (?, ?, ?) "
                                + "ON CONFLICT(uuid, setting) DO UPDATE SET value = excluded.value")) {
                    for (Map.Entry<String, String> entry : settings.entrySet()) {
                        statement.setString(1, uuid);
                        statement.setString(2, entry.getKey());
                        statement.setString(3, entry.getValue());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        })).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Kon de gegevens van " + name + " niet opslaan", error);
            }
        });
    }

    /** Zet alle online spelers klaar in het geheugen, bijvoorbeeld na een herstart van de plugin. */
    public void loadOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            get(player);
        }
    }

    /** Slaat alle spelers in het geheugen op. */
    public void saveAll() {
        for (PindaPlayer data : cache.values()) {
            save(data);
        }
    }

    /**
     * Ruimt gegevens op van spelers die wel geladen zijn, maar nooit (of niet meer) online
     * kwamen, bijvoorbeeld omdat een andere plugin de login weigerde.
     */
    public void startCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, PindaPlayer>> iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                UUID uuid = iterator.next().getKey();
                if (plugin.getServer().getPlayer(uuid) != null) {
                    continue;
                }
                Long loadedAt = preloaded.get(uuid);
                if (loadedAt == null || now - loadedAt > PRELOAD_TIMEOUT_MS) {
                    iterator.remove();
                    preloaded.remove(uuid);
                }
            }
        }, 20L * 60, 20L * 60);
    }

    private PindaPlayer loadBlocking(UUID uuid, String name) {
        try {
            return load(uuid, name).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.SEVERE, "Kon de gegevens van " + name + " niet laden. "
                    + "Er worden tijdelijke gegevens gebruikt die niet worden opgeslagen.", e);
            long now = System.currentTimeMillis();
            return new PindaPlayer(uuid, name, null, true, now, now, true);
        }
    }
}
