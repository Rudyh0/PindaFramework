package nl.pinda.framework.modules.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.modules.duel.DuelModule;
import nl.pinda.framework.modules.duel.DuelSettings;
import nl.pinda.framework.modules.duel.DuelStore;
import nl.pinda.framework.modules.duel.Spot;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * De 1v1-gevechten in het paneel: de instellingen en de melding bij slaan, de arena, het
 * gevecht dat nu bezig is (af te breken), de beste spelers en de laatste gevechten.
 */
final class DuelApi extends PanelApi {

    private static final String FILE = "modules/duel.yml";
    private static final int MAX_LIST = 60;

    private final ConfigEditor editor;

    DuelApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/duel", PanelUser.PLAYERS, this::overview);
        server.post("/api/duel/settings", PanelUser.CONFIG, this::saveSettings);
        server.post("/api/duel/arena/{spot}/delete", PanelUser.CONFIG, this::clearSpot);
        server.post("/api/duel/abort", PanelUser.MODERATE, this::abort);
        server.post("/api/duel/players/{uuid}/reset", PanelUser.CONFIG, this::resetPlayer);
    }

    private DuelModule duel() throws ApiException {
        return require(DuelModule.class, "duel");
    }

    // ============================================================ bekijken

    private Object overview(PanelRequest request) throws Exception {
        DuelModule duel = duel();
        List<DuelStore.Stats> top = await(duel.store().top(10));
        List<DuelStore.Fight> recent = await(duel.store().history(null, 20));
        int total = await(duel.store().fightCount());
        PanelUser user = request.user();
        return sync(() -> {
            DuelSettings settings = duel.settings();
            EconomyService economy = economy();
            List<Map<String, Object>> topList = new ArrayList<>();
            for (DuelStore.Stats stats : top) {
                topList.add(stats(stats));
            }
            List<Map<String, Object>> fights = new ArrayList<>();
            for (DuelStore.Fight fight : recent) {
                fights.add(fight(fight, economy));
            }
            return map(
                    "settings", settings(settings),
                    "arena", map("spawn1", spot(settings.spawn1()), "spawn2", spot(settings.spawn2()),
                            "spectate", spot(settings.spectate()), "ready", settings.arenaReady(),
                            "sameWorld", settings.arenaReady() && settings.spawn1().world().equals(settings.spawn2().world()),
                            "area", area(duel.guarded()), "areaSet", settings.area() != null,
                            "protection", !settings.protect() ? "off" : duel.guarded() == null ? "none"
                                    : duel.viaWorldGuard() ? "worldguard" : "framework",
                            "worldGuard", duel.worldGuardPresent(), "region", "pinda_arena"),
                    "live", duel.status(),
                    "invites", duel.openInvites(),
                    "top", topList,
                    "recent", fights,
                    "total", total,
                    "economy", economy != null,
                    "canEdit", user.has(PanelUser.CONFIG),
                    "canAbort", user.has(PanelUser.MODERATE));
        });
    }

    private static Map<String, Object> settings(DuelSettings settings) {
        return map(
                "blockPvp", settings.blockPvp(),
                "pvpMessage", settings.pvpMessage(),
                "pvpCooldown", settings.pvpCooldownSeconds(),
                "inviteSeconds", settings.inviteSeconds(),
                "wagerSeconds", settings.wagerSeconds(),
                "rematchSeconds", settings.rematchSeconds(),
                "maxFightMinutes", settings.maxFightMinutes(),
                "arenaRadius", settings.arenaRadius(),
                "stakeMoney", settings.stakeMoney(),
                "stakeItems", settings.stakeItems(),
                "moneyStep", Money.toDouble(settings.moneyStep()),
                "maxMoney", Money.toDouble(settings.maxMoney()),
                "blockedItems", settings.blockedItems().stream().map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                "allowTotems", settings.allowTotems(),
                "forceSurvival", settings.forceSurvival(),
                "healOnStart", settings.healOnStart(),
                "allowedCommands", settings.allowedCommands().stream().sorted().toList(),
                "broadcastStart", settings.broadcastStart(),
                "broadcastResult", settings.broadcastResult(),
                "protect", settings.protect(),
                "protectMargin", settings.protectMargin());
    }

    private static Map<String, Object> spot(Spot spot) {
        if (spot == null) {
            return null;
        }
        return map("world", spot.world(), "x", spot.blockX(), "y", spot.blockY(), "z", spot.blockZ(),
                "yaw", Math.round(spot.yaw()), "text", spot.describe());
    }

    private static Map<String, Object> area(nl.pinda.framework.modules.duel.Area area) {
        if (area == null) {
            return null;
        }
        return map("world", area.world(), "min", List.of(area.minX(), area.minY(), area.minZ()),
                "max", List.of(area.maxX(), area.maxY(), area.maxZ()), "sizeX", area.sizeX(), "sizeZ", area.sizeZ(),
                "text", area.describe());
    }

    static Map<String, Object> stats(DuelStore.Stats stats) {
        return map("uuid", stats.uuid().toString(), "name", stats.name(), "wins", stats.wins(), "losses", stats.losses(),
                "draws", stats.draws(), "fights", stats.fights(), "ratio", Math.round(stats.ratio() * 100) / 100.0);
    }

    static Map<String, Object> fight(DuelStore.Fight fight, EconomyService economy) {
        return map("time", fight.time(),
                "winner", fight.winner() == null ? null : fight.winner().toString(), "winnerName", fight.winnerName(),
                "loser", fight.loser() == null ? null : fight.loser().toString(), "loserName", fight.loserName(),
                "draw", fight.draw(), "money", fight.money(), "moneyText", economy == null || fight.money() <= 0 ? null : economy.format(fight.money()),
                "items", fight.items(), "seconds", fight.seconds(), "reason", fight.reason());
    }

    /** Voor het spelersprofiel: de stand en de laatste gevechten. */
    static Map<String, Object> profile(PanelApi api, DuelModule duel, UUID uuid) throws Exception {
        DuelStore.Stats stats = await(duel.store().stats(uuid));
        List<DuelStore.Fight> fights = await(duel.store().history(uuid, 5));
        EconomyService economy = api.economy();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (DuelStore.Fight fight : fights) {
            recent.add(fight(fight, economy));
        }
        Map<String, Object> result = stats(stats);
        result.put("recent", recent);
        return result;
    }

    // ============================================================ aanpassen

    private Object saveSettings(PanelRequest request) throws Exception {
        duel();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        List<String> changed = new ArrayList<>();
        bool(body, yaml, "blockPvp", "block-pvp", "PvP tegenhouden", changed);
        bool(body, yaml, "stakeMoney", "stakes.money", "geld inzetten", changed);
        bool(body, yaml, "stakeItems", "stakes.items", "items inzetten", changed);
        bool(body, yaml, "allowTotems", "fight.allow-totems", "totems", changed);
        bool(body, yaml, "protect", "protection.enabled", "bescherming", changed);
        bool(body, yaml, "forceSurvival", "fight.force-survival", "survival", changed);
        bool(body, yaml, "healOnStart", "fight.heal-on-start", "genezen", changed);
        bool(body, yaml, "broadcastStart", "broadcast.start", "melding begin", changed);
        bool(body, yaml, "broadcastResult", "broadcast.result", "melding uitslag", changed);
        integer(body, yaml, "pvpCooldown", "pvp-message-cooldown", 1, 300, "De tijd tussen twee meldingen", changed);
        integer(body, yaml, "inviteSeconds", "invite-seconds", 10, 600, "De tijd voor een uitdaging", changed);
        integer(body, yaml, "wagerSeconds", "wager-seconds", 20, 900, "De tijd voor de inzet", changed);
        integer(body, yaml, "rematchSeconds", "rematch-seconds", 5, 120, "De tijd voor een rematch", changed);
        integer(body, yaml, "maxFightMinutes", "max-fight-minutes", 0, 60, "De maximale duur", changed);
        integer(body, yaml, "arenaRadius", "arena-radius", 0, 1000, "De grens van de arena", changed);
        integer(body, yaml, "protectMargin", "protection.margin", 0, 200, "De marge om de arena", changed);
        if (body.has("moneyStep")) {
            yaml.set("stakes.money-step", amount(body.get("moneyStep"), 0.01, "De stap van + en -"));
            changed.add("stap");
        }
        if (body.has("maxMoney")) {
            yaml.set("stakes.max-money", amount(body.get("maxMoney"), 0, "Het maximum"));
            changed.add("maximum");
        }
        if (body.has("pvpMessage")) {
            String text = text(body.get("pvpMessage"));
            if (text.length() > DuelSettings.MAX_MESSAGE) {
                throw ApiException.badRequest("De melding is te lang (hooguit " + DuelSettings.MAX_MESSAGE + " tekens).");
            }
            yaml.set("messages.pvp", text.replace("\n", "<newline>"));
            changed.add("melding");
        }
        if (body.has("allowedCommands")) {
            Set<String> commands = new LinkedHashSet<>();
            for (String entry : list(body.get("allowedCommands"), "commando's")) {
                String label = entry.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                if (label.isEmpty()) {
                    continue;
                }
                if (!label.matches("[a-z0-9_:.-]{1,40}")) {
                    throw ApiException.badRequest("'" + entry + "' is geen commando (zonder spaties, bijv. msg).");
                }
                commands.add(label);
            }
            if (commands.size() > 50) {
                throw ApiException.badRequest("Hooguit 50 commando's.");
            }
            yaml.set("fight.allowed-commands", new ArrayList<>(commands));
            changed.add("commando's");
        }
        if (body.has("blockedItems")) {
            Set<String> materials = new LinkedHashSet<>();
            for (String entry : list(body.get("blockedItems"), "items")) {
                if (entry.isBlank()) {
                    continue;
                }
                Material material = Material.matchMaterial(entry.strip());
                if (material == null || !material.isItem()) {
                    throw ApiException.badRequest("'" + entry + "' is geen item (gebruik de Engelse naam, bijv. dragon_egg).");
                }
                materials.add(material.name());
            }
            if (materials.size() > 200) {
                throw ApiException.badRequest("Hooguit 200 items.");
            }
            yaml.set("stakes.blocked-items", new ArrayList<>(materials));
            changed.add("geblokkeerde items");
        }
        if (changed.isEmpty()) {
            throw ApiException.badRequest("Er is niets veranderd.");
        }
        write(yaml);
        module.log().add(request, "1v1 aangepast", null, String.join(", ", changed));
        return overview(request);
    }

    private Object clearSpot(PanelRequest request) throws Exception {
        DuelModule duel = duel();
        String spot = request.param("spot");
        if (!"spawn1".equals(spot) && !"spawn2".equals(spot) && !"spectate".equals(spot) && !"area".equals(spot)) {
            throw ApiException.notFound("Deze plek bestaat niet.");
        }
        sync(() -> {
            duel.clearArena(spot);
            return null;
        });
        module.log().add(request, "1v1-arena: plek weggehaald", spot, null);
        return overview(request);
    }

    private Object abort(PanelRequest request) throws Exception {
        DuelModule duel = duel();
        boolean stopped = sync(() -> duel.abort(request.user().name()));
        if (!stopped) {
            throw ApiException.notFound("Er is geen 1v1 bezig.");
        }
        module.log().add(request, "1v1 afgebroken", null, null);
        return overview(request);
    }

    private Object resetPlayer(PanelRequest request) throws Exception {
        DuelModule duel = duel();
        UUID uuid;
        try {
            uuid = UUID.fromString(request.param("uuid"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Ongeldige speler.");
        }
        DuelStore.Stats before = await(duel.store().stats(uuid));
        await(duel.store().reset(uuid));
        module.log().add(request, "1v1-stand gewist", before.name() == null ? uuid.toString() : before.name(),
                before.wins() + " gewonnen, " + before.losses() + " verloren");
        return map("ok", true);
    }

    // ============================================================ hulpjes

    private void write(YamlConfiguration yaml) throws Exception {
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
    }

    private static void bool(JsonObject body, YamlConfiguration yaml, String field, String path, String label, List<String> changed)
            throws ApiException {
        if (!body.has(field)) {
            return;
        }
        JsonElement element = body.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw ApiException.badRequest("Ongeldige waarde voor " + label + ".");
        }
        yaml.set(path, element.getAsBoolean());
        changed.add(label);
    }

    private static void integer(JsonObject body, YamlConfiguration yaml, String field, String path, int min, int max, String what,
                                List<String> changed) throws ApiException {
        if (!body.has(field)) {
            return;
        }
        try {
            double value = body.get(field).getAsDouble();
            if (Double.isNaN(value) || value < min || value > max || value != Math.rint(value)) {
                throw ApiException.badRequest(what + " moet een heel getal zijn van " + min + " tot " + max + ".");
            }
            yaml.set(path, (int) value);
            changed.add(path);
        } catch (UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw ApiException.badRequest(what + " moet een getal zijn.");
        }
    }

    private static double amount(JsonElement element, double min, String what) throws ApiException {
        double value;
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                long cents = Money.parse(element.getAsString());
                if (cents < 0) {
                    throw ApiException.badRequest(what + " is geen geldig bedrag.");
                }
                value = Money.toDouble(cents);
            } else {
                value = element.getAsDouble();
            }
        } catch (UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw ApiException.badRequest(what + " is geen geldig bedrag.");
        }
        if (Double.isNaN(value) || value < min || value > 1_000_000_000) {
            throw ApiException.badRequest(what + " is geen geldig bedrag.");
        }
        return Math.round(value * 100) / 100.0;
    }

    private static String text(JsonElement element) throws ApiException {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString().replace("\r\n", "\n").strip();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldige tekst.");
        }
    }

    private static List<String> list(JsonElement element, String what) throws ApiException {
        List<String> values = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return values;
        }
        if (element.isJsonPrimitive()) {
            for (String part : element.getAsString().split("[,\\n]")) {
                values.add(part.strip());
            }
            return values;
        }
        if (!element.isJsonArray()) {
            throw ApiException.badRequest("Ongeldige lijst met " + what + ".");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_LIST * 4) {
            throw ApiException.badRequest("Te veel " + what + ".");
        }
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive()) {
                throw ApiException.badRequest("Ongeldige lijst met " + what + ".");
            }
            values.add(entry.getAsString().strip());
        }
        return values;
    }
}
