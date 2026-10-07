package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import nl.pinda.framework.modules.motd.MotdModule;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/** Serverinstellingen: server.properties, spelregels (gamerules) per wereld en de MOTD. */
final class ServerSettingsApi extends PanelApi {

    /** Een instelling uit server.properties die in het paneel staat. */
    private record Property(String key, String type, boolean live, List<String> options) {
    }

    private static final List<Property> PROPERTIES = List.of(
            new Property("max-players", "integer", true, null),
            new Property("difficulty", "select", true, List.of("peaceful", "easy", "normal", "hard")),
            new Property("gamemode", "select", true, List.of("survival", "creative", "adventure", "spectator")),
            new Property("force-gamemode", "boolean", false, null),
            new Property("pvp", "boolean", true, null),
            new Property("view-distance", "integer", true, null),
            new Property("simulation-distance", "integer", true, null),
            new Property("spawn-protection", "integer", true, null),
            new Property("player-idle-timeout", "integer", true, null),
            new Property("allow-flight", "boolean", false, null),
            new Property("allow-nether", "boolean", false, null),
            new Property("enable-command-block", "boolean", false, null),
            new Property("hide-online-players", "boolean", false, null),
            new Property("entity-broadcast-range-percentage", "integer", false, null),
            new Property("white-list", "boolean", true, null),
            new Property("enforce-whitelist", "boolean", true, null),
            new Property("motd", "string", false, null),
            new Property("resource-pack", "string", false, null),
            new Property("require-resource-pack", "boolean", false, null));

    private final ConfigEditor editor;

    ServerSettingsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/server/settings", PanelUser.CONFIG, this::settings);
        server.post("/api/server/properties", PanelUser.CONFIG, this::saveProperties);
        server.post("/api/server/gamerule", PanelUser.CONFIG, this::gamerule);
        server.get("/api/motd", PanelUser.CONFIG, request -> motd());
        server.post("/api/motd", PanelUser.CONFIG, this::saveMotd);
        server.get("/api/server/icon", PanelUser.USE, this::icon);
    }

    private static Path propertiesFile() {
        return Path.of("server.properties").toAbsolutePath();
    }

    private static Properties readProperties() throws IOException {
        Properties properties = new Properties();
        Path file = propertiesFile();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    // ============================================================ overzicht

    private Object settings(PanelRequest request) throws Exception {
        Properties properties = readProperties();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Property property : PROPERTIES) {
            if (!properties.containsKey(property.key())) {
                continue;
            }
            list.add(map("key", property.key(), "type", property.type(), "live", property.live(),
                    "options", property.options(), "value", properties.getProperty(property.key())));
        }
        Map<String, Object> gamerules = sync(() -> {
            List<String> worlds = new ArrayList<>();
            for (World world : plugin.getServer().getWorlds()) {
                worlds.add(world.getName());
            }
            List<Map<String, Object>> rules = new ArrayList<>();
            for (GameRule<?> rule : GameRule.values()) {
                Class<?> type = rule.getType();
                if (type != Boolean.class && type != Integer.class) {
                    continue;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                Object fallback = null;
                for (World world : plugin.getServer().getWorlds()) {
                    values.put(world.getName(), world.getGameRuleValue(rule));
                    if (fallback == null) {
                        fallback = world.getGameRuleDefault(rule);
                    }
                }
                rules.add(map("name", rule.getName(), "type", type == Boolean.class ? "boolean" : "integer",
                        "default", fallback, "values", values));
            }
            rules.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare((String) a.get("name"), (String) b.get("name")));
            return map("worlds", worlds, "rules", rules);
        });
        return map("properties", list, "gamerules", gamerules);
    }

    // ============================================================ server.properties

    private Object saveProperties(PanelRequest request) throws Exception {
        JsonElement element = request.body().get("values");
        if (element == null || !element.isJsonObject()) {
            throw ApiException.badRequest("Geen wijzigingen meegestuurd.");
        }
        JsonObject values = element.getAsJsonObject();
        Map<String, String> updates = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            Property property = PROPERTIES.stream().filter(p -> p.key().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Onbekende instelling: " + entry.getKey()));
            updates.put(property.key(), validate(property, entry.getValue()));
        }
        if (updates.isEmpty()) {
            throw ApiException.badRequest("Er is niets veranderd.");
        }
        // Eerst live toepassen: sommige daarvan schrijven zelf server.properties weg.
        sync(() -> {
            applyLive(updates);
            return null;
        });
        writeProperties(updates);
        module.log().add(request, "server.properties", null, String.join(", ", updates.keySet()));
        return settings(request);
    }

    private static String validate(Property property, JsonElement value) throws ApiException {
        if (value == null || !value.isJsonPrimitive()) {
            throw ApiException.badRequest("Ongeldige waarde voor " + property.key() + ".");
        }
        String text = value.getAsString().trim();
        switch (property.type()) {
            case "boolean" -> {
                if (!text.equals("true") && !text.equals("false")) {
                    throw ApiException.badRequest(property.key() + " moet aan of uit zijn.");
                }
            }
            case "integer" -> {
                try {
                    int number = Integer.parseInt(text);
                    if (number < 0 || number > 1_000_000) {
                        throw new NumberFormatException();
                    }
                    if ((property.key().endsWith("distance")) && (number < 2 || number > 32)) {
                        throw ApiException.badRequest(property.key() + " moet tussen 2 en 32 liggen.");
                    }
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest(property.key() + " moet een geheel getal zijn.");
                }
            }
            case "select" -> {
                if (!property.options().contains(text.toLowerCase(Locale.ROOT))) {
                    throw ApiException.badRequest("Ongeldige keuze voor " + property.key() + ".");
                }
                text = text.toLowerCase(Locale.ROOT);
            }
            default -> {
                if (text.length() > 500 || text.contains("\n")) {
                    throw ApiException.badRequest(property.key() + " is te lang.");
                }
            }
        }
        return text;
    }

    private void applyLive(Map<String, String> updates) {
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "max-players" -> plugin.getServer().setMaxPlayers(Integer.parseInt(value));
                case "difficulty" -> {
                    Difficulty difficulty = Difficulty.valueOf(value.toUpperCase(Locale.ROOT));
                    for (World world : plugin.getServer().getWorlds()) {
                        world.setDifficulty(difficulty);
                    }
                }
                case "gamemode" -> plugin.getServer().setDefaultGameMode(GameMode.valueOf(value.toUpperCase(Locale.ROOT)));
                case "pvp" -> {
                    for (World world : plugin.getServer().getWorlds()) {
                        world.setPVP(Boolean.parseBoolean(value));
                    }
                }
                case "view-distance" -> {
                    for (World world : plugin.getServer().getWorlds()) {
                        world.setViewDistance(Integer.parseInt(value));
                    }
                }
                case "simulation-distance" -> {
                    for (World world : plugin.getServer().getWorlds()) {
                        world.setSimulationDistance(Integer.parseInt(value));
                    }
                }
                case "spawn-protection" -> plugin.getServer().setSpawnRadius(Integer.parseInt(value));
                case "player-idle-timeout" -> plugin.getServer().setIdleTimeout(Integer.parseInt(value));
                case "white-list" -> plugin.getServer().setWhitelist(Boolean.parseBoolean(value));
                case "enforce-whitelist" -> plugin.getServer().setWhitelistEnforced(Boolean.parseBoolean(value));
                default -> {
                    // Geldt na een herstart
                }
            }
        }
    }

    /** Past regels in server.properties aan, met behoud van de rest van het bestand. */
    private static void writeProperties(Map<String, String> updates) throws IOException {
        Path file = propertiesFile();
        List<String> lines = Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        Map<String, String> remaining = new LinkedHashMap<>(updates);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('=')).trim();
            if (remaining.containsKey(key)) {
                lines.set(index, key + "=" + escape(remaining.remove(key)));
            }
        }
        for (Map.Entry<String, String> entry : remaining.entrySet()) {
            lines.add(entry.getKey() + "=" + escape(entry.getValue()));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '=' || c == ':') {
                out.append('\\').append(c);
            } else if (c > 126) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ============================================================ spelregels

    @SuppressWarnings("unchecked")
    private Object gamerule(PanelRequest request) throws Exception {
        String name = request.string("rule", "Kies een spelregel.");
        String worldName = request.optString("world");
        JsonElement value = request.body().get("value");
        if (value == null || !value.isJsonPrimitive()) {
            throw ApiException.badRequest("Geen waarde meegestuurd.");
        }
        GameRule<?> rule = null;
        for (GameRule<?> candidate : GameRule.values()) {
            if (candidate.getName().equals(name)) {
                rule = candidate;
            }
        }
        if (rule == null) {
            throw ApiException.notFound("Spelregel '" + name + "' bestaat niet.");
        }
        Object parsed;
        if (rule.getType() == Boolean.class) {
            parsed = value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : Boolean.parseBoolean(value.getAsString());
        } else if (rule.getType() == Integer.class) {
            try {
                parsed = Integer.parseInt(value.getAsString().trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Vul een geheel getal in.");
            }
        } else {
            throw ApiException.badRequest("Deze spelregel kan hier niet aangepast worden.");
        }
        GameRule<Object> target = (GameRule<Object>) rule;
        Object chosen = parsed;
        String done = sync(() -> {
            List<String> names = new ArrayList<>();
            for (World world : plugin.getServer().getWorlds()) {
                if (worldName == null || world.getName().equals(worldName)) {
                    world.setGameRule(target, chosen);
                    names.add(world.getName());
                }
            }
            return String.join(", ", names);
        });
        if (done.isEmpty()) {
            throw ApiException.notFound("Wereld '" + worldName + "' bestaat niet.");
        }
        module.log().add(request, "spelregel", name, chosen + " in " + done);
        return map("rule", name, "value", chosen, "worlds", done);
    }

    // ============================================================ MOTD

    private Object motd() throws Exception {
        MotdModule motd = enabled(MotdModule.class);
        YamlConfiguration yaml = editor.read("modules/motd.yml");
        List<String> motds = yaml.getStringList("motds");
        List<String> previews = new ArrayList<>();
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = yaml.getInt("shown-max-players", -1) >= 0 ? yaml.getInt("shown-max-players") : plugin.getServer().getMaxPlayers();
        for (String raw : motds) {
            previews.add(motd == null ? ComponentHtml.escape(raw) : ComponentHtml.render(motd.render(raw, online, max)));
        }
        return map("moduleEnabled", motd != null, "enabled", yaml.getBoolean("enabled", true), "motds", motds,
                "previews", previews, "shownMax", yaml.getInt("shown-max-players", -1),
                "hidePlayers", yaml.getBoolean("hide-players", false), "online", online, "max", max);
    }

    private Object saveMotd(PanelRequest request) throws Exception {
        YamlConfiguration yaml = editor.read("modules/motd.yml");
        JsonElement motds = request.body().get("motds");
        if (motds != null && motds.isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement line : motds.getAsJsonArray()) {
                String text = line.getAsString().trim();
                if (!text.isEmpty()) {
                    if (text.length() > 1000) {
                        throw ApiException.badRequest("Een MOTD is te lang.");
                    }
                    list.add(text);
                }
            }
            if (list.isEmpty()) {
                throw ApiException.badRequest("Er moet minimaal één MOTD zijn.");
            }
            yaml.set("motds", list);
        }
        if (request.body().has("enabled")) {
            yaml.set("enabled", request.body().get("enabled").getAsBoolean());
        }
        if (request.body().has("shownMax")) {
            yaml.set("shown-max-players", request.body().get("shownMax").getAsInt());
        }
        if (request.body().has("hidePlayers")) {
            yaml.set("hide-players", request.body().get("hidePlayers").getAsBoolean());
        }
        editor.write("modules/motd.yml", yaml);
        sync(() -> {
            editor.reload("modules/motd.yml");
            return null;
        });
        module.log().add(request, "motd", null, null);
        return motd();
    }

    /** Het servericoon (server-icon.png), voor het voorbeeld van de serverlijst. */
    private Object icon(PanelRequest request) throws Exception {
        Path file = Path.of("server-icon.png").toAbsolutePath();
        if (!Files.isRegularFile(file) || Files.size(file) > 512 * 1024) {
            throw ApiException.notFound("Geen servericoon.");
        }
        byte[] bytes = Files.readAllBytes(file);
        Headers headers = request.exchange().getResponseHeaders();
        headers.set("Content-Type", "image/png");
        headers.set("Cache-Control", "no-cache");
        request.exchange().sendResponseHeaders(200, bytes.length);
        try (OutputStream out = request.exchange().getResponseBody()) {
            out.write(bytes);
        }
        return PanelServer.HANDLED;
    }
}
