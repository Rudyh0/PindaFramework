package nl.pinda.framework.modules.leaderboards;

import java.sql.SQLException;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

/**
 * Toplijsten: rijkste spelers, skills, speeltijd, kills, mobkills en doden. Te zien met /top,
 * op het scoreboard, in het paneel en via PlaceholderAPI.
 */
public final class LeaderboardsModule extends PindaModule {

    public static final String USE = "pinda.top.use";

    /** De databasetabellen van deze module (ook gebruikt bij het omzetten naar MySQL). */
    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_stats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        playtime INTEGER NOT NULL DEFAULT 0,
                        player_kills INTEGER NOT NULL DEFAULT 0,
                        mob_kills INTEGER NOT NULL DEFAULT 0,
                        deaths INTEGER NOT NULL DEFAULT 0,
                        updated INTEGER NOT NULL DEFAULT 0
                    )"""
            )
    );

    private StatsTracker tracker;
    private LeaderboardService service;
    private BukkitTask refreshTask;

    public LeaderboardsModule(PindaFramework plugin) {
        super(plugin, "leaderboards");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("leaderboards", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de statistieken-tabel niet aanmaken", e);
        }
        tracker = new StatsTracker(plugin);
        service = new LeaderboardService(plugin, this, tracker);
        listen(tracker);
        command(new TopCommand(plugin, this));
        tracker.seed();
        schedule();
    }

    @Override
    protected void onDisable() {
        if (tracker != null) {
            tracker.snapshotOnline();
            tracker.clear();
        }
    }

    @Override
    protected void onReload() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        schedule();
    }

    private void schedule() {
        long seconds = Math.max(15, config().getLong("refresh-seconds", 60));
        // Eerste keer snel (na de seed van de eerste spelers), daarna volgens de instelling
        refreshTask = repeat(() -> service.refresh(), 20L * 5, 20L * seconds);
    }

    /** De instellingen (modules/leaderboards.yml). */
    YamlConfiguration settings() {
        return config();
    }

    public LeaderboardService service() {
        return service;
    }

    /** Hoeveel plekken /top in de chat laat zien. */
    int chatPlaces() {
        return Math.max(1, Math.min(20, config().getInt("chat-places", 10)));
    }
}
