package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import nl.pinda.framework.modules.ranks.RankModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;

/** Rangen maken, bewerken en verwijderen, plus de chatopmaak en standaardrang. */
final class RanksApi extends PanelApi {

    private static final String FILE = "modules/ranks.yml";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}|[a-z_]+");

    private final ConfigEditor editor;

    RanksApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/ranks", PanelUser.RANKS_EDIT, this::list);
        server.post("/api/ranks/save", PanelUser.RANKS_EDIT, this::save);
        server.post("/api/ranks/delete", PanelUser.RANKS_EDIT, this::delete);
        server.post("/api/ranks/settings", PanelUser.RANKS_EDIT, this::settings);
    }

    // ============================================================ overzicht

    private Object list(PanelRequest request) throws Exception {
        require(RankModule.class, "ranks");
        YamlConfiguration yaml = editor.read(FILE);
        String defaultRank = yaml.getString("default-rank", "pinda").toLowerCase(Locale.ROOT);
        Map<String, Integer> counts = counts();
        int withRank = 0;
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        List<Map<String, Object>> ranks = new ArrayList<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                withRank += counts.getOrDefault(id.toLowerCase(Locale.ROOT), 0);
            }
            int total = counts.getOrDefault("*", 0);
            for (String id : section.getKeys(false)) {
                ConfigurationSection rank = section.getConfigurationSection(id);
                if (rank == null) {
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT);
                int players = counts.getOrDefault(key, 0) + (key.equals(defaultRank) ? Math.max(0, total - withRank) : 0);
                String prefix = rank.getString("prefix", "");
                ranks.add(map("id", key,
                        "displayName", rank.getString("display-name", id),
                        "weight", rank.getInt("weight", 0),
                        "inherits", rank.getString("inherits"),
                        "operator", rank.getBoolean("operator", false),
                        "color", rank.getString("color", "#FFFFFF"),
                        "chatColor", rank.getString("chat-color", "#FFFFFF"),
                        "prefix", prefix,
                        "prefixHtml", ComponentHtml.render(plugin.lang().parse(prefix)),
                        "permissions", rank.getStringList("permissions"),
                        "players", players,
                        "isDefault", key.equals(defaultRank)));
            }
        }
        ranks.sort(Comparator.comparingInt((Map<String, Object> rank) -> (Integer) rank.get("weight")).reversed());

        List<Map<String, Object>> permissions = sync(() -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Permission permission : plugin.getServer().getPluginManager().getPermissions()) {
                list.add(map("name", permission.getName().toLowerCase(Locale.ROOT), "description", permission.getDescription()));
            }
            list.sort(Comparator.comparing(entry -> (String) entry.get("name")));
            return list;
        });
        return map("ranks", ranks, "permissions", permissions, "settings", map(
                "defaultRank", defaultRank,
                "operatorsGetRank", yaml.getString("operators-get-rank", ""),
                "syncOperator", yaml.getBoolean("sync-operator", true),
                "chatEnabled", yaml.getBoolean("chat.enabled", true),
                "chatFormat", yaml.getString("chat.format", "")));
    }

    /** Hoeveel spelers elke rang hebben ("*" = alle spelers). */
    private Map<String, Integer> counts() throws Exception {
        return await(plugin.database().query(connection -> {
            Map<String, Integer> counts = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value, COUNT(*) FROM pinda_player_settings WHERE setting = 'rank' GROUP BY value");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    counts.put(result.getString(1).toLowerCase(Locale.ROOT), result.getInt(2));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pinda_players");
                 ResultSet result = statement.executeQuery()) {
                counts.put("*", result.next() ? result.getInt(1) : 0);
            }
            return counts;
        }));
    }

    // ============================================================ opslaan

    private Object save(PanelRequest request) throws Exception {
        require(RankModule.class, "ranks");
        PanelUser user = request.user();
        String id = request.string("id", "Geef de rang een id.").toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw ApiException.badRequest("Het id mag alleen kleine letters, cijfers, - en _ bevatten (max 32 tekens).");
        }
        YamlConfiguration yaml = editor.read(FILE);
        String path = "ranks." + id;
        boolean exists = yaml.isConfigurationSection(path);
        if (request.body().has("create") && request.body().get("create").getAsBoolean() && exists) {
            throw ApiException.badRequest("Er bestaat al een rang met id '" + id + "'.");
        }
        String displayName = request.string("displayName", "Geef de rang een naam.");
        int weight;
        try {
            weight = Integer.parseInt(request.string("weight", "Vul een gewicht in.").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Het gewicht moet een geheel getal zijn.");
        }
        boolean operator = request.body().has("operator") && request.body().get("operator").getAsBoolean();
        if (!user.operator()) {
            int current = exists ? yaml.getInt(path + ".weight", 0) : Integer.MIN_VALUE;
            if (current >= user.weight() || weight >= user.weight() || operator) {
                throw ApiException.forbidden("Je kunt alleen rangen beheren die lager zijn dan je eigen rang, zonder operator.");
            }
        }
        String inherits = request.optString("inherits");
        if (inherits != null) {
            inherits = inherits.toLowerCase(Locale.ROOT);
            if (inherits.equals(id) || !yaml.isConfigurationSection("ranks." + inherits)) {
                throw ApiException.badRequest("De rang om van te erven bestaat niet.");
            }
            if (!user.operator() && (yaml.getInt("ranks." + inherits + ".weight", 0) >= user.weight()
                    || yaml.getBoolean("ranks." + inherits + ".operator", false))) {
                throw ApiException.forbidden("Je kunt alleen laten erven van een rang die lager is dan je eigen rang.");
            }
            // Geen rondje: A erft van B die erft van A
            Set<String> seen = new HashSet<>(List.of(id));
            String current = inherits;
            while (current != null) {
                if (!seen.add(current)) {
                    throw ApiException.badRequest("Dit geeft een rondje in het erven van rangen.");
                }
                current = yaml.getString("ranks." + current + ".inherits");
                current = current == null ? null : current.toLowerCase(Locale.ROOT);
            }
        }
        String color = color(request.optString("color"), "#FFFFFF");
        String chatColor = color(request.optString("chatColor"), "#FFFFFF");
        String prefix = request.optString("prefix");
        if (prefix != null && prefix.length() > 500) {
            throw ApiException.badRequest("De prefix is te lang.");
        }
        List<String> permissions = new ArrayList<>();
        JsonElement list = request.body().get("permissions");
        if (list != null && list.isJsonArray()) {
            Set<String> unique = new LinkedHashSet<>();
            for (JsonElement element : list.getAsJsonArray()) {
                String node = element.getAsString().trim().toLowerCase(Locale.ROOT);
                if (!node.isEmpty() && node.length() <= 200 && !node.contains(" ")) {
                    unique.add(node);
                }
            }
            if (unique.size() > 1000) {
                throw ApiException.badRequest("Te veel permissies.");
            }
            if (!user.operator()) {
                for (String node : unique) {
                    if (!node.startsWith("-") && !user.has(node)) {
                        throw ApiException.forbidden("Je kunt alleen permissies geven die je zelf ook hebt (" + node + ").");
                    }
                }
            }
            permissions.addAll(unique);
        }

        yaml.set(path + ".display-name", displayName);
        yaml.set(path + ".weight", weight);
        yaml.set(path + ".inherits", inherits);
        yaml.set(path + ".operator", operator ? true : null);
        yaml.set(path + ".color", color);
        yaml.set(path + ".chat-color", chatColor);
        yaml.set(path + ".prefix", prefix == null ? "" : prefix);
        yaml.set(path + ".permissions", permissions);
        editor.write(FILE, yaml);
        reloadRanks();
        module.log().add(request, exists ? "rang bewerkt" : "rang gemaakt", displayName, permissions.size() + " permissies");
        return list(request);
    }

    private static String color(String value, String fallback) throws ApiException {
        if (value == null) {
            return fallback;
        }
        if (!COLOR.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("'" + value + "' is geen geldige kleur.");
        }
        return value.trim().startsWith("#") ? value.trim().toUpperCase(Locale.ROOT) : value.trim();
    }

    private Object delete(PanelRequest request) throws Exception {
        require(RankModule.class, "ranks");
        PanelUser user = request.user();
        String id = request.string("id", "Kies een rang.").toLowerCase(Locale.ROOT);
        YamlConfiguration yaml = editor.read(FILE);
        if (!yaml.isConfigurationSection("ranks." + id)) {
            throw ApiException.notFound("Deze rang bestaat niet.");
        }
        if (id.equalsIgnoreCase(yaml.getString("default-rank", "pinda"))) {
            throw ApiException.badRequest("De standaardrang kan niet verwijderd worden. Kies eerst een andere standaardrang.");
        }
        if (!user.operator() && yaml.getInt("ranks." + id + ".weight", 0) >= user.weight()) {
            throw ApiException.forbidden("Je kunt alleen rangen verwijderen die lager zijn dan je eigen rang.");
        }
        String name = yaml.getString("ranks." + id + ".display-name", id);
        yaml.set("ranks." + id, null);
        ConfigurationSection ranks = yaml.getConfigurationSection("ranks");
        if (ranks != null) {
            for (String other : ranks.getKeys(false)) {
                if (id.equalsIgnoreCase(ranks.getString(other + ".inherits"))) {
                    ranks.set(other + ".inherits", null);
                }
            }
        }
        editor.write(FILE, yaml);
        reloadRanks();
        module.log().add(request, "rang verwijderd", name, null);
        return list(request);
    }

    private Object settings(PanelRequest request) throws Exception {
        require(RankModule.class, "ranks");
        YamlConfiguration yaml = editor.read(FILE);
        JsonObject body = request.body();
        if (!request.user().operator() && changesSecurity(yaml, body)) {
            throw ApiException.forbidden("Alleen een operator kan de standaardrang, de rang voor operators of de operator-instelling aanpassen.");
        }
        String defaultRank = request.optString("defaultRank");
        if (defaultRank != null) {
            if (!yaml.isConfigurationSection("ranks." + defaultRank.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("Die standaardrang bestaat niet.");
            }
            yaml.set("default-rank", defaultRank.toLowerCase(Locale.ROOT));
        }
        String operatorsRank = request.optString("operatorsGetRank");
        if (operatorsRank != null) {
            if (!yaml.isConfigurationSection("ranks." + operatorsRank.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("De rang voor operators bestaat niet.");
            }
            yaml.set("operators-get-rank", operatorsRank.toLowerCase(Locale.ROOT));
        }
        if (body.has("syncOperator")) {
            yaml.set("sync-operator", body.get("syncOperator").getAsBoolean());
        }
        if (body.has("chatEnabled")) {
            yaml.set("chat.enabled", body.get("chatEnabled").getAsBoolean());
        }
        String format = request.optString("chatFormat");
        if (format != null) {
            if (!format.contains("<message>")) {
                throw ApiException.badRequest("De chatopmaak moet <message> bevatten, anders zie je het bericht niet.");
            }
            yaml.set("chat.format", format);
        }
        editor.write(FILE, yaml);
        reloadRanks();
        module.log().add(request, "rangen-instellingen", null, null);
        return list(request);
    }

    /** Verandert dit verzoek de standaardrang, de rang voor operators of het gelijkhouden van operator? */
    private static boolean changesSecurity(YamlConfiguration yaml, JsonObject body) {
        return differs(body, "defaultRank", yaml.getString("default-rank", ""))
                || differs(body, "operatorsGetRank", yaml.getString("operators-get-rank", ""))
                || (body.has("syncOperator") && body.get("syncOperator").getAsBoolean() != yaml.getBoolean("sync-operator", true));
    }

    private static boolean differs(JsonObject body, String key, String current) {
        return body.has(key) && !body.get(key).isJsonNull() && !body.get(key).getAsString().equalsIgnoreCase(current);
    }

    private void reloadRanks() throws Exception {
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
    }
}
