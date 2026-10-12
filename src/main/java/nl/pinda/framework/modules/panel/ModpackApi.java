package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.modules.modpack.ClientReport;
import nl.pinda.framework.modules.modpack.ModpackModule;
import nl.pinda.framework.modules.modpack.ModpackModule.PlayerState;
import nl.pinda.framework.modules.modpack.ModpackSettings;
import nl.pinda.framework.modules.modpack.ModpackSettings.Mode;
import nl.pinda.framework.modules.modpack.ModpackSettings.Required;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * De modpack-lock in het paneel: de lock aan/uit, welke mods verplicht zijn, de meldingen,
 * uitzonderingen, en per speler welke mods hij heeft en wat er ontbreekt.
 */
final class ModpackApi extends PanelApi {

    private static final String FILE = "modules/modpack.yml";
    private static final int MAX_SEEN = 400;

    private final ConfigEditor editor;

    ModpackApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/modpack", PanelUser.PLAYERS, this::overview);
        server.get("/api/modpack/players/{uuid}", PanelUser.PLAYERS, this::player);
        server.post("/api/modpack/settings", PanelUser.CONFIG, this::saveSettings);
        server.post("/api/modpack/required", PanelUser.CONFIG, this::saveRequired);
        server.post("/api/modpack/required/{id}/delete", PanelUser.CONFIG, this::deleteRequired);
        server.post("/api/modpack/exempt", PanelUser.CONFIG, this::addExempt);
        server.post("/api/modpack/exempt/{uuid}/delete", PanelUser.CONFIG, this::deleteExempt);
    }

    private ModpackModule modpack() throws ApiException {
        return require(ModpackModule.class, "modpack");
    }

    // ============================================================ bekijken

    private Object overview(PanelRequest request) throws Exception {
        ModpackModule modpack = modpack();
        boolean canEdit = request.user().has(PanelUser.CONFIG);
        return sync(() -> {
            ModpackSettings settings = modpack.settings();
            List<PlayerState> online = modpack.online();
            List<Map<String, Object>> players = new ArrayList<>();
            for (PlayerState state : online) {
                players.add(state(state));
            }
            List<Map<String, Object>> required = new ArrayList<>();
            for (Required mod : settings.required()) {
                long having = online.stream().filter(state -> state.report() != null && state.report().has(mod.id())).count();
                required.add(map("id", mod.id(), "name", mod.name(), "online", having));
            }
            List<Map<String, Object>> exempt = new ArrayList<>();
            settings.exempt().forEach((uuid, name) -> exempt.add(map("uuid", uuid.toString(), "name", name)));
            exempt.sort(Comparator.comparing(entry -> String.valueOf(entry.get("name")).toLowerCase(Locale.ROOT)));
            Map<String, Object> result = map(
                    "mode", settings.mode().key(),
                    "url", settings.url(),
                    "timeout", settings.timeoutSeconds(),
                    "remind", settings.remindMinutes(),
                    "messages", map("soft", settings.softMessage(), "kick", settings.kickMessage()),
                    "required", required,
                    "exempt", exempt,
                    "players", players,
                    "configPhase", modpack.configPhase(),
                    "canEdit", canEdit);
            if (canEdit) {
                List<Map<String, Object>> seen = new ArrayList<>();
                for (ModpackModule.SeenMod mod : modpack.seenMods()) {
                    if (seen.size() >= MAX_SEEN) {
                        break;
                    }
                    seen.add(map("id", mod.id(), "name", mod.name(), "version", mod.version(), "players", mod.players()));
                }
                result.put("seen", seen);
            }
            return result;
        });
    }

    private static Map<String, Object> state(PlayerState state) {
        ClientReport report = state.report();
        return map("uuid", state.uuid().toString(), "name", state.name(),
                "status", state.status().name().toLowerCase(Locale.ROOT),
                "exempt", state.exempt(),
                "brand", state.brand(),
                "loader", report == null ? null : report.loader() + " " + report.loaderVersion(),
                "mods", report == null ? null : report.topLevel().size(),
                "missing", labels(state.missing()),
                "joined", state.joined());
    }

    private static List<String> labels(List<Required> mods) {
        List<String> labels = new ArrayList<>();
        for (Required mod : mods) {
            labels.add(mod.label());
        }
        return labels;
    }

    /** De modlijst van één speler: live als hij online is, anders de laatst bekende. */
    private Object player(PanelRequest request) throws Exception {
        ModpackModule modpack = modpack();
        UUID uuid;
        try {
            uuid = UUID.fromString(request.param("uuid"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Ongeldige speler.");
        }
        Map<String, Object> live = sync(() -> {
            Player player = plugin.getServer().getPlayer(uuid);
            PlayerState state = player == null ? null : modpack.state(player);
            if (state == null) {
                return null;
            }
            Map<String, Object> result = state(state);
            result.put("online", true);
            result.put("updated", state.report() == null ? null : state.report().time());
            result.put("report", report(modpack, state.report()));
            return result;
        });
        if (live != null) {
            return live;
        }
        ModpackModule.Stored stored = await(modpack.stored(uuid));
        if (stored == null) {
            throw ApiException.notFound("Van deze speler is geen modlijst bekend: hij heeft nooit met de PindaCraft-mod gespeeld.");
        }
        return sync(() -> {
            List<Required> missing = modpack.missing(stored.report());
            return map("uuid", uuid.toString(), "name", stored.name(), "online", false,
                    "status", missing.isEmpty() ? "ok" : "missing", "exempt", modpack.settings().exempt().containsKey(uuid),
                    "brand", stored.brand(), "missing", labels(missing), "updated", stored.updated(),
                    "report", report(modpack, stored.report()));
        });
    }

    private static Map<String, Object> report(ModpackModule modpack, ClientReport report) {
        if (report == null) {
            return null;
        }
        List<String> required = modpack.settings().required().stream().map(Required::id).toList();
        List<Map<String, Object>> mods = new ArrayList<>();
        for (ClientReport.Mod mod : report.mods()) {
            mods.add(map("id", mod.id(), "version", mod.version(), "name", mod.name(), "included", mod.included(),
                    "required", required.contains(mod.id())));
        }
        return map("loader", report.loader(), "loaderVersion", report.loaderVersion(), "minecraft", report.minecraft(),
                "client", report.client(), "mods", mods);
    }

    /** Voor het spelersprofiel. Null als er niets bekend is. */
    static Map<String, Object> profile(PanelApi api, ModpackModule modpack, UUID uuid) throws Exception {
        Map<String, Object> live = api.sync(() -> {
            Player player = api.plugin.getServer().getPlayer(uuid);
            PlayerState state = player == null ? null : modpack.state(player);
            if (state == null) {
                return null;
            }
            Map<String, Object> result = state(state);
            result.put("online", true);
            result.put("updated", state.report() == null ? null : state.report().time());
            return result;
        });
        if (live != null) {
            return live;
        }
        ModpackModule.Stored stored = await(modpack.stored(uuid));
        if (stored == null) {
            return null;
        }
        return api.sync(() -> {
            List<Required> missing = modpack.missing(stored.report());
            return map("uuid", uuid.toString(), "online", false, "status", missing.isEmpty() ? "ok" : "missing",
                    "exempt", modpack.settings().exempt().containsKey(uuid), "brand", stored.brand(),
                    "loader", stored.report().loader() + " " + stored.report().loaderVersion(),
                    "mods", stored.report().topLevel().size(), "missing", labels(missing), "updated", stored.updated());
        });
    }

    // ============================================================ aanpassen

    private Object saveSettings(PanelRequest request) throws Exception {
        modpack();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        List<String> changed = new ArrayList<>();
        if (body.has("mode")) {
            String value = request.string("mode", "Kies of er een lock op zit.");
            if (!value.equals("off") && !value.equals("soft") && !value.equals("hard")) {
                throw ApiException.badRequest("Kies uit: geen lock, softlock of hardlock.");
            }
            yaml.set("mode", value);
            changed.add("lock " + value);
        }
        if (body.has("url")) {
            String url = request.optString("url");
            url = url == null ? "" : url;
            if (!ModpackSettings.validUrl(url)) {
                throw ApiException.badRequest("Vul een link in die begint met https://, bijv. https://modrinth.com/modpack/jouw-modpack");
            }
            yaml.set("modpack-url", url);
            changed.add("link");
        }
        if (body.has("timeout")) {
            yaml.set("timeout-seconds", number(body.get("timeout"), 3, 60, "De wachttijd"));
            changed.add("wachttijd");
        }
        if (body.has("remind")) {
            yaml.set("remind-minutes", number(body.get("remind"), 0, 1440, "De herinnering"));
            changed.add("herinnering");
        }
        for (String kind : List.of("soft", "kick")) {
            if (body.has(kind)) {
                String text = text(body.get(kind));
                if (text.length() > ModpackSettings.MAX_MESSAGE) {
                    throw ApiException.badRequest("De melding is te lang (hooguit " + ModpackSettings.MAX_MESSAGE + " tekens).");
                }
                yaml.set("messages." + kind, text);
                changed.add(kind.equals("soft") ? "melding" : "kickmelding");
            }
        }
        if (changed.isEmpty()) {
            throw ApiException.badRequest("Er is niets veranderd.");
        }
        write(yaml);
        module.log().add(request, "modpack-lock aangepast", null, String.join(", ", changed));
        return overview(request);
    }

    private Object saveRequired(PanelRequest request) throws Exception {
        modpack();
        String raw = request.string("id", "Vul de mod-ID in, bijv. voicechat.");
        String id = raw.toLowerCase(Locale.ROOT);
        if (!ClientReport.ID.matcher(id).matches()) {
            throw ApiException.badRequest("Een mod-ID bestaat uit kleine letters, cijfers, - en _ (bijv. voicechat).");
        }
        if (id.equals(ModpackModule.COMPANION)) {
            throw ApiException.badRequest("De PindaCraft-mod is altijd nodig; die hoef je niet toe te voegen.");
        }
        String name = request.optString("name");
        if (name != null && name.length() > ModpackSettings.MAX_NAME) {
            throw ApiException.badRequest("De naam is te lang (hooguit " + ModpackSettings.MAX_NAME + " tekens).");
        }
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection required = section(yaml, "required");
        if (!required.contains(id) && required.getKeys(false).size() >= ModpackSettings.MAX_REQUIRED) {
            throw ApiException.badRequest("Hooguit " + ModpackSettings.MAX_REQUIRED + " verplichte mods.");
        }
        boolean isNew = !required.contains(id);
        required.set(id, name == null ? "" : name);
        write(yaml);
        module.log().add(request, isNew ? "verplichte mod toegevoegd" : "verplichte mod aangepast", id, name);
        return overview(request);
    }

    private Object deleteRequired(PanelRequest request) throws Exception {
        modpack();
        String id = request.param("id");
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection required = yaml.getConfigurationSection("required");
        if (id == null || required == null || !required.contains(id)) {
            throw ApiException.notFound("Deze mod is niet (meer) verplicht.");
        }
        required.set(id, null);
        write(yaml);
        module.log().add(request, "verplichte mod verwijderd", id, null);
        return overview(request);
    }

    private Object addExempt(PanelRequest request) throws Exception {
        modpack();
        String name = request.string("name", "Vul de naam van de speler in.");
        KnownPlayer known = await(plugin.players().findKnown(name));
        if (known == null) {
            throw ApiException.notFound("Speler '" + name + "' is hier nog nooit geweest.");
        }
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection exempt = section(yaml, "exempt");
        if (!exempt.contains(known.uuid().toString()) && exempt.getKeys(false).size() >= ModpackSettings.MAX_EXEMPT) {
            throw ApiException.badRequest("Hooguit " + ModpackSettings.MAX_EXEMPT + " uitzonderingen.");
        }
        exempt.set(known.uuid().toString(), known.name());
        write(yaml);
        module.log().add(request, "modpack-uitzondering toegevoegd", known.name(), null);
        return overview(request);
    }

    private Object deleteExempt(PanelRequest request) throws Exception {
        modpack();
        String uuid = request.param("uuid");
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection exempt = yaml.getConfigurationSection("exempt");
        if (uuid == null || exempt == null || !exempt.contains(uuid)) {
            throw ApiException.notFound("Deze speler is geen uitzondering (meer).");
        }
        String name = exempt.getString(uuid, uuid);
        exempt.set(uuid, null);
        write(yaml);
        module.log().add(request, "modpack-uitzondering verwijderd", name, null);
        return overview(request);
    }

    // ============================================================ hulpjes

    /** Een sectie die er altijd is (met de uitleg erboven behouden). */
    private static ConfigurationSection section(YamlConfiguration yaml, String path) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            List<String> comments = yaml.getComments(path);
            section = yaml.createSection(path);
            yaml.setComments(path, comments);
        }
        return section;
    }

    private void write(YamlConfiguration yaml) throws Exception {
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
    }

    private static int number(JsonElement element, int min, int max, String what) throws ApiException {
        try {
            double value = element.getAsDouble();
            if (Double.isNaN(value) || value < min || value > max || value != Math.rint(value)) {
                throw ApiException.badRequest(what + " moet een heel getal zijn van " + min + " tot " + max + ".");
            }
            return (int) value;
        } catch (UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw ApiException.badRequest(what + " moet een getal zijn.");
        }
    }

    private static String text(JsonElement element) throws ApiException {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString().replace("\r\n", "\n");
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldige tekst.");
        }
    }
}
