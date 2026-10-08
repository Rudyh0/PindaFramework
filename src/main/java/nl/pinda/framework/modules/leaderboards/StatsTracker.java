package nl.pinda.framework.modules.leaderboards;

import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.storage.Database;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Houdt speeltijd, kills en doden bij. Die komen uit de statistieken van Minecraft zelf
 * (zo tellen ook de jaren van voor de plugin mee) en worden in de database gezet, zodat
 * ook offline spelers op de toplijsten staan.
 */
public final class StatsTracker implements Listener {

    /** De statistieken van één speler. Speeltijd in seconden. */
    public record Stats(String name, long playtime, long kills, long mobKills, long deaths) {
    }

    private static final String SEEDED = "leaderboards.seeded";

    private final PindaFramework plugin;
    private final Map<UUID, Stats> cache = new ConcurrentHashMap<>();
    private BukkitTask seeding;

    StatsTracker(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** De laatst gemeten statistieken van een online speler (of null). Veilig vanaf elke thread. */
    public Stats cached(UUID uuid) {
        return cache.get(uuid);
    }

    static Stats read(Player player) {
        return new Stats(player.getName(),
                player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L,
                player.getStatistic(Statistic.PLAYER_KILLS),
                player.getStatistic(Statistic.MOB_KILLS),
                player.getStatistic(Statistic.DEATHS));
    }

    /** Meet alle online spelers en zet ze in de database (aanroepen op de hoofdthread). */
    CompletableFuture<Void> snapshotOnline() {
        Map<UUID, Stats> batch = new LinkedHashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Stats stats = read(player);
            cache.put(player.getUniqueId(), stats);
            batch.put(player.getUniqueId(), stats);
        }
        return save(batch, true);
    }

    private CompletableFuture<Void> save(Map<UUID, Stats> batch, boolean overwrite) {
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        String conflict = overwrite
                ? "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, playtime = excluded.playtime, "
                + "player_kills = excluded.player_kills, mob_kills = excluded.mob_kills, deaths = excluded.deaths, updated = excluded.updated"
                : "ON CONFLICT(uuid) DO NOTHING";
        return plugin.database().execute(connection -> Database.transaction(connection, tx -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_stats (uuid, name, playtime, player_kills, mob_kills, deaths, updated) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) " + conflict)) {
                for (Map.Entry<UUID, Stats> entry : batch.entrySet()) {
                    Stats stats = entry.getValue();
                    statement.setString(1, entry.getKey().toString());
                    statement.setString(2, stats.name());
                    statement.setLong(3, stats.playtime());
                    statement.setLong(4, stats.kills());
                    statement.setLong(5, stats.mobKills());
                    statement.setLong(6, stats.deaths());
                    statement.setLong(7, now);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon de statistieken niet opslaan", error);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Stats stats = read(player);
        cache.put(player.getUniqueId(), stats);
        save(Map.of(player.getUniqueId(), stats), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cache.remove(player.getUniqueId());
        save(Map.of(player.getUniqueId(), read(player)), true);
    }

    void clear() {
        cache.clear();
        if (seeding != null) {
            seeding.cancel();
            seeding = null;
        }
    }

    // ============================================================ eenmalig: bestaande spelers

    /**
     * De eerste keer: de statistieken van alle spelers die hier ooit speelden in de database zetten,
     * zodat de toplijsten meteen kloppen. In kleine stukjes, zodat de server niet hapert.
     */
    void seed() {
        if (plugin.serverData().getLong(SEEDED, 0) > 0) {
            return;
        }
        Deque<OfflinePlayer> queue = new ArrayDeque<>();
        for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
            if (player.getName() != null && !player.isOnline()) {
                queue.add(player);
            }
        }
        if (queue.isEmpty()) {
            plugin.serverData().set(SEEDED, System.currentTimeMillis());
            return;
        }
        plugin.getLogger().info("Toplijsten: statistieken van " + queue.size() + " spelers inlezen...");
        int total = queue.size();
        seeding = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Map<UUID, Stats> batch = new LinkedHashMap<>();
            for (int index = 0; index < 10 && !queue.isEmpty(); index++) {
                OfflinePlayer player = queue.poll();
                try {
                    Stats stats = new Stats(player.getName(),
                            player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L,
                            player.getStatistic(Statistic.PLAYER_KILLS),
                            player.getStatistic(Statistic.MOB_KILLS),
                            player.getStatistic(Statistic.DEATHS));
                    if (stats.playtime() > 0) {
                        batch.put(player.getUniqueId(), stats);
                    }
                } catch (RuntimeException ignored) {
                    // Geen (leesbaar) statistiekenbestand: overslaan
                }
            }
            save(batch, false);
            if (queue.isEmpty()) {
                seeding.cancel();
                seeding = null;
                plugin.serverData().set(SEEDED, System.currentTimeMillis());
                plugin.getLogger().info("Toplijsten: statistieken van " + total + " spelers ingelezen.");
            }
        }, 40L, 2L);
    }
}
