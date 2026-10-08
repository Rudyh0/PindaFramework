package nl.pinda.framework.modules.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.lang.Language;
import nl.pinda.framework.lang.Text;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Alle teksten (meldingen, menu's, tips) bewerken met een live voorbeeld, en het uiterlijk
 * (servernaam, prefix en kleuren) aanpassen.
 */
final class TextsApi extends PanelApi {

    private static final Pattern TAG = Pattern.compile("<([a-z_][a-z0-9_-]*)>");
    private static final String ACTIONBAR = "[actionbar]";
    /** Opmaak-tags die geen placeholder zijn. */
    private static final Set<String> KNOWN_TAGS = Set.of("prefix", "server", "primary", "secondary", "text", "muted",
            "highlight", "success", "error", "warning", "newline", "br", "bold", "b", "italic", "i", "em",
            "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "reset", "black", "dark_blue",
            "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey", "dark_gray", "dark_grey",
            "blue", "green", "aqua", "red", "light_purple", "yellow", "white", "rainbow", "gradient", "shadow");
    /** Voorbeeldwaarden voor het voorbeeld in het paneel. */
    private static final Map<String, String> SAMPLES = Map.ofEntries(
            Map.entry("player", "Rudyh0"), Map.entry("target", "Henk_Bouwt"), Map.entry("actor", "LisaMC"),
            Map.entry("amount", "250 PindaCredits"), Map.entry("cost", "5 PindaCredits"), Map.entry("reason", "Griefen bij de spawn"),
            Map.entry("expires", "13-10-2026 22:00 (nog 6d)"), Map.entry("level", "12"), Map.entry("skill", "Mijnbouw"),
            Map.entry("seconds", "3"), Map.entry("time", "5m"), Map.entry("count", "3"), Map.entry("limit", "5"),
            Map.entry("home", "thuis"), Map.entry("shop", "Henk's Houthandel"), Map.entry("world", "world"),
            Map.entry("x", "112"), Map.entry("y", "64"), Map.entry("z", "-240"), Map.entry("message", "Hallo allemaal!"),
            Map.entry("sender", "Rudyh0"), Map.entry("receiver", "Henk_Bouwt"), Map.entry("version", "0.1.0"),
            Map.entry("percent", "64"), Map.entry("reward", "70 PindaCredits"), Map.entry("multiplier", "2"),
            Map.entry("sleeping", "1"), Map.entry("needed", "2"), Map.entry("day", "42"), Map.entry("minutes", "5"),
            Map.entry("rank_name", "PindaMod"), Map.entry("language", "Nederlands"), Map.entry("status", "Aan"),
            Map.entry("price", "0,75 PindaCredits"), Map.entry("stock", "512"), Map.entry("item", "Oak Log"),
            Map.entry("balance", "1.250 PindaCredits"), Map.entry("cash", "125 PindaCredits"), Map.entry("bank", "7.840 PindaCredits"),
            Map.entry("fee", "2 PindaCredits"), Map.entry("tax", "5%"), Map.entry("input", "abc"), Map.entry("max", "7 dagen"),
            Map.entry("position", "1"), Map.entry("xp", "1.234"), Map.entry("next", "1.500"), Map.entry("total", "96"),
            Map.entry("online", "3"), Map.entry("health", "150"), Map.entry("tip", "Zet je geld op tijd op de /bank."),
            Map.entry("board", "Rijkste spelers"), Map.entry("value", "12.500 PindaCredits"), Map.entry("boards", "money, skills, playtime"),
            Map.entry("description", "Contant geld en bank samen."), Map.entry("tps", "14,2"), Map.entry("mspt", "70,4"),
            Map.entry("chunks", "1.204"), Map.entry("entities", "3.410"), Map.entry("items", "212"),
            Map.entry("location", "world 120, -340"), Map.entry("types", "cow 120, item 40"), Map.entry("mob", "Koe"),
            Map.entry("cx", "7"), Map.entry("cz", "-22"));

    private final ConfigEditor editor;

    TextsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/texts", PanelUser.TEXTS, this::list);
        server.post("/api/texts", PanelUser.TEXTS, this::save);
        server.post("/api/texts/reset", PanelUser.TEXTS, this::reset);
        server.post("/api/texts/preview", PanelUser.USE, this::preview);
        server.get("/api/appearance", PanelUser.TEXTS, request -> appearance());
        server.post("/api/appearance", PanelUser.TEXTS, this::saveAppearance);
    }

    // ============================================================ teksten

    private Object list(PanelRequest request) throws Exception {
        String code = language(request.query("lang"));
        YamlConfiguration current = editor.read("lang/" + code + ".yml");
        YamlConfiguration defaults = bundled(code);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String key : current.getKeys(true)) {
            if (key.startsWith("language") || current.isConfigurationSection(key)) {
                continue;
            }
            Object value = value(current, key);
            Object fallback = defaults == null ? null : value(defaults, key);
            entries.add(map("key", key, "value", value, "default", fallback,
                    "changed", fallback != null && !fallback.equals(value),
                    "placeholders", placeholders(fallback != null ? fallback : value)));
        }
        List<Map<String, Object>> languages = new ArrayList<>();
        for (Language language : plugin.lang().languages()) {
            languages.add(map("code", language.code(), "name", language.name()));
        }
        return map("lang", code, "languages", languages, "entries", entries);
    }

    private String language(String code) throws ApiException {
        String chosen = code == null || code.isBlank() ? plugin.lang().defaultLanguage() : code.trim().toLowerCase(java.util.Locale.ROOT);
        if (plugin.lang().find(chosen) == null) {
            throw ApiException.notFound("Taal '" + chosen + "' bestaat niet.");
        }
        return chosen;
    }

    private static Object value(YamlConfiguration yaml, String key) {
        if (yaml.isList(key)) {
            return yaml.getStringList(key);
        }
        return yaml.isSet(key) ? yaml.getString(key) : null;
    }

    private YamlConfiguration bundled(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> placeholders(Object value) {
        Set<String> found = new LinkedHashSet<>();
        String text = value instanceof List<?> list ? String.join("\n", list.stream().map(String::valueOf).toList()) : String.valueOf(value);
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!KNOWN_TAGS.contains(name)) {
                found.add(name);
            }
        }
        return new ArrayList<>(found);
    }

    private Object save(PanelRequest request) throws Exception {
        String code = language(request.string("lang", "Kies een taal."));
        String key = request.string("key", "Geen tekst gekozen.");
        JsonElement value = request.body().get("value");
        String path = "lang/" + code + ".yml";
        YamlConfiguration yaml = editor.read(path);
        YamlConfiguration defaults = bundled(code);
        if (key.startsWith("language") || (!yaml.isSet(key) && (defaults == null || !defaults.isSet(key)))) {
            throw ApiException.badRequest("Onbekende tekst: " + key);
        }
        if (yaml.isConfigurationSection(key)) {
            throw ApiException.badRequest("Dit is een groep teksten, geen losse tekst.");
        }
        if (value != null && value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() > 200) {
                throw ApiException.badRequest("Te veel regels.");
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement line : array) {
                lines.add(line.isJsonNull() ? "" : line.getAsString());
            }
            yaml.set(key, lines);
        } else if (value != null && value.isJsonPrimitive()) {
            String text = value.getAsString();
            if (text.length() > 4000) {
                throw ApiException.badRequest("De tekst is te lang.");
            }
            yaml.set(key, text);
        } else {
            throw ApiException.badRequest("Geen tekst meegestuurd.");
        }
        editor.write(path, yaml);
        sync(() -> {
            plugin.lang().load();
            return null;
        });
        module.log().add(request, "tekst", code + ":" + key, null);
        return map("key", key, "value", value(yaml, key));
    }

    private Object reset(PanelRequest request) throws Exception {
        String code = language(request.string("lang", "Kies een taal."));
        String key = request.string("key", "Geen tekst gekozen.");
        YamlConfiguration defaults = bundled(code);
        if (defaults == null || !defaults.isSet(key) || defaults.isConfigurationSection(key)) {
            throw ApiException.badRequest("Voor deze tekst is geen standaardtekst bekend.");
        }
        String path = "lang/" + code + ".yml";
        YamlConfiguration yaml = editor.read(path);
        yaml.set(key, defaults.get(key));
        editor.write(path, yaml);
        sync(() -> {
            plugin.lang().load();
            return null;
        });
        module.log().add(request, "tekst teruggezet", code + ":" + key, null);
        return map("key", key, "value", value(yaml, key));
    }

    /** Hoe een tekst er in-game uitziet, als HTML. */
    private Object preview(PanelRequest request) throws Exception {
        JsonElement value = request.body().get("text");
        String text;
        if (value != null && value.isJsonArray()) {
            List<String> lines = new ArrayList<>();
            for (JsonElement line : value.getAsJsonArray()) {
                lines.add(line.isJsonNull() ? "" : line.getAsString());
            }
            text = String.join("\n", lines);
        } else {
            text = value == null || value.isJsonNull() ? "" : value.getAsString();
        }
        if (text.length() > 8000) {
            throw ApiException.badRequest("De tekst is te lang.");
        }
        return map("html", render(text), "actionbar", text.startsWith(ACTIONBAR));
    }

    /** MiniMessage-tekst naar HTML, met de thema-tags en voorbeeldwaarden voor placeholders. */
    String render(String text) throws Exception {
        String source = text.startsWith(ACTIONBAR) ? text.substring(ACTIONBAR.length()).stripLeading() : text;
        List<TagResolver> resolvers = new ArrayList<>();
        for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
            resolvers.add(Text.p(sample.getKey(), sample.getValue()));
        }
        TagResolver all = TagResolver.resolver(resolvers);
        return sync(() -> ComponentHtml.render(plugin.lang().parse(source, all)));
    }

    // ============================================================ uiterlijk

    private Object appearance() throws Exception {
        YamlConfiguration config = editor.read("config.yml");
        Map<String, Object> colors = new LinkedHashMap<>();
        for (String color : List.of("primary", "secondary", "text", "muted", "highlight", "success", "error", "warning")) {
            colors.put(color, config.getString("theme.colors." + color, "#FFFFFF"));
        }
        String serverName = config.getString("server-name", "PindaCraft");
        String prefix = config.getString("theme.prefix", "");
        return map("serverName", serverName, "prefix", prefix, "colors", colors,
                "serverNameHtml", render(serverName), "prefixHtml", render(prefix));
    }

    private Object saveAppearance(PanelRequest request) throws Exception {
        YamlConfiguration config = editor.read("config.yml");
        List<String> changed = new ArrayList<>();
        String serverName = request.optString("serverName");
        if (serverName != null) {
            if (serverName.length() > 200) {
                throw ApiException.badRequest("De servernaam is te lang.");
            }
            config.set("server-name", serverName);
            changed.add("servernaam");
        }
        if (request.body().has("prefix")) {
            String prefix = request.body().get("prefix").isJsonNull() ? "" : request.body().get("prefix").getAsString();
            if (prefix.length() > 500) {
                throw ApiException.badRequest("De prefix is te lang.");
            }
            config.set("theme.prefix", prefix);
            changed.add("prefix");
        }
        JsonElement colors = request.body().get("colors");
        if (colors != null && colors.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : colors.getAsJsonObject().entrySet()) {
                String color = entry.getValue().getAsString().trim();
                if (!config.isSet("theme.colors." + entry.getKey())) {
                    throw ApiException.badRequest("Onbekende kleur: " + entry.getKey());
                }
                if (!color.matches("#[0-9a-fA-F]{6}") && !color.matches("[a-z_]+")) {
                    throw ApiException.badRequest("'" + color + "' is geen geldige kleur.");
                }
                config.set("theme.colors." + entry.getKey(), color.toUpperCase(java.util.Locale.ROOT).startsWith("#") ? color.toUpperCase(java.util.Locale.ROOT) : color);
            }
            changed.add("kleuren");
        }
        if (changed.isEmpty()) {
            throw ApiException.badRequest("Er is niets veranderd.");
        }
        editor.write("config.yml", config);
        sync(() -> plugin.reload());
        module.log().add(request, "uiterlijk", null, String.join(", ", changed));
        return appearance();
    }
}
