package nl.pinda.framework.modules.panel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.afk.AfkModule;
import nl.pinda.framework.modules.economy.Account;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.economy.Money;
import nl.pinda.framework.modules.moderation.Durations;
import nl.pinda.framework.modules.moderation.ModerationModule;
import nl.pinda.framework.modules.moderation.ModerationService;
import nl.pinda.framework.modules.moderation.Punishment;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import nl.pinda.framework.modules.shop.Shop;
import nl.pinda.framework.modules.shop.ShopModule;
import nl.pinda.framework.modules.staff.StaffModule;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Spelers zoeken en bekijken, rangen geven, straffen en saldo's aanpassen. */
final class PlayersApi extends PanelApi {

    private static final long MAX_AMOUNT = 100_000_000_000L;

    PlayersApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/players", PanelUser.PLAYERS, this::search);
        server.get("/api/players/{uuid}", PanelUser.PLAYERS, this::profile);
        server.post("/api/players/{uuid}/rank", PanelUser.RANKS, this::setRank);
        server.post("/api/players/{uuid}/punish", PanelUser.MODERATE, this::punish);
        server.post("/api/players/{uuid}/revoke", PanelUser.MODERATE, this::revoke);
        server.post("/api/players/{uuid}/economy", PanelUser.ECONOMY_EDIT, this::economyEdit);
        server.get("/api/punishments", PanelUser.PLAYERS, this::punishments);
    }

    // ============================================================ zoeken

    private Object search(PanelRequest request) throws Exception {
        String query = request.query("q") == null ? "" : request.query("q").trim();
        int limit = request.queryInt("limit", 50, 1, 200);
        int offset = request.queryInt("offset", 0, 0, 1_000_000);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        Set<String> online = sync(() -> {
            Set<String> uuids = new HashSet<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                uuids.add(player.getUniqueId().toString());
            }
            return uuids;
        });
        RankModule ranks = ranks();
        record Row(String uuid, String name, long firstJoin, long lastSeen, String rank) {
        }
        record Page(List<Row> rows, long total) {
        }
        Page page = await(plugin.database().query(connection -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.uuid, p.name, p.first_join, p.last_seen, s.value AS rank
                    FROM pinda_players p
                    LEFT JOIN pinda_player_settings s ON s.uuid = p.uuid AND s.setting = 'rank'
                    WHERE p.name LIKE ? ESCAPE '\\'
                    ORDER BY p.last_seen DESC LIMIT ? OFFSET ?""")) {
                statement.setString(1, pattern);
                statement.setInt(2, limit);
                statement.setInt(3, offset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        rows.add(new Row(result.getString("uuid"), result.getString("name"),
                                result.getLong("first_join"), result.getLong("last_seen"), result.getString("rank")));
                    }
                }
            }
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM pinda_players WHERE name LIKE ? ESCAPE '\\'")) {
                statement.setString(1, pattern);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    total = result.getLong(1);
                }
            }
            return new Page(rows, total);
        }));

        List<Map<String, Object>> players = new ArrayList<>();
        for (Row row : page.rows()) {
            Rank rank = null;
            if (ranks != null) {
                rank = ranks.service().rank(row.rank());
                if (rank == null) {
                    rank = ranks.service().defaultRank();
                }
            }
            players.add(map("uuid", row.uuid(), "name", row.name(), "firstJoin", row.firstJoin(),
                    "lastSeen", row.lastSeen(), "online", online.contains(row.uuid()), "rank", rank(rank)));
        }
        return map("players", players, "total", page.total(), "offset", offset, "limit", limit);
    }

    // ============================================================ profiel

    private Object profile(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        UUID uuid = request.uuidParam("uuid");
        Map<String, Object> profile = await(plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, language, first_join, last_seen FROM pinda_players WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return map("uuid", uuid.toString(), "name", result.getString("name"),
                            "language", result.getString("language"), "firstJoin", result.getLong("first_join"),
                            "lastSeen", result.getLong("last_seen"));
                }
            }
        }));
        if (profile == null) {
            throw ApiException.notFound("Deze speler is nog nooit op de server geweest.");
        }
        boolean self = uuid.equals(user.uuid());

        RankModule ranks = ranks();
        if (ranks != null) {
            Rank rank = syncAwait(() -> ranks.service().rankOf(uuid));
            profile.put("rank", rank(rank));
            profile.put("canSetRank", user.has(PanelUser.RANKS) && !self && (user.operator() || rank.weight() < user.weight()));
        }

        profile.put("live", sync(() -> live(uuid)));

        EconomyService economy = economy();
        if (economy != null && user.has(PanelUser.ECONOMY_VIEW)) {
            Account account = await(economy.peek(uuid));
            long cash = account == null ? 0 : account.cash();
            long bank = account == null ? 0 : account.bank();
            profile.put("economy", map("cash", money(cash), "bank", money(bank), "total", money(cash + bank),
                    "log", EconomyApi.log(this, uuid, 25)));
        }

        ModerationModule moderation = moderation();
        if (moderation != null) {
            ModerationService service = moderation.service();
            List<Punishment> history = await(service.history(uuid, 50));
            List<Map<String, Object>> list = new ArrayList<>();
            Punishment ban = null;
            Punishment mute = null;
            for (Punishment punishment : history) {
                list.add(punishment(punishment));
                if (punishment.inEffect() && punishment.type() == Punishment.Type.BAN && ban == null) {
                    ban = punishment;
                }
                if (punishment.inEffect() && punishment.type() == Punishment.Type.MUTE && mute == null) {
                    mute = punishment;
                }
            }
            boolean canPunish = !self && user.has(PanelUser.MODERATE)
                    && Boolean.TRUE.equals(syncAwait(() -> service.canPunish(user.uuid(), uuid)));
            profile.put("moderation", map("history", list, "ban", ban == null ? null : punishment(ban),
                    "mute", mute == null ? null : punishment(mute), "canPunish", canPunish));
        }

        if (user.has(PanelUser.SKILLS)) {
            Map<String, Object> skills = SkillsApi.profile(this, uuid, user.has(PanelUser.SKILLS_EDIT));
            if (skills != null) {
                profile.put("skills", skills);
            }
        }

        ShopModule shops = shops();
        if (shops != null && user.has(PanelUser.SHOPS)) {
            profile.put("shop", sync(() -> {
                Shop shop = shops.service().shop(uuid);
                return shop == null ? null : ShopsApi.summary(this, shops.service(), shop);
            }));
        }
        return profile;
    }

    /** Live gegevens van een online speler, of null (hoofdthread). */
    private Map<String, Object> live(UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) {
            return null;
        }
        AfkModule afk = enabled(AfkModule.class);
        StaffModule staff = enabled(StaffModule.class);
        Location location = player.getLocation();
        return map(
                "world", location.getWorld().getName(),
                "x", location.getBlockX(), "y", location.getBlockY(), "z", location.getBlockZ(),
                "gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT),
                "health", DashboardApi.round(player.getHealth()),
                "food", player.getFoodLevel(),
                "level", player.getLevel(),
                "ping", player.getPing(),
                "flying", player.isFlying(),
                "afk", afk != null && afk.isAfk(player),
                "vanished", staff != null && staff.isVanished(player));
    }

    static Map<String, Object> punishment(Punishment punishment) {
        boolean lasting = punishment.type() == Punishment.Type.BAN || punishment.type() == Punishment.Type.MUTE;
        String status = !lasting ? "done"
                : punishment.inEffect() ? "active"
                : punishment.expired() ? "expired" : "lifted";
        return map("id", punishment.id(),
                "uuid", punishment.target().toString(),
                "name", punishment.targetName(),
                "type", punishment.type().name().toLowerCase(Locale.ROOT),
                "reason", punishment.reason(),
                "actor", punishment.actorName(),
                "created", punishment.created(),
                "expires", punishment.expires(),
                "status", status);
    }

    // ============================================================ rang

    private Object setRank(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        RankModule ranks = require(RankModule.class, "ranks");
        UUID uuid = request.uuidParam("uuid");
        Rank target = ranks.service().find(request.string("rank", "Kies een rang."));
        if (target == null) {
            throw ApiException.badRequest("Die rang bestaat niet.");
        }
        if (uuid.equals(user.uuid())) {
            throw ApiException.badRequest("Je kunt je eigen rang niet aanpassen in het paneel.");
        }
        KnownPlayer known = known(uuid);
        Rank current = syncAwait(() -> ranks.service().rankOf(uuid));
        if (!user.operator()) {
            if (current.weight() >= user.weight()) {
                throw ApiException.forbidden("Je kunt de rang van iemand met een gelijke of hogere rang niet aanpassen.");
            }
            if (target.weight() >= user.weight()) {
                throw ApiException.forbidden("Je kunt geen rang geven die gelijk aan of hoger is dan je eigen rang.");
            }
        }
        syncAwait(() -> ranks.service().setRank(uuid, target));
        sync(() -> {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                plugin.lang().send(online, "rank.received", Text.c("rank", ranks.service().prefix(target)),
                        Text.p("rank_name", target.displayName()));
                plugin.theme().play(online, "success");
            }
            return null;
        });
        module.log().add(request, "rang", known.name(), current.displayName() + " -> " + target.displayName());
        return map("rank", rank(target));
    }

    // ============================================================ straffen

    private Object punish(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        ModerationModule moderation = require(ModerationModule.class, "moderation");
        ModerationService service = moderation.service();
        UUID uuid = request.uuidParam("uuid");
        Punishment.Type type = switch (request.string("type", "Kies een straf.").toLowerCase(Locale.ROOT)) {
            case "ban" -> Punishment.Type.BAN;
            case "mute" -> Punishment.Type.MUTE;
            case "kick" -> Punishment.Type.KICK;
            case "warn" -> Punishment.Type.WARN;
            default -> throw ApiException.badRequest("Onbekende straf.");
        };
        if (uuid.equals(user.uuid())) {
            throw ApiException.badRequest("Je kunt jezelf niet straffen.");
        }
        KnownPlayer known = known(uuid);
        String reason = request.optString("reason");
        if (reason == null) {
            if (type == Punishment.Type.WARN) {
                throw ApiException.badRequest("Geef een reden op voor de waarschuwing.");
            }
            String fallback = plugin.lang().raw(plugin.lang().defaultLanguage(), "moderation.no-reason");
            reason = fallback == null ? "-" : fallback;
        }
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }

        Long duration = null;
        String durationText = request.optString("duration");
        String code = plugin.lang().defaultLanguage();
        if (type == Punishment.Type.BAN || type == Punishment.Type.MUTE) {
            if (durationText != null && !durationText.equalsIgnoreCase("permanent")) {
                long parsed = Durations.parse(durationText);
                if (parsed <= 0) {
                    throw ApiException.badRequest("Ongeldige duur. Gebruik bijvoorbeeld 30m, 2u, 1d of 1w.");
                }
                duration = parsed;
            }
            if (type == Punishment.Type.BAN && !user.has(ModerationModule.BAN_PERMANENT)) {
                long max = moderation.maxTempBan();
                if (duration == null) {
                    throw ApiException.forbidden("Je mag alleen tijdelijk bannen (maximaal "
                            + service.formatDuration(code, max) + ").");
                }
                if (duration > max) {
                    throw ApiException.forbidden("Dat is te lang. Je mag maximaal " + service.formatDuration(code, max) + " bannen.");
                }
            }
        }
        if (type == Punishment.Type.KICK && !Boolean.TRUE.equals(sync(() -> plugin.getServer().getPlayer(uuid) != null))) {
            throw ApiException.badRequest(known.name() + " is niet online.");
        }
        if (!Boolean.TRUE.equals(syncAwait(() -> service.canPunish(user.uuid(), uuid)))) {
            throw ApiException.forbidden("Je kunt " + known.name() + " niet straffen: die heeft een gelijke of hogere rang.");
        }

        Punishment punishment = await(service.punish(uuid, known.name(), type, reason, user.uuid(), user.name(), duration));
        sync(() -> {
            service.applyEffects(punishment);
            service.announce(null, punishment);
            return null;
        });
        module.log().add(request, type.name().toLowerCase(Locale.ROOT), known.name(),
                reason + (duration == null ? "" : " · " + service.formatDuration(code, duration)));
        return punishment(punishment);
    }

    private Object revoke(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        ModerationModule moderation = require(ModerationModule.class, "moderation");
        ModerationService service = moderation.service();
        UUID uuid = request.uuidParam("uuid");
        String typeText = request.string("type", "Kies wat je wilt opheffen.").toLowerCase(Locale.ROOT);
        if (!typeText.equals("ban") && !typeText.equals("mute")) {
            throw ApiException.badRequest("Alleen een ban of mute kan worden opgeheven.");
        }
        boolean ban = typeText.equals("ban");
        KnownPlayer known = known(uuid);
        Integer count = await(service.revoke(uuid, ban ? Punishment.Type.BAN : Punishment.Type.MUTE, user.name()));
        if (count == null || count == 0) {
            throw ApiException.badRequest(known.name() + (ban ? " is niet verbannen." : " is niet gemute."));
        }
        sync(() -> {
            service.notifyStaff(null, ban ? "moderation.notify-unban" : "moderation.notify-unmute",
                    Text.p("player", known.name()), Text.p("actor", user.name()));
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null && !ban) {
                plugin.lang().send(online, "moderation.unmuted-target");
            }
            return null;
        });
        module.log().add(request, ban ? "unban" : "unmute", known.name(), null);
        return null;
    }

    private Object punishments(PanelRequest request) throws Exception {
        ModerationModule moderation = require(ModerationModule.class, "moderation");
        int limit = request.queryInt("limit", 100, 1, 500);
        List<Map<String, Object>> active = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Punishment punishment : await(moderation.service().activeAll(500))) {
            active.add(punishment(punishment));
        }
        for (Punishment punishment : await(moderation.service().recent(limit))) {
            recent.add(punishment(punishment));
        }
        return map("active", active, "recent", recent);
    }

    // ============================================================ saldo

    private Object economyEdit(PanelRequest request) throws Exception {
        EconomyService economy = economy();
        if (economy == null) {
            throw new ApiException(409, "De module 'economy' staat uit.");
        }
        UUID uuid = request.uuidParam("uuid");
        String action = request.string("action", "Kies geven, afnemen of instellen.").toLowerCase(Locale.ROOT);
        if (!action.equals("give") && !action.equals("take") && !action.equals("set")) {
            throw ApiException.badRequest("Kies geven, afnemen of instellen.");
        }
        boolean bank = !"cash".equalsIgnoreCase(request.optString("account"));
        String amountText = request.string("amount", "Vul een bedrag in.");
        long cents = Money.parse(amountText);
        if (cents < 0 || (cents == 0 && !action.equals("set"))) {
            throw ApiException.badRequest("'" + amountText + "' is geen geldig bedrag.");
        }
        if (cents > MAX_AMOUNT) {
            throw ApiException.badRequest("Dat bedrag is te hoog.");
        }
        KnownPlayer known = known(uuid);
        String extra = request.optString("note");
        String note = "paneel: " + request.user().name() + (extra == null ? "" : " - " + truncate(extra, 100));
        syncAwait(() -> switch (action) {
            case "give" -> economy.give(uuid, cents, bank, "admin-give", note);
            case "take" -> economy.give(uuid, -cents, bank, "admin-take", note);
            default -> economy.set(uuid, cents, bank, note);
        });
        Account account = await(economy.peek(uuid));
        String label = switch (action) {
            case "give" -> "geld geven";
            case "take" -> "geld afnemen";
            default -> "saldo instellen";
        };
        module.log().add(request, label, known.name(), economy.format(cents) + (bank ? " (bank)" : " (contant)"));
        long cash = account == null ? 0 : account.cash();
        long bankBalance = account == null ? 0 : account.bank();
        return map("cash", money(cash), "bank", money(bankBalance), "total", money(cash + bankBalance));
    }

    // ============================================================ helpers

    /** De naam van een speler die ooit online is geweest, of een 404. */
    KnownPlayer known(UUID uuid) throws Exception {
        KnownPlayer known = await(plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM pinda_players WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new KnownPlayer(uuid, result.getString(1)) : null;
                }
            }
        }));
        if (known == null) {
            throw ApiException.notFound("Deze speler is nog nooit op de server geweest.");
        }
        return known;
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
