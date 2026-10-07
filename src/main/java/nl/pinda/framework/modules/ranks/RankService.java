package nl.pinda.framework.modules.ranks;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Het eigen rangensysteem. Elke speler heeft één rang; de permissies van die rang (en van de
 * rangen waarvan hij erft) worden aan de speler gegeven. Werkt ook voor andere plugins.
 */
public final class RankService {

    public static final String SETTING = "rank";

    private final PindaFramework plugin;
    private final RankModule module;
    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private String defaultRank = "pinda";

    public RankService(PindaFramework plugin, RankModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private YamlConfiguration cfg() {
        return module.cfg();
    }

    // ============================================================ rangen laden

    public void load() {
        ranks.clear();
        ConfigurationSection section = cfg().getConfigurationSection("ranks");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection rank = section.getConfigurationSection(id);
                if (rank == null) {
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT);
                ranks.put(key, new Rank(key,
                        rank.getString("display-name", id),
                        rank.getInt("weight", 0),
                        rank.getString("inherits"),
                        rank.getBoolean("operator", false),
                        color(rank.getString("color"), NamedTextColor.WHITE),
                        color(rank.getString("chat-color"), NamedTextColor.WHITE),
                        rank.getString("prefix", ""),
                        rank.getStringList("permissions")));
            }
        }
        if (ranks.isEmpty()) {
            plugin.getLogger().warning("Geen rangen gevonden in modules/ranks.yml; er wordt een lege standaardrang gebruikt.");
            ranks.put("pinda", new Rank("pinda", "Pinda", 0, null, false, NamedTextColor.WHITE,
                    NamedTextColor.WHITE, "", List.of()));
        }
        String configured = cfg().getString("default-rank", "pinda").toLowerCase(Locale.ROOT);
        if (!ranks.containsKey(configured)) {
            configured = ranks.keySet().iterator().next();
            plugin.getLogger().warning("default-rank bestaat niet; '" + configured + "' wordt gebruikt.");
        }
        defaultRank = configured;
    }

    private static TextColor color(String value, TextColor fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        TextColor color = trimmed.startsWith("#")
                ? TextColor.fromHexString(trimmed)
                : NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
        return color == null ? fallback : color;
    }

    // ============================================================ opvragen

    public Rank rank(String id) {
        return id == null ? null : ranks.get(id.toLowerCase(Locale.ROOT));
    }

    public Rank defaultRank() {
        return ranks.get(defaultRank);
    }

    /** Alle rangen, van hoog naar laag. */
    public List<Rank> ranks() {
        List<Rank> list = new ArrayList<>(ranks.values());
        list.sort(Comparator.comparingInt(Rank::weight).reversed());
        return list;
    }

    /** Zoekt een rang op naam of op weergavenaam (PindaMod). */
    public Rank find(String input) {
        Rank byId = rank(input);
        if (byId != null) {
            return byId;
        }
        for (Rank rank : ranks.values()) {
            if (rank.displayName().equalsIgnoreCase(input)) {
                return rank;
            }
        }
        return null;
    }

    public Rank rankOf(Player player) {
        PindaPlayer data = plugin.players().get(player.getUniqueId());
        Rank rank = data == null ? null : rank(data.getSetting(SETTING));
        return rank != null ? rank : defaultRank();
    }

    /** De rang van een (eventueel offline) speler. Wordt niet op de hoofdthread afgerond. */
    public CompletableFuture<Rank> rankOf(UUID uuid) {
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            return CompletableFuture.completedFuture(rankOf(online));
        }
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM pinda_player_settings WHERE uuid = ? AND setting = ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, SETTING);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            }
        }).thenApply(id -> {
            Rank rank = rank(id);
            return rank != null ? rank : defaultRank();
        });
    }

    public Component prefix(Rank rank) {
        return rank.prefix() == null || rank.prefix().isEmpty() ? Component.empty() : plugin.lang().parse(rank.prefix());
    }

    // ============================================================ rang geven

    /** Geeft een speler een rang, online of offline. */
    public CompletableFuture<Void> setRank(UUID uuid, Rank rank) {
        Player online = plugin.getServer().getPlayer(uuid);
        if (online != null) {
            PindaPlayer data = plugin.players().get(online);
            data.setSetting(SETTING, rank.id());
            plugin.players().save(data);
            apply(online);
            return CompletableFuture.completedFuture(null);
        }
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_player_settings (uuid, setting, value) VALUES (?, ?, ?) "
                            + "ON CONFLICT(uuid, setting) DO UPDATE SET value = excluded.value")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, SETTING);
                statement.setString(3, rank.id());
                statement.executeUpdate();
            }
        });
    }

    /** Geeft de speler de permissies van zijn rang, en zet operator aan of uit. */
    public void apply(Player player) {
        clear(player);
        Rank rank = rankOf(player);
        PermissionAttachment attachment = player.addAttachment(plugin);
        for (Map.Entry<String, Boolean> entry : resolve(rank).entrySet()) {
            attachment.setPermission(entry.getKey(), entry.getValue());
        }
        attachments.put(player.getUniqueId(), attachment);

        if (rank.operator() && !player.isOp()) {
            player.setOp(true);
        } else if (!rank.operator() && player.isOp() && cfg().getBoolean("sync-operator", true)) {
            player.setOp(false);
            plugin.getLogger().info(player.getName() + " is geen operator meer (rang " + rank.displayName() + ").");
        }
        player.recalculatePermissions();
        player.updateCommands();
        plugin.display().refresh(player);
    }

    public void clear(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            try {
                player.removeAttachment(attachment);
            } catch (IllegalArgumentException ignored) {
                // Al weg, bijvoorbeeld na uitloggen
            }
        }
    }

    public void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
            player.recalculatePermissions();
            player.updateCommands();
        }
        attachments.clear();
    }

    public void applyAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            apply(player);
        }
    }

    /**
     * Alle permissies van een rang, inclusief geërfde. "plugin.*" wordt uitgeschreven naar
     * alle bekende permissies die zo beginnen; "-node" zet een permissie uit.
     */
    public Map<String, Boolean> resolve(Rank rank) {
        List<Rank> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Rank current = rank;
        while (current != null && seen.add(current.id())) {
            chain.add(0, current);
            current = rank(current.inherits());
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Rank link : chain) {
            for (String raw : link.permissions()) {
                String node = raw.trim().toLowerCase(Locale.ROOT);
                if (node.isEmpty()) {
                    continue;
                }
                boolean value = !node.startsWith("-");
                if (!value) {
                    node = node.substring(1);
                }
                result.put(node, value);
                if (node.endsWith(".*")) {
                    String start = node.substring(0, node.length() - 1);
                    for (Permission permission : plugin.getServer().getPluginManager().getPermissions()) {
                        if (permission.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                            result.put(permission.getName().toLowerCase(Locale.ROOT), value);
                        }
                    }
                } else if (node.equals("*")) {
                    for (Permission permission : plugin.getServer().getPluginManager().getPermissions()) {
                        result.put(permission.getName().toLowerCase(Locale.ROOT), value);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Bestaande operators zonder rang krijgen bij hun eerste join met de rangen de
     * operatorrang, zodat de eigenaar zichzelf niet buitensluit.
     */
    public void bootstrap(Player player) {
        PindaPlayer data = plugin.players().get(player);
        if (data.getSetting(SETTING) != null || !player.isOp()) {
            return;
        }
        Rank target = rank(cfg().getString("operators-get-rank", "pindaadmin"));
        if (target == null) {
            return;
        }
        data.setSetting(SETTING, target.id());
        plugin.players().save(data);
        plugin.getLogger().info(player.getName() + " is operator en krijgt de rang " + target.displayName() + ".");
    }

    void logError(String message, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, message, error);
    }
}
