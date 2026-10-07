package nl.pinda.framework.modules.panel;

import java.lang.management.ManagementFactory;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.afk.AfkModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.ranks.RankModule;
import nl.pinda.framework.modules.shop.ShopModule;
import nl.pinda.framework.modules.shop.ShopService;
import nl.pinda.framework.modules.staff.StaffModule;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Het dashboard: hoe gaat het met de server, wie is er online, en de grafiek van de laatste 24 uur. */
final class DashboardApi extends PanelApi {

    DashboardApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/dashboard", PanelUser.USE, this::dashboard);
    }

    private Object dashboard(PanelRequest request) throws Exception {
        PanelUser user = request.user();
        Map<String, Object> result = sync(() -> {
            Runtime runtime = Runtime.getRuntime();
            double[] tps = plugin.getServer().getTPS();
            Map<String, Object> data = map(
                    "server", map(
                            "name", module.serverName(),
                            "software", plugin.getServer().getName(),
                            "version", plugin.getServer().getVersion(),
                            "minecraft", plugin.getServer().getMinecraftVersion(),
                            "plugin", plugin.version(),
                            "uptime", ManagementFactory.getRuntimeMXBean().getUptime(),
                            "tps", List.of(round(tps[0]), round(tps[1]), round(tps[2])),
                            "mspt", round(plugin.getServer().getAverageTickTime()),
                            "memoryUsed", runtime.totalMemory() - runtime.freeMemory(),
                            "memoryMax", runtime.maxMemory(),
                            "online", plugin.getServer().getOnlinePlayers().size(),
                            "maxPlayers", plugin.getServer().getMaxPlayers(),
                            "whitelist", plugin.getServer().hasWhitelist()),
                    "players", onlinePlayers(),
                    "worlds", worlds());
            if (user.has(PanelUser.SHOPS)) {
                ShopModule shops = shops();
                if (shops != null) {
                    ShopService service = shops.service();
                    data.put("shops", map("total", service.all().size(), "open", service.openShops().size()));
                }
            }
            return data;
        });

        List<Map<String, Object>> history = new ArrayList<>();
        for (StatsHistory.Sample sample : module.stats().all()) {
            history.add(map("time", sample.time(), "online", sample.online(), "tps", round(sample.tps()),
                    "memory", sample.memory()));
        }
        result.put("history", history);

        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long week = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        boolean showEconomy = user.has(PanelUser.ECONOMY_VIEW) && economy() != null;
        boolean showBans = moderation() != null;
        result.put("counts", await(plugin.database().query(connection -> {
            Map<String, Object> counts = map();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(first_join >= ?), 0), COALESCE(SUM(first_join >= ?), 0), "
                            + "COALESCE(SUM(last_seen >= ?), 0) FROM pinda_players")) {
                statement.setLong(1, today);
                statement.setLong(2, week);
                statement.setLong(3, today);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    counts.put("players", rows.getLong(1));
                    counts.put("newToday", rows.getLong(2));
                    counts.put("newWeek", rows.getLong(3));
                    counts.put("seenToday", rows.getLong(4));
                }
            }
            if (showEconomy) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(cash), 0), COALESCE(SUM(bank), 0) FROM pinda_economy");
                     ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    counts.put("accounts", rows.getLong(1));
                    counts.put("cash", rows.getLong(2));
                    counts.put("bank", rows.getLong(3));
                }
            }
            if (showBans) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM pinda_punishments WHERE type = 'BAN' AND active = 1 "
                                + "AND (expires IS NULL OR expires > ?)")) {
                    statement.setLong(1, System.currentTimeMillis());
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        counts.put("bans", rows.getLong(1));
                    }
                }
            }
            return counts;
        })));
        if (showEconomy) {
            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) result.get("counts");
            EconomyService economy = economy();
            counts.put("cashText", economy.format((Long) counts.get("cash")));
            counts.put("bankText", economy.format((Long) counts.get("bank")));
            counts.put("totalText", economy.format((Long) counts.get("cash") + (Long) counts.get("bank")));
        }
        return result;
    }

    /** Alle online spelers (hoofdthread). */
    List<Map<String, Object>> onlinePlayers() {
        RankModule ranks = ranks();
        AfkModule afk = enabled(AfkModule.class);
        StaffModule staff = enabled(StaffModule.class);
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            players.add(map(
                    "uuid", player.getUniqueId().toString(),
                    "name", player.getName(),
                    "rank", ranks == null ? null : rank(ranks.service().rankOf(player)),
                    "world", player.getWorld().getName(),
                    "gamemode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT),
                    "health", round(player.getHealth()),
                    "ping", player.getPing(),
                    "afk", afk != null && afk.isAfk(player),
                    "vanished", staff != null && staff.isVanished(player)));
        }
        players.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare((String) a.get("name"), (String) b.get("name")));
        return players;
    }

    /** Alle werelden (hoofdthread). */
    List<Map<String, Object>> worlds() {
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            worlds.add(map(
                    "name", world.getName(),
                    "environment", world.getEnvironment().name().toLowerCase(java.util.Locale.ROOT),
                    "players", world.getPlayerCount(),
                    "entities", world.getEntityCount(),
                    "chunks", world.getChunkCount(),
                    "time", world.getTime(),
                    "storm", world.hasStorm(),
                    "thundering", world.isThundering()));
        }
        return worlds;
    }

    static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
