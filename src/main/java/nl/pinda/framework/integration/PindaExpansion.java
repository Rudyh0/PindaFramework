package nl.pinda.framework.integration;

import java.util.Locale;
import java.util.Map;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.afk.AfkModule;
import nl.pinda.framework.modules.economy.Account;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.leaderboards.Board;
import nl.pinda.framework.modules.leaderboards.LeaderboardService;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;
import nl.pinda.framework.modules.leaderboards.StatsTracker;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import nl.pinda.framework.modules.skills.Skill;
import nl.pinda.framework.modules.skills.SkillService;
import nl.pinda.framework.modules.skills.SkillsModule;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * De placeholders van PindaFramework voor andere plugins (TAB, hologrammen, ...):
 * %pinda_rank%, %pinda_money%, %pinda_top_money_1_name%, ... Zie de README voor de hele lijst.
 */
final class PindaExpansion extends PlaceholderExpansion {

    /** Zo begrijpen Bukkit-plugins hex-kleuren (§x§F§F§C§8§5§7). */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final PindaFramework plugin;

    PindaExpansion(PindaFramework plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pinda";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Rudyh0";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.version();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            // null = onbekende placeholder: dan laat PlaceholderAPI de tekst staan
            return resolve(player, params.toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String resolve(OfflinePlayer offline, String params) {
        String code = plugin.lang().defaultLanguage();
        Player online = offline == null ? null : offline.getPlayer();
        if (online != null) {
            code = plugin.lang().languageOf(online);
        }

        // Zonder speler
        switch (params) {
            case "tps" -> {
                return LeaderboardService.number(Math.round(Math.min(20.0, plugin.getServer().getTPS()[0]) * 10) / 10.0, code);
            }
            case "online" -> {
                return String.valueOf(plugin.getServer().getOnlinePlayers().size());
            }
            default -> {
                // verder hieronder
            }
        }
        if (params.startsWith("top_")) {
            return top(params.substring(4), code);
        }
        if (offline == null) {
            return "";
        }

        // Toplijsten van deze speler: position_<lijst>, value_<lijst>, value_<lijst>_raw
        if (params.startsWith("position_") || params.startsWith("value_")) {
            boolean position = params.startsWith("position_");
            String rest = params.substring(position ? 9 : 6);
            boolean raw = rest.endsWith("_raw");
            Board board = Board.find(raw ? rest.substring(0, rest.length() - 4) : rest);
            LeaderboardService service = leaderboards();
            if (board == null || service == null) {
                return "";
            }
            LeaderboardService.Ranking ranking = service.ranking(board);
            if (position) {
                int found = ranking.position(offline.getUniqueId());
                return found == 0 ? "-" : String.valueOf(found);
            }
            LeaderboardService.Entry entry = ranking.entry(offline.getUniqueId());
            long value = entry == null ? 0 : entry.value();
            return raw ? String.valueOf(value) : service.format(board, value, code);
        }

        switch (params) {
            case "rank", "rank_name" -> {
                Rank rank = rank(online);
                return rank == null ? "" : rank.displayName();
            }
            case "rank_prefix" -> {
                Rank rank = rank(online);
                RankModule ranks = enabled(RankModule.class);
                return rank == null || ranks == null ? "" : LEGACY.serialize(ranks.service().prefix(rank));
            }
            case "rank_color" -> {
                Rank rank = rank(online);
                return rank == null ? "" : rank.color().asHexString();
            }
            case "rank_weight" -> {
                Rank rank = rank(online);
                return rank == null ? "" : String.valueOf(rank.weight());
            }
            case "money", "cash", "bank", "money_raw", "cash_raw", "bank_raw" -> {
                return money(offline, params);
            }
            case "skills_total" -> {
                SkillService skills = skills();
                Map<Skill, Double> xp = skills == null ? null : skills.cachedXp(offline.getUniqueId());
                if (xp != null) {
                    return String.valueOf(skills.totalLevel(xp));
                }
                // Offline: uit de toplijst (laadt niets uit de database)
                LeaderboardService service = leaderboards();
                LeaderboardService.Entry entry = service == null ? null : service.ranking(Board.SKILLS).entry(offline.getUniqueId());
                return entry == null ? "0" : String.valueOf(entry.value());
            }
            case "playtime", "playtime_raw", "kills", "mobkills", "deaths" -> {
                return stat(offline, params, code);
            }
            case "afk" -> {
                AfkModule afk = enabled(AfkModule.class);
                return String.valueOf(online != null && afk != null && afk.isAfk(online));
            }
            case "name_colored" -> {
                if (online == null) {
                    return offline.getName();
                }
                Component name = plugin.display().name(online);
                return LEGACY.serialize(name);
            }
            default -> {
                // skill_<id>: het level in een skill
                if (params.startsWith("skill_")) {
                    SkillService skills = skills();
                    Skill skill = skills == null ? null : skills.find(params.substring(6));
                    Map<Skill, Double> xp = skill == null ? null : skills.cachedXp(offline.getUniqueId());
                    return xp == null ? "" : String.valueOf(skills.curve().levelOf(xp.getOrDefault(skill, 0.0)));
                }
                return null;
            }
        }
    }

    /** top_&lt;lijst&gt;_&lt;plek&gt;_name|value|raw */
    private String top(String rest, String code) {
        String[] parts = rest.split("_");
        if (parts.length != 3) {
            return null;
        }
        Board board = Board.find(parts[0]);
        LeaderboardService service = leaderboards();
        if (board == null || service == null) {
            return "";
        }
        int place;
        try {
            place = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        LeaderboardService.Ranking ranking = service.ranking(board);
        if (place < 1 || place > ranking.entries().size()) {
            return parts[2].equals("name") ? "-" : "";
        }
        LeaderboardService.Entry entry = ranking.entries().get(place - 1);
        return switch (parts[2]) {
            case "name" -> entry.name();
            case "value" -> service.format(board, entry.value(), code);
            case "raw" -> String.valueOf(entry.value());
            default -> null;
        };
    }

    private String money(OfflinePlayer player, String params) {
        EconomyModule module = enabled(EconomyModule.class);
        if (module == null) {
            return "";
        }
        EconomyService economy = module.service();
        Account account = economy.cached(player.getUniqueId());
        long cents;
        if (account != null) {
            cents = params.startsWith("cash") ? account.cash() : params.startsWith("bank") ? account.bank() : account.total();
        } else if (params.startsWith("money")) {
            // Offline: het totaal van de toplijst
            LeaderboardService service = leaderboards();
            LeaderboardService.Entry entry = service == null ? null : service.ranking(Board.MONEY).entry(player.getUniqueId());
            cents = entry == null ? 0 : entry.value();
        } else {
            return "";
        }
        return params.endsWith("_raw") ? LeaderboardService.number(cents / 100.0, "en").replace(",", "") : economy.format(cents);
    }

    private String stat(OfflinePlayer player, String params, String code) {
        LeaderboardService service = leaderboards();
        if (service == null) {
            return "";
        }
        StatsTracker.Stats stats = service.stats().cached(player.getUniqueId());
        Board board = switch (params) {
            case "kills" -> Board.KILLS;
            case "mobkills" -> Board.MOB_KILLS;
            case "deaths" -> Board.DEATHS;
            default -> Board.PLAYTIME;
        };
        long value;
        if (stats != null) {
            value = switch (board) {
                case KILLS -> stats.kills();
                case MOB_KILLS -> stats.mobKills();
                case DEATHS -> stats.deaths();
                default -> stats.playtime();
            };
        } else {
            LeaderboardService.Entry entry = service.ranking(board).entry(player.getUniqueId());
            value = entry == null ? 0 : entry.value();
        }
        if (params.equals("playtime_raw")) {
            return String.valueOf(value);
        }
        return board == Board.PLAYTIME ? service.duration(value, code) : LeaderboardService.number(value, code);
    }

    private Rank rank(Player online) {
        RankModule ranks = enabled(RankModule.class);
        return online == null || ranks == null ? null : ranks.service().rankOf(online);
    }

    private LeaderboardService leaderboards() {
        LeaderboardsModule module = enabled(LeaderboardsModule.class);
        return module == null ? null : module.service();
    }

    private SkillService skills() {
        SkillsModule module = enabled(SkillsModule.class);
        return module == null ? null : module.service();
    }

    private <T extends PindaModule> T enabled(Class<T> type) {
        T module = plugin.modules().get(type);
        return module != null && module.isEnabled() ? module : null;
    }
}
