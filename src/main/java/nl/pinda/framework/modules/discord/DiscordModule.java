package nl.pinda.framework.modules.discord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.event.PindaNotifyEvent;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Discord via webhooks: een statusbericht dat zichzelf bijwerkt en meldingen voor staff.
 * Er is geen bot nodig.
 */
public final class DiscordModule extends PindaModule implements Listener {

    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9_-]+");
    private static final String STATUS_MESSAGE = "discord-status.message";
    private static final String STATUS_WEBHOOK = "discord-status.webhook";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Gson gson = new Gson();

    public DiscordModule(PindaFramework plugin) {
        super(plugin, "discord");
    }

    @Override
    protected void onEnable() {
        listen(this);
        schedule();
        if (staffEvent("server-start-stop")) {
            sendStaff(embed(text("discord.staff.started"), null, color("#7BE495"), new JsonArray()));
        }
    }

    @Override
    protected void onDisable() {
        try {
            if (statusEnabled()) {
                updateStatus(false).get(3, TimeUnit.SECONDS);
            }
            if (staffEvent("server-start-stop")) {
                sendStaff(embed(text("discord.staff.stopped"), null, color("#FF6B6B"), new JsonArray())).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.FINE, "Discord-bericht bij stoppen mislukt", e);
        }
    }

    @Override
    protected void onReload() {
        cancelTasks();
        schedule();
    }

    private void schedule() {
        if (statusEnabled()) {
            long interval = Math.max(30, config().getInt("status.interval", 60)) * 20L;
            repeat(() -> updateStatus(true), 40L, interval);
        }
    }

    // ============================================================ instellingen

    /** Is dit een geldige Discord-webhook? */
    public static boolean validWebhook(String url) {
        return url != null && WEBHOOK.matcher(clean(url)).matches();
    }

    private static String clean(String url) {
        String trimmed = url == null ? "" : url.trim();
        int query = trimmed.indexOf('?');
        return query >= 0 ? trimmed.substring(0, query) : trimmed;
    }

    private boolean statusEnabled() {
        return config().getBoolean("status.enabled", false) && validWebhook(config().getString("status.webhook"));
    }

    private boolean staffEvent(String event) {
        return config().getBoolean("staff.enabled", false) && validWebhook(config().getString("staff.webhook"))
                && config().getBoolean("staff.events." + event, true);
    }

    private String text(String key, String... pairs) {
        String raw = plugin.lang().raw(plugin.lang().defaultLanguage(), key);
        String result = raw == null ? key : raw;
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            result = result.replace("<" + pairs[index] + ">", pairs[index + 1] == null ? "" : pairs[index + 1]);
        }
        return result.replace("<server>", serverName());
    }

    private String serverName() {
        return PlainTextComponentSerializer.plainText().serialize(
                plugin.lang().parse(plugin.mainConfig().getString("server-name", "PindaCraft")));
    }

    private static int color(String hex) {
        try {
            return Integer.parseInt(hex.trim().replace("#", ""), 16);
        } catch (RuntimeException e) {
            return 0xFFC857;
        }
    }

    /** Discord-opmaaktekens in spelernamen onschadelijk maken (Henk_Bouwt zou anders cursief worden). */
    private static String escape(String text) {
        return text == null ? "" : text.replaceAll("([_*~`|>\\\\])", "\\\\$1");
    }

    // ============================================================ berichten opbouwen

    private JsonObject embed(String title, String description, int color, JsonArray fields) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        if (description != null && !description.isBlank()) {
            embed.addProperty("description", description);
        }
        embed.addProperty("color", color);
        if (!fields.isEmpty()) {
            embed.add("fields", fields);
        }
        JsonObject footer = new JsonObject();
        footer.addProperty("text", serverName());
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());
        return embed;
    }

    private static void field(JsonArray fields, String name, String value, boolean inline) {
        if (value == null || value.isBlank()) {
            return;
        }
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value.length() > 1024 ? value.substring(0, 1021) + "..." : value);
        field.addProperty("inline", inline);
        fields.add(field);
    }

    private JsonObject message(JsonObject embed) {
        JsonObject payload = new JsonObject();
        String username = config().getString("username", "");
        if (username != null && !username.isBlank()) {
            payload.addProperty("username", username);
        }
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        return payload;
    }

    /** Het statusbericht (moet op de hoofdthread gemaakt worden). */
    private JsonObject statusEmbed(boolean online) {
        JsonArray fields = new JsonArray();
        StringBuilder description = new StringBuilder(text(online ? "discord.status.online" : "discord.status.offline"));
        String address = config().getString("status.address", "");
        if (online && address != null && !address.isBlank()) {
            description.append('\n').append(text("discord.status.address", "address", address.trim()));
        }
        if (online) {
            int count = plugin.getServer().getOnlinePlayers().size();
            field(fields, text("discord.status.players"), count + "/" + plugin.getServer().getMaxPlayers(), true);
            field(fields, text("discord.status.tps"), String.format(Locale.ROOT, "%.1f", Math.min(20.0, plugin.getServer().getTPS()[0])), true);
            field(fields, text("discord.status.version"), plugin.getServer().getMinecraftVersion(), true);
            long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime() / 1000;
            field(fields, text("discord.status.since"), "<t:" + startedAt + ":R>", true);
            if (config().getBoolean("status.show-players", true)) {
                List<String> names = new ArrayList<>();
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    names.add(escape(player.getName()));
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                field(fields, text("discord.status.player-list"), names.isEmpty() ? text("discord.status.nobody") : String.join(", ", names), false);
            }
        }
        JsonObject embed = embed(serverName(), description.toString(),
                color(config().getString(online ? "status.color-online" : "status.color-offline", online ? "#7BE495" : "#FF6B6B")), fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", text("discord.status.footer"));
        embed.add("footer", footer);
        return embed;
    }

    // ============================================================ versturen

    /** Werkt het statusbericht bij, of plaatst een nieuw bericht als het nog niet bestaat. Hoofdthread. */
    public CompletableFuture<Void> updateStatus(boolean online) {
        String url = clean(config().getString("status.webhook"));
        if (!validWebhook(url)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Geen geldige webhook ingesteld."));
        }
        JsonObject payload = message(statusEmbed(online));
        String hash = Integer.toHexString(url.hashCode());
        String messageId = hash.equals(plugin.serverData().get(STATUS_WEBHOOK)) ? plugin.serverData().get(STATUS_MESSAGE) : null;
        if (messageId == null) {
            return postStatus(url, hash, payload);
        }
        return send("PATCH", url + "/messages/" + messageId, payload).thenCompose(response -> {
            if (response.statusCode() == 404) {
                return postStatus(url, hash, payload);
            }
            check(response);
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Void> postStatus(String url, String hash, JsonObject payload) {
        return send("POST", url + "?wait=true", payload).thenAccept(response -> {
            check(response);
            String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
            plugin.serverData().set(STATUS_WEBHOOK, hash);
            plugin.serverData().set(STATUS_MESSAGE, id);
        });
    }

    /** Stuurt een melding naar het staffkanaal. */
    public CompletableFuture<Void> sendStaff(JsonObject embed) {
        String url = clean(config().getString("staff.webhook"));
        if (!validWebhook(url)) {
            return CompletableFuture.completedFuture(null);
        }
        return send("POST", url, message(embed)).thenAccept(DiscordModule::check);
    }

    /** Stuurt een testbericht naar een webhook (vanuit het paneel). */
    public CompletableFuture<Void> test(String webhook, String by) {
        String url = clean(webhook);
        if (!validWebhook(url)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Dit is geen geldige Discord-webhook."));
        }
        JsonObject embed = embed(text("discord.staff.test"), text("discord.staff.test-body", "player", escape(by)),
                color("#FFC857"), new JsonArray());
        return send("POST", url, message(embed)).thenAccept(DiscordModule::check);
    }

    private CompletableFuture<HttpResponse<String>> send(String method, String url, JsonObject payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "PindaFramework (Minecraft-plugin)")
                .method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Discord niet bereikbaar: " + error.getMessage());
            } else if (response.statusCode() >= 300 && response.statusCode() != 404) {
                plugin.getLogger().warning("Discord gaf foutcode " + response.statusCode() + " terug.");
            }
        });
    }

    private static void check(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code == 401 || code == 403 || code == 404) {
            throw new IllegalStateException("Discord kent deze webhook niet (meer). Maak een nieuwe aan.");
        }
        if (code == 429) {
            throw new IllegalStateException("Discord vraagt even te wachten (te veel berichten).");
        }
        if (code >= 300) {
            throw new IllegalStateException("Discord gaf foutcode " + code + ".");
        }
    }

    // ============================================================ meldingen voor staff

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNotify(PindaNotifyEvent event) {
        JsonArray fields = new JsonArray();
        String player = escape(event.get("player"));
        String actor = escape(event.get("actor"));
        JsonObject embed;
        switch (event.type()) {
            case "punishment" -> {
                if (!staffEvent("punishments")) {
                    return;
                }
                String kind = event.get("kind");
                field(fields, text("discord.staff.by"), actor, true);
                field(fields, text("discord.staff.until"), event.get("expires"), true);
                field(fields, text("discord.staff.reason"), escape(event.get("reason")), false);
                int color = kind.equals("ban") ? 0xFF6B6B : kind.equals("warn") ? 0x4D9DFF : 0xFFB347;
                embed = embed(text("discord.staff.punishment-" + kind, "player", player), null, color, fields);
            }
            case "revoke" -> {
                if (!staffEvent("revokes")) {
                    return;
                }
                field(fields, text("discord.staff.by"), actor, true);
                embed = embed(text("discord.staff.revoke-" + event.get("kind"), "player", player), null, 0x7BE495, fields);
            }
            case "rank" -> {
                if (!staffEvent("rank-changes")) {
                    return;
                }
                field(fields, text("discord.staff.by"), actor, true);
                embed = embed(text("discord.staff.rank", "player", player, "rank", escape(event.get("rank"))), null, 0xB17CFF, fields);
            }
            case "panel-login" -> {
                if (!staffEvent("panel-logins")) {
                    return;
                }
                field(fields, text("discord.staff.ip"), "||" + event.get("ip") + "||", true);
                embed = embed(text("discord.staff.panel-login", "player", player), null, 0xFFC857, fields);
            }
            case "panel-action" -> {
                if (!staffEvent("panel-actions")) {
                    return;
                }
                field(fields, text("discord.staff.target"), escape(event.get("target")), true);
                field(fields, text("discord.staff.details"), escape(event.get("details")), false);
                embed = embed(text("discord.staff.panel-action", "player", player, "action", escape(event.get("action"))),
                        null, 0xE9724C, fields);
            }
            default -> {
                return;
            }
        }
        sendStaff(embed);
    }
}
