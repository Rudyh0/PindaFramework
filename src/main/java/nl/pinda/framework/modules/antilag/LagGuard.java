package nl.pinda.framework.modules.antilag;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.event.PindaNotifyEvent;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Let op de TPS. Zakt die een tijdje onder de grens, dan krijgt staff een melding (ook in Discord)
 * met de drukste chunk, en worden losse items opgeruimd. Als de TPS weer goed is, komt er een
 * melding dat het voorbij is.
 */
final class LagGuard {

    public static final String NOTIFY = "pinda.antilag.notify";

    private final PindaFramework plugin;
    private final AntilagModule module;
    private int low;
    private int good;
    private boolean lagging;
    private long since;
    private long lastAlert;
    private boolean alerted;

    LagGuard(PindaFramework plugin, AntilagModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection section = module.settings().getConfigurationSection("lag-guard");
        return section != null ? section : module.settings().createSection("lag-guard");
    }

    boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    double threshold() {
        return Math.max(1, Math.min(19.5, cfg().getDouble("tps-below", 15.0)));
    }

    boolean lagging() {
        return lagging;
    }

    long since() {
        return since;
    }

    /** De TPS van de laatste ~5 seconden (uit de gemiddelde ticktijd), dus sneller dan het minuutgemiddelde. */
    double recentTps() {
        double mspt = plugin.getServer().getAverageTickTime();
        return mspt <= 50 ? 20.0 : 1000.0 / mspt;
    }

    /** Elke 5 seconden. */
    void check() {
        if (!enabled()) {
            lagging = false;
            low = 0;
            good = 0;
            return;
        }
        double tps = recentTps();
        double threshold = threshold();
        if (tps < threshold) {
            low++;
            good = 0;
        } else {
            good++;
            low = 0;
        }
        int seconds = Math.max(5, cfg().getInt("seconds", 15));
        if (!lagging && low * 5 >= seconds) {
            start(tps);
        } else if (lagging && tps >= Math.min(19.5, threshold + 1) && good * 5 >= 30) {
            recover(tps);
        }
    }

    private void start(double tps) {
        lagging = true;
        alerted = false;
        since = System.currentTimeMillis();
        long cooldown = Math.max(1, cfg().getLong("cooldown-minutes", 5)) * 60_000L;
        if (System.currentTimeMillis() - lastAlert < cooldown) {
            return;
        }
        lastAlert = System.currentTimeMillis();
        alerted = true;
        double mspt = plugin.getServer().getAverageTickTime();
        List<AntilagModule.ChunkLoad> busiest = module.busiest(1);
        AntilagModule.ChunkLoad chunk = busiest.isEmpty() ? null : busiest.get(0);

        boolean clear = cfg().getBoolean("clear-items", true);
        int countdown = Math.max(3, cfg().getInt("clear-countdown", 10));
        if (clear) {
            clear = module.cleaner().start(countdown);
        }

        if (cfg().getBoolean("notify-staff", true)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission(NOTIFY)) {
                    alert(player, tps, mspt, chunk, clear, countdown);
                }
            }
            alert(plugin.getServer().getConsoleSender(), tps, mspt, chunk, clear, countdown);
        }
        PindaNotifyEvent.fire(plugin, "lag",
                "tps", module.number(tps), "mspt", module.number(mspt),
                "chunk", chunk == null ? null : chunk.world() + " " + chunk.blockX() + ", " + chunk.blockZ() + " (" + chunk.entities() + ")",
                "online", String.valueOf(plugin.getServer().getOnlinePlayers().size()),
                "cleanup", clear ? String.valueOf(countdown) : null);
    }

    private void alert(CommandSender sender, double tps, double mspt, AntilagModule.ChunkLoad chunk, boolean clear, int countdown) {
        plugin.lang().send(sender, "antilag.lag-alert", Text.c("tps", module.tpsComponent(tps)), Text.p("mspt", module.number(mspt)));
        if (chunk != null) {
            plugin.lang().send(sender, "antilag.lag-chunk", Text.c("location", module.location(sender, chunk)),
                    Text.p("count", chunk.entities()), Text.p("types", chunk.typesText()));
        }
        if (clear) {
            plugin.lang().send(sender, "antilag.lag-cleanup", Text.p("seconds", countdown));
        }
    }

    private void recover(double tps) {
        lagging = false;
        if (!alerted) {
            return; // binnen de cooldown: er was ook geen melding dat het begon
        }
        long duration = System.currentTimeMillis() - since;
        if (cfg().getBoolean("notify-staff", true)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission(NOTIFY)) {
                    plugin.lang().send(player, "antilag.lag-recovered", Text.c("tps", module.tpsComponent(tps)));
                }
            }
            plugin.lang().send(plugin.getServer().getConsoleSender(), "antilag.lag-recovered", Text.c("tps", module.tpsComponent(tps)));
        }
        PindaNotifyEvent.fire(plugin, "lag-recovered", "tps", module.number(tps),
                "duration", String.valueOf(Math.max(1, duration / 60_000L)));
    }
}
