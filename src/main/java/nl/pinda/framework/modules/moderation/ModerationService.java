package nl.pinda.framework.modules.moderation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.event.PindaNotifyEvent;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Straffen opslaan, opzoeken en opheffen. Houdt mutes van online spelers bij in het geheugen. */
public final class ModerationService {

    private final PindaFramework plugin;
    private final ModerationModule module;
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();

    public ModerationService(PindaFramework plugin, ModerationModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    // ============================================================ opvragen

    public CompletableFuture<Punishment> active(UUID target, Punishment.Type type) {
        return plugin.database().query(connection -> {
            Punishment found = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pinda_punishments WHERE uuid = ? AND type = ? AND active = 1 ORDER BY created DESC")) {
                statement.setString(1, target.toString());
                statement.setString(2, type.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Punishment punishment = read(rows);
                        if (punishment.expired()) {
                            deactivate(connection, punishment.id(), null);
                        } else if (found == null) {
                            found = punishment;
                        }
                    }
                }
            }
            return found;
        });
    }

    public CompletableFuture<List<Punishment>> history(UUID target, int limit) {
        return list("SELECT * FROM pinda_punishments WHERE uuid = ? ORDER BY created DESC LIMIT ?",
                target.toString(), limit);
    }

    public CompletableFuture<List<Punishment>> activeBans(int limit) {
        return plugin.database().query(connection -> {
            List<Punishment> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pinda_punishments WHERE type = 'BAN' AND active = 1 ORDER BY created DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Punishment punishment = read(rows);
                        if (!punishment.expired()) {
                            result.add(punishment);
                        }
                    }
                }
            }
            return result;
        });
    }

    /** Alle bans en mutes die nu gelden, nieuwste eerst. */
    public CompletableFuture<List<Punishment>> activeAll(int limit) {
        long now = System.currentTimeMillis();
        return plugin.database().query(connection -> {
            List<Punishment> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pinda_punishments WHERE active = 1 AND (expires IS NULL OR expires > ?) "
                            + "ORDER BY created DESC LIMIT ?")) {
                statement.setLong(1, now);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return result;
        });
    }

    /** De nieuwste straffen van iedereen. */
    public CompletableFuture<List<Punishment>> recent(int limit) {
        return plugin.database().query(connection -> {
            List<Punishment> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pinda_punishments ORDER BY created DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return result;
        });
    }

    private CompletableFuture<List<Punishment>> list(String sql, String uuid, int limit) {
        return plugin.database().query(connection -> {
            List<Punishment> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                }
            }
            return result;
        });
    }

    /** De mute van een online speler, of null. Verlopen mutes worden opgeruimd. */
    public Punishment mute(UUID player) {
        Punishment mute = mutes.get(player);
        if (mute != null && mute.expired()) {
            mutes.remove(player);
            final long id = mute.id();
            plugin.database().execute(connection -> deactivate(connection, id, null));
            return null;
        }
        return mute;
    }

    void cacheMute(UUID player, Punishment mute) {
        if (mute == null) {
            mutes.remove(player);
        } else {
            mutes.put(player, mute);
        }
    }

    void forgetMute(UUID player) {
        mutes.remove(player);
    }

    // ============================================================ straffen

    /**
     * Mag de afzender deze speler straffen? Staff kan geen gelijke of hogere rang straffen.
     * De console mag altijd. Wordt niet op de hoofdthread afgerond.
     */
    public CompletableFuture<Boolean> canPunish(CommandSender actor, UUID target) {
        return canPunish(actor instanceof Player player ? player.getUniqueId() : null, target);
    }

    /** Zelfde, op UUID (null = console). Gebruikt door het webpaneel. */
    public CompletableFuture<Boolean> canPunish(UUID actor, UUID target) {
        if (actor == null) {
            return CompletableFuture.completedFuture(true);
        }
        RankModule ranks = plugin.modules().get(RankModule.class);
        if (ranks == null || !ranks.isEnabled()) {
            return CompletableFuture.completedFuture(true);
        }
        return ranks.service().rankOf(actor).thenCombine(ranks.service().rankOf(target),
                (own, theirs) -> own.weight() > theirs.weight());
    }

    /** Slaat een straf op. Een nieuwe ban of mute vervangt de vorige. */
    public CompletableFuture<Punishment> punish(UUID target, String targetName, Punishment.Type type,
                                                String reason, CommandSender actor, Long durationMillis) {
        return punish(target, targetName, type, reason,
                actor instanceof Player player ? player.getUniqueId() : null, actor.getName(), durationMillis);
    }

    /** Zelfde, met de dader als UUID en naam (null = console). Gebruikt door het webpaneel. */
    public CompletableFuture<Punishment> punish(UUID target, String targetName, Punishment.Type type,
                                                String reason, UUID actorId, String actorName, Long durationMillis) {
        long now = System.currentTimeMillis();
        Long expires = durationMillis == null ? null : now + durationMillis;
        boolean active = type == Punishment.Type.BAN || type == Punishment.Type.MUTE;
        return plugin.database().query(connection -> {
            if (active) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE pinda_punishments SET active = 0, removed_by = ?, removed_at = ? "
                                + "WHERE uuid = ? AND type = ? AND active = 1")) {
                    statement.setString(1, actorName);
                    statement.setLong(2, now);
                    statement.setString(3, target.toString());
                    statement.setString(4, type.name());
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_punishments (uuid, name, type, reason, actor, actor_name, created, expires, active)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, target.toString());
                statement.setString(2, targetName);
                statement.setString(3, type.name());
                statement.setString(4, reason);
                statement.setString(5, actorId == null ? null : actorId.toString());
                statement.setString(6, actorName);
                statement.setLong(7, now);
                if (expires == null) {
                    statement.setNull(8, Types.INTEGER);
                } else {
                    statement.setLong(8, expires);
                }
                statement.setInt(9, active ? 1 : 0);
                statement.executeUpdate();
                long id = 0;
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                    }
                }
                return new Punishment(id, target, targetName, type, reason, actorId, actorName, now, expires, active);
            }
        });
    }

    /** Heft een actieve ban of mute op. Geeft het aantal opgeheven straffen. */
    public CompletableFuture<Integer> revoke(UUID target, Punishment.Type type, CommandSender actor) {
        return revoke(target, type, actor.getName());
    }

    public CompletableFuture<Integer> revoke(UUID target, Punishment.Type type, String actorName) {
        if (type == Punishment.Type.MUTE) {
            mutes.remove(target);
        }
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pinda_punishments SET active = 0, removed_by = ?, removed_at = ? "
                            + "WHERE uuid = ? AND type = ? AND active = 1")) {
                statement.setString(1, actorName);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, target.toString());
                statement.setString(4, type.name());
                return statement.executeUpdate();
            }
        });
    }

    private static void deactivate(Connection connection, long id, String by) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE pinda_punishments SET active = 0, removed_by = ?, removed_at = ? WHERE id = ?")) {
            statement.setString(1, by);
            statement.setLong(2, System.currentTimeMillis());
            statement.setLong(3, id);
            statement.executeUpdate();
        }
    }

    private static Punishment read(ResultSet rows) throws SQLException {
        String actor = rows.getString("actor");
        long expires = rows.getLong("expires");
        boolean noExpiry = rows.wasNull();
        return new Punishment(rows.getLong("id"), UUID.fromString(rows.getString("uuid")), rows.getString("name"),
                Punishment.Type.valueOf(rows.getString("type")), rows.getString("reason"),
                actor == null ? null : UUID.fromString(actor), rows.getString("actor_name"),
                rows.getLong("created"), noExpiry ? null : expires, rows.getInt("active") == 1);
    }

    // ============================================================ gevolgen en meldingen (hoofdthread)

    /** Voert een straf uit op een online speler: kicken, muten of waarschuwen. */
    public void applyEffects(Punishment punishment) {
        Player target = plugin.getServer().getPlayer(punishment.target());
        if (target == null) {
            return;
        }
        String code = plugin.lang().languageOf(target);
        switch (punishment.type()) {
            case BAN -> target.kick(banScreen(code, punishment));
            case KICK -> target.kick(plugin.lang().component(code, "moderation.kick-screen",
                    Text.p("reason", punishment.reason()), Text.p("actor", punishment.actorName())));
            case MUTE -> {
                cacheMute(target.getUniqueId(), punishment);
                plugin.lang().send(target, "moderation.muted-target", Text.p("reason", punishment.reason()),
                        Text.p("expires", expiryText(code, punishment)));
                plugin.theme().play(target, "error");
            }
            case WARN -> {
                plugin.lang().send(target, "moderation.warned-target", Text.p("reason", punishment.reason()),
                        Text.p("actor", punishment.actorName()));
                plugin.lang().sendTitle(target, "moderation.warned-title", "moderation.warned-subtitle",
                        Text.p("reason", punishment.reason()));
                plugin.theme().play(target, "error");
            }
        }
    }

    /** Meldt iets aan staff (of iedereen als broadcast aan staat) en in de console. */
    public void notifyStaff(Object exclude, String key, TagResolver... resolvers) {
        boolean everyone = module.cfg().getBoolean("broadcast", false);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == exclude) {
                continue;
            }
            if (everyone || online.hasPermission(ModerationModule.NOTIFY)) {
                plugin.lang().send(online, key, resolvers);
            }
        }
        if (exclude != plugin.getServer().getConsoleSender()) {
            plugin.getServer().getConsoleSender().sendMessage(
                    plugin.lang().component(plugin.lang().defaultLanguage(), key, resolvers));
        }
    }

    /** De standaardmelding aan staff na een nieuwe straf. */
    public void announce(Object exclude, Punishment punishment) {
        String type = punishment.type().name().toLowerCase(java.util.Locale.ROOT);
        String expires = expiryText(plugin.lang().defaultLanguage(), punishment);
        notifyStaff(exclude, "moderation.notify-" + type,
                Text.p("player", punishment.targetName()), Text.p("actor", punishment.actorName()),
                Text.p("reason", punishment.reason()), Text.p("expires", expires));
        PindaNotifyEvent.fire(plugin, "punishment", "player", punishment.targetName(), "actor", punishment.actorName(),
                "kind", type, "reason", punishment.reason(),
                "expires", punishment.type() == Punishment.Type.BAN || punishment.type() == Punishment.Type.MUTE ? expires : null);
    }

    /** De melding aan staff als een ban of mute is opgeheven. */
    public void announceRevoke(Object exclude, String player, Punishment.Type type, String actor) {
        boolean ban = type == Punishment.Type.BAN;
        notifyStaff(exclude, ban ? "moderation.notify-unban" : "moderation.notify-unmute",
                Text.p("player", player), Text.p("actor", actor));
        PindaNotifyEvent.fire(plugin, "revoke", "player", player, "actor", actor, "kind", ban ? "ban" : "mute");
    }

    // ============================================================ teksten

    /** "tot 12-10-2026 21:00 (nog 4d 3u)" of "permanent", in de taal van de lezer. */
    public String expiryText(String code, Punishment punishment) {
        if (punishment.permanent()) {
            String permanent = plugin.lang().raw(code, "moderation.permanent");
            return permanent == null ? "permanent" : permanent;
        }
        String pattern = plugin.lang().raw(code, "moderation.date-format");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern == null ? "dd-MM-yyyy HH:mm" : pattern)
                .withZone(ZoneId.systemDefault());
        String date = formatter.format(Instant.ofEpochMilli(punishment.expires()));
        long left = Math.max(0, punishment.expires() - System.currentTimeMillis());
        String template = plugin.lang().raw(code, "moderation.until");
        return (template == null ? "%date% (%left%)" : template)
                .replace("%date%", date)
                .replace("%left%", Durations.format(left, units(code)));
    }

    public String formatDuration(String code, long millis) {
        return Durations.format(millis, units(code));
    }

    public String formatDate(String code, long millis) {
        String pattern = plugin.lang().raw(code, "moderation.date-format");
        return DateTimeFormatter.ofPattern(pattern == null ? "dd-MM-yyyy HH:mm" : pattern)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
    }

    private String[] units(String code) {
        String raw = plugin.lang().raw(code, "moderation.units");
        String[] parts = raw == null ? new String[0] : raw.split(",");
        return parts.length == 4 ? parts : new String[]{"d", "u", "m", "s"};
    }

    /** Het scherm dat een verbannen speler ziet. */
    public Component banScreen(String code, Punishment ban) {
        return plugin.lang().component(code, "moderation.ban-screen",
                Text.p("reason", ban.reason()), Text.p("actor", ban.actorName()),
                Text.p("expires", expiryText(code, ban)));
    }
}
