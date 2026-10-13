package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.sun.net.httpserver.Headers;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import nl.pinda.framework.modules.motd.MotdModule;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * De MOTD (het bericht in de serverlijst) en het servericoon. server.properties pas je aan via
 * de bestanden (bijv. in het dev-paneel); spelregels met /gamerule.
 */
final class ServerSettingsApi extends PanelApi {

    private final ConfigEditor editor;

    ServerSettingsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/motd", PanelUser.CONFIG, request -> motd());
        server.post("/api/motd", PanelUser.CONFIG, this::saveMotd);
        server.get("/api/server/icon", PanelUser.USE, this::icon);
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
