package nl.pinda.framework.modules.leaderboards;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.storage.Database;
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
     * zodat de toplijsten meteen kloppen. De bestanden worden op de achtergrond gelezen, zodat de
     * server niet hapert (ook niet met duizenden oude spelers).
     */
    void seed() {
        if (plugin.serverData().getLong(SEEDED, 0) > 0 || plugin.getServer().getWorlds().isEmpty()) {
            return;
        }
        File world = plugin.getServer().getWorlds().get(0).getWorldFolder();
        File root = plugin.getServer().getWorldContainer();
        Set<UUID> online = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        seeding = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Stats> batch = readAll(world, root, online);
            save(batch, false).thenRun(() -> {
                if (plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.serverData().set(SEEDED, System.currentTimeMillis());
                        if (!batch.isEmpty()) {
                            plugin.getLogger().info("Toplijsten: statistieken van " + batch.size() + " eerdere spelers ingelezen.");
                        }
                    });
                }
            });
        });
    }

    /** Leest world/stats/*.json (de statistieken van Minecraft zelf), met de namen uit usercache.json. */
    private Map<UUID, Stats> readAll(File world, File root, Set<UUID> skip) {
        Map<UUID, Stats> result = new LinkedHashMap<>();
        File folder = null;
        for (File candidate : new File[]{new File(world, "stats"), new File(new File(world, "players"), "stats")}) {
            if (candidate.isDirectory()) {
                folder = candidate;
                break;
            }
        }
        File[] files = folder == null ? null : folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return result;
        }
        Map<UUID, String> names = names(new File(root, "usercache.json"));
        for (File file : files) {
            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 5));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (skip.contains(uuid)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject stats = json.has("stats") ? json.getAsJsonObject("stats") : null;
                JsonObject custom = stats != null && stats.has("minecraft:custom") ? stats.getAsJsonObject("minecraft:custom") : null;
                if (custom == null) {
                    continue;
                }
                long ticks = value(custom, "minecraft:play_time");
                if (ticks == 0) {
                    ticks = value(custom, "minecraft:play_one_minute"); // oude naam
                }
                if (ticks <= 0) {
                    continue;
                }
                result.put(uuid, new Stats(names.get(uuid), ticks / 20L, value(custom, "minecraft:player_kills"),
                        value(custom, "minecraft:mob_kills"), value(custom, "minecraft:deaths")));
            } catch (IOException | RuntimeException e) {
                // Kapot of onleesbaar bestand: overslaan
            }
        }
        return result;
    }

    private static long value(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Map<UUID, String> names(File cache) {
        Map<UUID, String> names = new HashMap<>();
        if (!cache.isFile()) {
            return names;
        }
        try (Reader reader = Files.newBufferedReader(cache.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    JsonObject entry = element.getAsJsonObject();
                    try {
                        names.put(UUID.fromString(entry.get("uuid").getAsString()), entry.get("name").getAsString());
                    } catch (RuntimeException ignored) {
                        // regel overslaan
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // zonder namen gaat het ook
        }
        return names;
    }
}
