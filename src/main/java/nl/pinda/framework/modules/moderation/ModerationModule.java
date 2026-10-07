package nl.pinda.framework.modules.moderation;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Moderatie: kick, ban, tempban, mute, warn, met geschiedenis per speler. */
public final class ModerationModule extends PindaModule implements Listener {

    public static final String KICK = "pinda.mod.kick";
    public static final String BAN = "pinda.mod.ban";
    public static final String BAN_PERMANENT = "pinda.mod.ban.permanent";
    public static final String UNBAN = "pinda.mod.unban";
    public static final String MUTE = "pinda.mod.mute";
    public static final String WARN = "pinda.mod.warn";
    public static final String HISTORY = "pinda.mod.history";
    public static final String NOTIFY = "pinda.mod.notify";

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_punishments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        actor TEXT,
                        actor_name TEXT NOT NULL,
                        created INTEGER NOT NULL,
                        expires INTEGER,
                        active INTEGER NOT NULL,
                        removed_by TEXT,
                        removed_at INTEGER
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_punishments_uuid ON pinda_punishments (uuid, type, active)"
            )
    );

    private ModerationService service;

    public ModerationModule(PindaFramework plugin) {
        super(plugin, "moderation");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("moderation", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de moderatie-tabel niet aanmaken", e);
        }
        service = new ModerationService(plugin, this);
        listen(this);
        ModerationActions actions = new ModerationActions(plugin, this, service);
        command(new KickCommand(plugin, actions));
        command(new BanCommand(plugin, actions, false));
        command(new BanCommand(plugin, actions, true));
        command(new RevokeCommand(plugin, actions, Punishment.Type.BAN));
        command(new MuteCommand(plugin, actions));
        command(new RevokeCommand(plugin, actions, Punishment.Type.MUTE));
        command(new WarnCommand(plugin, actions));
        command(new HistoryCommand(plugin, service));
        command(new BanlistCommand(plugin, service));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            service.active(player.getUniqueId(), Punishment.Type.MUTE)
                    .thenAccept(mute -> service.cacheMute(player.getUniqueId(), mute));
        }
    }

    YamlConfiguration cfg() {
        return config();
    }

    public ModerationService service() {
        return service;
    }

    /** Hoogste duur voor een tempban door staff zonder pinda.mod.ban.permanent (ms). */
    public long maxTempBan() {
        long parsed = Durations.parse(config().getString("max-temp-ban", "7d"));
        return parsed > 0 ? parsed : 7L * 24 * 60 * 60 * 1000;
    }

    // ----------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            Punishment ban = service.active(event.getUniqueId(), Punishment.Type.BAN).get(5, TimeUnit.SECONDS);
            if (ban != null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        service.banScreen(plugin.lang().defaultLanguage(), ban));
                return;
            }
            service.cacheMute(event.getUniqueId(),
                    service.active(event.getUniqueId(), Punishment.Type.MUTE).get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.SEVERE, "Kon bans van " + event.getName() + " niet controleren", e);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Punishment mute = service.mute(player.getUniqueId());
        if (mute == null) {
            return;
        }
        event.setCancelled(true);
        String code = plugin.lang().languageOf(player);
        plugin.lang().send(player, "moderation.muted-chat", Text.p("reason", mute.reason()),
                Text.p("expires", service.expiryText(code, mute)));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Punishment mute = service.mute(player.getUniqueId());
        if (mute == null) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (config().getStringList("muted-blocked-commands").contains(label)) {
            event.setCancelled(true);
            String code = plugin.lang().languageOf(player);
            plugin.lang().send(player, "moderation.muted-chat", Text.p("reason", mute.reason()),
                    Text.p("expires", service.expiryText(code, mute)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forgetMute(event.getPlayer().getUniqueId());
    }
}
