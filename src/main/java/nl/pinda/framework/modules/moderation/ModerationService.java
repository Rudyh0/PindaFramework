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
import nl.pinda.framework.PindaFramework;
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
        if (!(actor instanceof Player player)) {
            return CompletableFuture.completedFuture(true);
        }
        RankModule ranks = plugin.modules().get(RankModule.class);
        if (ranks == null || !ranks.isEnabled()) {
            return CompletableFuture.completedFuture(true);
        }
        Rank own = ranks.service().rankOf(player);
        return ranks.service().rankOf(target).thenApply(theirs -> own.weight() > theirs.weight());
    }

    /** Slaat een straf op. Een nieuwe ban of mute vervangt de vorige. */
    public CompletableFuture<Punishment> punish(UUID target, String targetName, Punishment.Type type,
                                                String reason, CommandSender actor, Long durationMillis) {
        long now = System.currentTimeMillis();
        Long expires = durationMillis == null ? null : now + durationMillis;
        boolean active = type == Punishment.Type.BAN || type == Punishment.Type.MUTE;
        UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        String actorName = actor.getName();
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
        String actorName = actor.getName();
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
