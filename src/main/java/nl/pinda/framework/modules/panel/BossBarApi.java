package nl.pinda.framework.modules.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import nl.pinda.framework.integration.Placeholders;
import nl.pinda.framework.modules.bossbar.BarDefinition;
import nl.pinda.framework.modules.bossbar.BossBarModule;
import nl.pinda.framework.modules.bossbar.ProgressMode;
import nl.pinda.framework.modules.bossbar.Rotation;
import nl.pinda.framework.modules.bossbar.TextEffect;
import nl.pinda.framework.modules.leaderboards.Board;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** De bossbars in het paneel: bekijken (met bewegend voorbeeld), maken, aanpassen en meteen tonen. */
final class BossBarApi extends PanelApi {

    private static final String FILE = "modules/bossbar.yml";
    private static final int MAX_BARS = 50;
    private static final int MAX_ROTATIONS = 10;
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._-]{0,100}");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    private final ConfigEditor editor;

    BossBarApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/bossbar", PanelUser.CONFIG, this::overview);
        server.post("/api/bossbar", PanelUser.CONFIG, this::save);
        server.post("/api/bossbar/preview", PanelUser.CONFIG, this::preview);
        server.post("/api/bossbar/show", PanelUser.CONFIG, this::show);
        server.post("/api/bossbar/hide", PanelUser.CONFIG, this::hide);
    }

    private Object overview(PanelRequest request) throws Exception {
        BossBarModule bossbar = enabled(BossBarModule.class);
        YamlConfiguration yaml = editor.read(FILE);
        Map<String, BarDefinition> bars = definitions(yaml);
        List<Map<String, Object>> rotations = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("rotations")) {
            rotations.add(Rotation.parse(map).toMap());
        }
        Map<String, Object> rendered = sync(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (BarDefinition bar : bars.values()) {
                result.put(bar.id(), render(bossbar, request, bar.frames()));
            }
            return result;
        });
        List<Map<String, Object>> list = new ArrayList<>();
        for (BarDefinition bar : bars.values()) {
            Map<String, Object> map = bar.toMap();
            map.put("previews", rendered.get(bar.id()));
            list.add(map);
        }
        List<String> worlds = sync(() -> plugin.getServer().getWorlds().stream().map(World::getName).toList());
        List<Map<String, Object>> boards = new ArrayList<>();
        LeaderboardsModule leaderboards = enabled(LeaderboardsModule.class);
        String code = plugin.lang().defaultLanguage();
        for (Board board : Board.values()) {
            boards.add(map("id", board.id(), "name", leaderboards == null ? board.id() : leaderboards.service().name(board, code)));
        }
        return map("moduleEnabled", bossbar != null,
                "settings", map("defaultEnabled", yaml.getBoolean("default-enabled", true),
                        "showInSetup", yaml.getBoolean("show-in-setup", true),
                        "disabledWorlds", yaml.getStringList("disabled-worlds")),
                "bars", list, "rotations", rotations,
                "clock", bossbar == null ? 0 : sync(bossbar::clock),
                "worlds", worlds, "boards", boards,
                "leaderboards", leaderboards != null,
                "placeholderApi", Placeholders.available(),
                "online", plugin.getServer().getOnlinePlayers().size(),
                "max", plugin.getServer().getMaxPlayers());
    }

    private Map<String, BarDefinition> definitions(YamlConfiguration yaml) {
        Map<String, BarDefinition> bars = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("bars");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection bar = section.getConfigurationSection(key);
                String id = key.toLowerCase(Locale.ROOT);
                if (bar != null && BarDefinition.ID.matcher(id).matches()) {
                    bars.putIfAbsent(id, BarDefinition.parse(id, bar));
                }
            }
        }
        return bars;
    }

    /** De frames als HTML, voor wie in het paneel kijkt (is die speler online, dan met zijn eigen gegevens). */
    private List<String> render(BossBarModule bossbar, PanelRequest request, List<String> frames) {
        List<String> html = new ArrayList<>();
        Player viewer = plugin.getServer().getPlayer(request.user().uuid());
        OfflinePlayer as = viewer != null ? viewer : plugin.getServer().getOfflinePlayer(request.user().uuid());
        String code = viewer == null ? plugin.lang().defaultLanguage() : plugin.lang().languageOf(viewer);
        for (String frame : frames) {
            try {
                html.add(bossbar == null ? ComponentHtml.escape(frame) : ComponentHtml.render(bossbar.render(viewer, as, frame, code)));
            } catch (RuntimeException e) {
                html.add("<span style=\"color:#ff6b6b\">" + ComponentHtml.escape("Fout in de opmaak: " + e.getMessage()) + "</span>");
            }
        }
        return html;
    }

    private Object preview(PanelRequest request) throws Exception {
        BossBarModule bossbar = enabled(BossBarModule.class);
        JsonElement frames = request.body().get("frames");
        List<String> list = new ArrayList<>();
        if (frames != null && frames.isJsonArray()) {
            for (JsonElement element : frames.getAsJsonArray()) {
                if (list.size() >= BarDefinition.MAX_FRAMES) {
                    break;
                }
                String text = element.isJsonPrimitive() ? element.getAsString() : "";
                list.add(text.length() > BarDefinition.MAX_TEXT ? text.substring(0, BarDefinition.MAX_TEXT) : text);
            }
        }
        return map("html", sync(() -> render(bossbar, request, list)));
    }

    private Object show(PanelRequest request) throws Exception {
        BossBarModule bossbar = require(BossBarModule.class, "bossbar");
        String id = request.string("id", "Kies een bossbar.").toLowerCase(Locale.ROOT);
        JsonElement secondsElement = request.body().get("seconds");
        int seconds;
        try {
            seconds = secondsElement == null || secondsElement.isJsonNull() ? 0 : secondsElement.getAsInt();
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Seconden moet een getal zijn.");
        }
        if (seconds < 0 || seconds > 86_400) {
            throw ApiException.badRequest("Kies 0 tot 86400 seconden.");
        }
        int count = sync(() -> {
            if (!bossbar.bars().containsKey(id)) {
                return -1;
            }
            return bossbar.show(id, plugin.getServer().getOnlinePlayers(), seconds);
        });
        if (count < 0) {
            throw ApiException.notFound("Die bossbar bestaat niet (sla eerst op).");
        }
        module.log().add(request, "bossbar getoond", null, id);
        return map("shown", count);
    }

    private Object hide(PanelRequest request) throws Exception {
        BossBarModule bossbar = require(BossBarModule.class, "bossbar");
        String id = request.string("id", "Kies een bossbar.").toLowerCase(Locale.ROOT);
        int count = sync(() -> bossbar.hide(id, plugin.getServer().getOnlinePlayers()));
        module.log().add(request, "bossbar weggehaald", null, id);
        return map("hidden", count);
    }

    // ============================================================ opslaan

    private Object save(PanelRequest request) throws Exception {
        try {
            return store(request);
        } catch (IllegalStateException | UnsupportedOperationException | ClassCastException | NumberFormatException e) {
            // Een veld van het verkeerde soort (bijv. een object waar tekst hoort).
            throw ApiException.badRequest("Ongeldige gegevens: " + e.getMessage());
        }
    }

    private Object store(PanelRequest request) throws Exception {
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        if (body.has("settings") && body.get("settings").isJsonObject()) {
            JsonObject settings = body.getAsJsonObject("settings");
            if (settings.has("defaultEnabled")) {
                yaml.set("default-enabled", settings.get("defaultEnabled").getAsBoolean());
            }
            if (settings.has("showInSetup")) {
                yaml.set("show-in-setup", settings.get("showInSetup").getAsBoolean());
            }
            if (settings.has("disabledWorlds")) {
                yaml.set("disabled-worlds", names(settings.get("disabledWorlds"), "werelden"));
            }
        }
        Set<String> ids = new LinkedHashSet<>(definitions(yaml).keySet());
        if (body.has("bars")) {
            JsonArray array = array(body.get("bars"), "bossbars");
            if (array.size() > MAX_BARS) {
                throw ApiException.badRequest("Hooguit " + MAX_BARS + " bossbars.");
            }
            List<BarDefinition> list = new ArrayList<>();
            ids.clear();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw ApiException.badRequest("Ongeldige bossbar.");
                }
                BarDefinition bar = bar(element.getAsJsonObject());
                if (!ids.add(bar.id())) {
                    throw ApiException.badRequest("Er zijn twee bossbars met de naam " + bar.id() + ".");
                }
                list.add(bar);
            }
            // Een nieuwe sectie op dezelfde plek; de uitleg (commentaar) erboven terugzetten.
            List<String> comments = yaml.getComments("bars");
            ConfigurationSection section = yaml.createSection("bars");
            yaml.setComments("bars", comments);
            for (BarDefinition bar : list) {
                bar.write(section.createSection(bar.id()));
            }
        }
        if (body.has("rotations")) {
            JsonArray array = array(body.get("rotations"), "wisselingen");
            if (array.size() > MAX_ROTATIONS) {
                throw ApiException.badRequest("Hooguit " + MAX_ROTATIONS + " wisselingen (zoveel bossbars tegelijk).");
            }
            List<Map<String, Object>> rotations = new ArrayList<>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    throw ApiException.badRequest("Ongeldige wisseling.");
                }
                rotations.add(rotation(element.getAsJsonObject(), ids).toMap());
            }
            List<String> comments = yaml.getComments("rotations");
            yaml.set("rotations", rotations);
            yaml.setComments("rotations", comments);
        } else if (body.has("bars")) {
            // Weggehaalde bossbars ook uit de wisselingen halen.
            List<Map<String, Object>> rotations = new ArrayList<>();
            for (Map<?, ?> map : yaml.getMapList("rotations")) {
                Rotation rotation = Rotation.parse(map);
                rotations.add(new Rotation(rotation.name(), rotation.bars().stream().filter(ids::contains).toList(),
                        rotation.loop(), rotation.onJoin(), rotation.enabled()).toMap());
            }
            yaml.set("rotations", rotations);
        }
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "bossbars", null, body.has("bars") ? "bossbars" : body.has("rotations") ? "wisselingen" : "instellingen");
        return overview(request);
    }

    private static JsonArray array(JsonElement element, String what) throws ApiException {
        if (element == null || !element.isJsonArray()) {
            throw ApiException.badRequest("Ongeldige lijst met " + what + ".");
        }
        return element.getAsJsonArray();
    }

    private static List<String> names(JsonElement element, String what) throws ApiException {
        List<String> names = new ArrayList<>();
        for (JsonElement item : array(element, what)) {
            String name = item.getAsString().trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > 64 || names.size() >= 50) {
                throw ApiException.badRequest("Te veel of te lange namen bij " + what + ".");
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static String text(JsonObject object, String key, String fallback, int max) throws ApiException {
        JsonElement element = object.get(key);
        String value = element == null || element.isJsonNull() ? fallback : element.getAsString();
        if (value.length() > max) {
            throw ApiException.badRequest("'" + key + "' is te lang.");
        }
        return value;
    }

    private static double number(JsonObject object, String key, double fallback, double min, double max, String label) throws ApiException {
        JsonElement element = object.get(key);
        double value;
        try {
            value = element == null || element.isJsonNull() ? fallback : element.getAsDouble();
        } catch (RuntimeException e) {
            throw ApiException.badRequest(label + " moet een getal zijn.");
        }
        if (Double.isNaN(value) || value < min || value > max) {
            throw ApiException.badRequest(label + " moet tussen " + format(min) + " en " + format(max) + " liggen.");
        }
        return value;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Een bossbar uit het paneel, streng gecontroleerd. */
    private static BarDefinition bar(JsonObject object) throws ApiException {
        String id = text(object, "id", "", 32).trim().toLowerCase(Locale.ROOT);
        if (!BarDefinition.ID.matcher(id).matches()) {
            throw ApiException.badRequest("De naam van een bossbar is 1 tot 32 tekens: kleine letters, cijfers, - en _ (" + id + ").");
        }
        List<String> frames = new ArrayList<>();
        for (JsonElement element : array(object.get("frames"), "teksten")) {
            String frame = element.getAsString();
            if (frame.length() > BarDefinition.MAX_TEXT) {
                throw ApiException.badRequest("Een tekst van " + id + " is te lang (hooguit " + BarDefinition.MAX_TEXT + " tekens).");
            }
            frames.add(frame);
        }
        if (frames.isEmpty() || frames.size() > BarDefinition.MAX_FRAMES) {
            throw ApiException.badRequest(id + ": 1 tot " + BarDefinition.MAX_FRAMES + " teksten.");
        }
        String color = text(object, "color", "yellow", 20).toLowerCase(Locale.ROOT);
        String style = text(object, "style", "progress", 20).toLowerCase(Locale.ROOT);
        String progress = text(object, "progress", "static", 20).toLowerCase(Locale.ROOT);
        String effect = text(object, "effect", "none", 20).toLowerCase(Locale.ROOT);
        if (!BarDefinition.COLORS.contains(color) || !BarDefinition.STYLES.contains(style)
                || !ProgressMode.exists(progress) || !TextEffect.exists(effect)) {
            throw ApiException.badRequest(id + ": onbekende kleur, stijl, balk of effect.");
        }
        List<TextColor> colors = new ArrayList<>();
        JsonElement colorList = object.get("effectColors");
        if (colorList != null && !colorList.isJsonNull()) {
            for (JsonElement element : array(colorList, "effectkleuren")) {
                String hex = element.getAsString().trim();
                if (!HEX.matcher(hex).matches() || colors.size() >= 8) {
                    throw ApiException.badRequest(id + ": effectkleuren zijn hooguit 8 kleuren als #RRGGBB.");
                }
                colors.add(TextColor.fromHexString(hex));
            }
        }
        String permission = text(object, "permission", "", 100).trim();
        if (!PERMISSION.matcher(permission).matches()) {
            throw ApiException.badRequest(id + ": ongeldige permissie.");
        }
        List<String> worlds = new ArrayList<>();
        for (String world : names(object.has("worlds") ? object.get("worlds") : new JsonArray(), "werelden")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        List<String> disabled = new ArrayList<>();
        for (String world : names(object.has("disabledWorlds") ? object.get("disabledWorlds") : new JsonArray(), "werelden")) {
            disabled.add(world.toLowerCase(Locale.ROOT));
        }
        double frameSeconds = number(object, "frameSeconds", 1, 0.1, 600, "Seconden per tekst");
        double value = number(object, "value", 100, 0, 100, "Hoe vol (%)");
        double cycle = number(object, "cycleSeconds", 5, 0.5, 3600, "De duur van één ronde");
        double duration = number(object, "duration", 10, 0, 86400, "Hoe lang (seconden)");
        double speed = number(object, "effectSpeed", 1, 0.1, 5, "De snelheid van het effect");
        return new BarDefinition(id, List.copyOf(frames), (int) Math.max(2, Math.round(frameSeconds * 20)),
                BossBar.Color.valueOf(color.toUpperCase(Locale.ROOT)), BossBar.Overlay.valueOf(style.toUpperCase(Locale.ROOT)),
                ProgressMode.find(progress), (float) (value / 100.0),
                text(object, "progressValue", "<online>", 200), text(object, "progressMax", "<max>", 200),
                (int) Math.round(cycle * 20), (int) Math.round(duration * 20), TextEffect.find(effect), List.copyOf(colors), speed,
                permission, List.copyOf(worlds), List.copyOf(disabled));
    }

    private static Rotation rotation(JsonObject object, Set<String> ids) throws ApiException {
        String name = text(object, "name", "", 40).trim();
        List<String> bars = new ArrayList<>();
        for (JsonElement element : array(object.get("bars"), "bossbars")) {
            String id = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (!ids.contains(id)) {
                throw ApiException.badRequest("De wisseling '" + name + "' noemt een bossbar die niet bestaat: " + id + ".");
            }
            bars.add(id);
        }
        if (bars.size() > Rotation.MAX_BARS) {
            throw ApiException.badRequest("Hooguit " + Rotation.MAX_BARS + " bossbars in een wisseling.");
        }
        boolean loop = !object.has("loop") || object.get("loop").getAsBoolean();
        boolean join = "join".equals(text(object, "start", "always", 10));
        boolean enabled = !object.has("enabled") || object.get("enabled").getAsBoolean();
        return new Rotation(name, List.copyOf(bars), loop, join, enabled);
    }
}
