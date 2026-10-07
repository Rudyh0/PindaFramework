package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.discord.DiscordModule;
import org.bukkit.configuration.file.YamlConfiguration;

/** De Discord-koppeling instellen en testen vanuit het paneel. */
final class DiscordApi extends PanelApi {

    private static final String FILE = "modules/discord.yml";
    private static final List<String> EVENTS = List.of("punishments", "revokes", "rank-changes", "panel-logins",
            "panel-actions", "server-start-stop");

    private final ConfigEditor editor;

    DiscordApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/discord", PanelUser.CONFIG, request -> settings());
        server.post("/api/discord", PanelUser.CONFIG, this::save);
        server.post("/api/discord/test", PanelUser.CONFIG, this::test);
    }

    private Object settings() throws Exception {
        YamlConfiguration yaml = editor.read(FILE);
        Map<String, Object> events = new LinkedHashMap<>();
        for (String event : EVENTS) {
            events.put(event, yaml.getBoolean("staff.events." + event, true));
        }
        return map(
                "moduleEnabled", enabled(DiscordModule.class) != null,
                "username", yaml.getString("username", ""),
                "status", map("enabled", yaml.getBoolean("status.enabled", false),
                        "webhook", yaml.getString("status.webhook", ""),
                        "interval", yaml.getInt("status.interval", 60),
                        "showPlayers", yaml.getBoolean("status.show-players", true),
                        "address", yaml.getString("status.address", "")),
                "staff", map("enabled", yaml.getBoolean("staff.enabled", false),
                        "webhook", yaml.getString("staff.webhook", ""),
                        "events", events));
    }

    private Object save(PanelRequest request) throws Exception {
        YamlConfiguration yaml = editor.read(FILE);
        JsonObject body = request.body();
        if (body.has("username")) {
            yaml.set("username", body.get("username").getAsString().trim());
        }
        JsonElement status = body.get("status");
        if (status != null && status.isJsonObject()) {
            JsonObject s = status.getAsJsonObject();
            String webhook = s.has("webhook") ? s.get("webhook").getAsString().trim() : yaml.getString("status.webhook", "");
            boolean enabled = s.has("enabled") && s.get("enabled").getAsBoolean();
            if (enabled && !DiscordModule.validWebhook(webhook)) {
                throw ApiException.badRequest("De webhook voor de serverstatus is geen geldige Discord-webhook.");
            }
            yaml.set("status.enabled", enabled);
            yaml.set("status.webhook", webhook);
            if (s.has("interval")) {
                yaml.set("status.interval", Math.max(30, s.get("interval").getAsInt()));
            }
            if (s.has("showPlayers")) {
                yaml.set("status.show-players", s.get("showPlayers").getAsBoolean());
            }
            if (s.has("address")) {
                yaml.set("status.address", s.get("address").getAsString().trim());
            }
        }
        JsonElement staff = body.get("staff");
        if (staff != null && staff.isJsonObject()) {
            JsonObject s = staff.getAsJsonObject();
            String webhook = s.has("webhook") ? s.get("webhook").getAsString().trim() : yaml.getString("staff.webhook", "");
            boolean enabled = s.has("enabled") && s.get("enabled").getAsBoolean();
            if (enabled && !DiscordModule.validWebhook(webhook)) {
                throw ApiException.badRequest("De webhook voor staffmeldingen is geen geldige Discord-webhook.");
            }
            yaml.set("staff.enabled", enabled);
            yaml.set("staff.webhook", webhook);
            JsonElement events = s.get("events");
            if (events != null && events.isJsonObject()) {
                for (String event : EVENTS) {
                    if (events.getAsJsonObject().has(event)) {
                        yaml.set("staff.events." + event, events.getAsJsonObject().get(event).getAsBoolean());
                    }
                }
            }
        }
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "discord", null, null);
        return settings();
    }

    private Object test(PanelRequest request) throws Exception {
        DiscordModule discord = require(DiscordModule.class, "discord");
        String webhook = request.string("webhook", "Vul eerst een webhook in.");
        try {
            await(discord.test(webhook, request.user().name()));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest(e.getMessage() == null ? "Versturen mislukt." : e.getMessage());
        }
        return null;
    }
}
