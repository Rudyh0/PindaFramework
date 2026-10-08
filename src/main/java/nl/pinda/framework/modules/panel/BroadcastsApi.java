package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import nl.pinda.framework.integration.Placeholders;
import nl.pinda.framework.modules.broadcasts.BroadcastsModule;
import org.bukkit.configuration.file.YamlConfiguration;

/** Automatische aankondigingen beheren: de berichten, hoe vaak, en meteen versturen. */
final class BroadcastsApi extends PanelApi {

    private static final String FILE = "modules/broadcasts.yml";
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_LENGTH = 1500;

    private final ConfigEditor editor;

    BroadcastsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/broadcasts", PanelUser.BROADCASTS, request -> overview());
        server.post("/api/broadcasts", PanelUser.BROADCASTS, this::save);
        server.post("/api/broadcasts/send", PanelUser.BROADCASTS, this::send);
    }

    private Object overview() throws Exception {
        BroadcastsModule broadcasts = enabled(BroadcastsModule.class);
        YamlConfiguration yaml = editor.read(FILE);
        List<String> messages = yaml.getStringList("messages");
        int online = plugin.getServer().getOnlinePlayers().size();
        int max = plugin.getServer().getMaxPlayers();
        String code = plugin.lang().defaultLanguage();
        List<String> previews = new ArrayList<>();
        for (String message : messages) {
            previews.add(broadcasts == null ? ComponentHtml.escape(message)
                    : ComponentHtml.render(broadcasts.render(null, "Rudyh0", code, message, online, max)));
        }
        return map("moduleEnabled", broadcasts != null,
                "intervalMinutes", yaml.getInt("interval-minutes", 10),
                "random", yaml.getBoolean("random", false),
                "minPlayers", yaml.getInt("min-players", 1),
                "sound", yaml.getBoolean("sound", true),
                "messages", messages, "previews", previews,
                "nextAt", broadcasts == null ? 0 : broadcasts.nextAt(),
                "online", online, "placeholderApi", Placeholders.available());
    }

    private Object save(PanelRequest request) throws Exception {
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        JsonElement messages = body.get("messages");
        if (messages != null && messages.isJsonArray()) {
            List<String> list = new ArrayList<>();
            for (JsonElement element : messages.getAsJsonArray()) {
                String text = element.getAsString().strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > MAX_LENGTH) {
                    throw ApiException.badRequest("Een aankondiging is te lang (maximaal " + MAX_LENGTH + " tekens).");
                }
                list.add(text);
            }
            if (list.size() > MAX_MESSAGES) {
                throw ApiException.badRequest("Maximaal " + MAX_MESSAGES + " aankondigingen.");
            }
            yaml.set("messages", list);
        }
        if (body.has("intervalMinutes")) {
            int minutes = body.get("intervalMinutes").getAsInt();
            if (minutes < 1 || minutes > 1440) {
                throw ApiException.badRequest("Het interval moet tussen 1 en 1440 minuten liggen.");
            }
            yaml.set("interval-minutes", minutes);
        }
        if (body.has("minPlayers")) {
            int players = body.get("minPlayers").getAsInt();
            if (players < 0 || players > 1000) {
                throw ApiException.badRequest("Ongeldig minimum aantal spelers.");
            }
            yaml.set("min-players", players);
        }
        if (body.has("random")) {
            yaml.set("random", body.get("random").getAsBoolean());
        }
        if (body.has("sound")) {
            yaml.set("sound", body.get("sound").getAsBoolean());
        }
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "aankondigingen", null, messages != null ? "berichten" : "instellingen");
        return overview();
    }

    private Object send(PanelRequest request) throws Exception {
        BroadcastsModule broadcasts = require(BroadcastsModule.class, "broadcasts");
        JsonObject body = request.body();
        String message;
        if (body.has("index")) {
            List<String> messages = editor.read(FILE).getStringList("messages");
            int index = body.get("index").getAsInt();
            if (index < 0 || index >= messages.size()) {
                throw ApiException.notFound("Deze aankondiging bestaat niet (meer).");
            }
            message = messages.get(index);
        } else {
            message = request.string("message", "Typ een aankondiging.").strip();
            if (message.length() > MAX_LENGTH) {
                throw ApiException.badRequest("De aankondiging is te lang.");
            }
        }
        String text = message;
        sync(() -> {
            broadcasts.send(text);
            return null;
        });
        module.log().add(request, "aankondiging verstuurd", null, text.length() > 120 ? text.substring(0, 117) + "..." : text);
        return map("sent", plugin.getServer().getOnlinePlayers().size());
    }
}
