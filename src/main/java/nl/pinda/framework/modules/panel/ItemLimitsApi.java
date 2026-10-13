package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.modules.itemlimits.ItemLimitModule;
import nl.pinda.framework.modules.itemlimits.LimitRule;
import nl.pinda.framework.modules.itemlimits.LimitedItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

/**
 * De item-limiet in het paneel: welke items beperkt zijn, wie ze heeft en waar ze liggen,
 * regels maken en aanpassen, en een item vrijgeven (als het echt weg is maar nog meetelt).
 */
final class ItemLimitsApi extends PanelApi {

    private static final String FILE = "modules/itemlimits.yml";
    private static final int MAX_RULES = 100;

    private final ConfigEditor editor;

    ItemLimitsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/itemlimits", PanelUser.PLAYERS, this::overview);
        server.get("/api/itemlimits/rules/{id}", PanelUser.PLAYERS, this::detail);
        server.post("/api/itemlimits/rules", PanelUser.CONFIG, this::saveRule);
        server.post("/api/itemlimits/rules/{id}/delete", PanelUser.CONFIG, this::deleteRule);
        server.post("/api/itemlimits/settings", PanelUser.CONFIG, this::saveSettings);
        server.post("/api/itemlimits/items/{serial}/release", PanelUser.PLAYERS_MANAGE, this::release);
    }

    private ItemLimitModule limits() throws ApiException {
        return require(ItemLimitModule.class, "itemlimits");
    }

    // ============================================================ bekijken

    private Object overview(PanelRequest request) throws Exception {
        ItemLimitModule limits = limits();
        boolean canEdit = request.user().has(PanelUser.CONFIG);
        return sync(() -> {
            Map<String, int[]> totals = limits.totals();
            List<Map<String, Object>> rules = new ArrayList<>();
            for (LimitRule rule : limits.rules()) {
                Map<String, Object> map = rule.toMap();
                int[] total = totals.getOrDefault(rule.id(), new int[3]);
                map.put("active", total[0]);
                map.put("owners", total[1]);
                map.put("gifts", total[2]);
                rules.add(map);
            }
            YamlConfiguration settings = limits.settings();
            Map<String, Object> result = map(
                    "rules", rules,
                    "settings", map("overLimit", "remove".equalsIgnoreCase(settings.getString("over-limit", "keep")) ? "remove" : "keep",
                            "markItems", settings.getBoolean("mark-items", true),
                            "notifyFreed", settings.getBoolean("notify-freed", true),
                            "giftCommands", settings.getStringList("gift-commands")),
                    "canEdit", canEdit,
                    "canRelease", request.user().has(PanelUser.PLAYERS_MANAGE));
            if (canEdit) {
                result.put("materials", PlayerActionsApi.materialNames());
                result.put("enchantments", enchantmentNames());
            }
            return result;
        });
    }

    static List<String> enchantmentNames() {
        List<String> names = new ArrayList<>();
        for (Enchantment enchantment : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            names.add(enchantment.getKey().getKey());
        }
        names.sort(null);
        return names;
    }

    private Object detail(PanelRequest request) throws Exception {
        ItemLimitModule limits = limits();
        String id = request.param("id");
        String code = plugin.lang().defaultLanguage();
        Map<String, Object> result = sync(() -> {
            LimitRule rule = limits.rule(id);
            if (rule == null) {
                throw ApiException.notFound("Dit beperkte item bestaat niet (meer).");
            }
            Map<UUID, List<Map<String, Object>>> perOwner = new LinkedHashMap<>();
            Map<UUID, String> names = new LinkedHashMap<>();
            List<Map<String, Object>> ownerless = new ArrayList<>();
            for (LimitedItem item : limits.itemsOfRule(rule.id())) {
                Map<String, Object> entry = map("serial", item.serial(), "location", item.location(),
                        "where", PlainTextComponentSerializer.plainText().serialize(limits.describe(item.location(), code)),
                        "since", item.created(), "updated", item.updated(), "gift", item.gift());
                if (item.owner() == null) {
                    ownerless.add(entry);
                } else {
                    perOwner.computeIfAbsent(item.owner(), key -> new ArrayList<>()).add(entry);
                    names.putIfAbsent(item.owner(), item.ownerName());
                }
            }
            List<Map<String, Object>> owners = new ArrayList<>();
            perOwner.forEach((uuid, items) -> {
                // Cadeaus (voor hemzelf) tellen niet mee voor zijn limiet.
                int gifts = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.get("gift"))).count();
                int count = items.size() - gifts;
                owners.add(map("uuid", uuid.toString(), "name", names.get(uuid), "count", count, "gifts", gifts,
                        "online", plugin.getServer().getPlayer(uuid) != null,
                        "over", rule.perPlayer() > 0 && count > rule.perPlayer(), "items", items));
            });
            owners.sort(Comparator.comparingInt((Map<String, Object> owner) -> -((int) owner.get("count") + (int) owner.get("gifts")))
                    .thenComparing(owner -> String.valueOf(owner.get("name")).toLowerCase(Locale.ROOT)));
            return map("rule", rule.toMap(), "owners", owners, "ownerless", ownerless);
        });
        List<Map<String, Object>> recent = new ArrayList<>();
        for (LimitedItem.Gone gone : await(limits.recentlyGone(id, 30))) {
            recent.add(map("serial", gone.serial(), "name", gone.ownerName(), "reason", gone.reason(), "time", gone.updated()));
        }
        result.put("recent", recent);
        result.put("canRelease", request.user().has(PanelUser.PLAYERS_MANAGE));
        result.put("canEdit", request.user().has(PanelUser.CONFIG));
        return result;
    }

    /** Voor het spelersprofiel: hoeveel beperkte items iemand heeft, en waar ze liggen (hoofdthread). */
    static List<Map<String, Object>> profile(PanelApi api, ItemLimitModule limits, UUID uuid) {
        String code = api.plugin.lang().defaultLanguage();
        List<LimitedItem> items = limits.itemsOf(uuid);
        List<Map<String, Object>> list = new ArrayList<>();
        for (LimitRule rule : limits.rules()) {
            List<Map<String, Object>> own = new ArrayList<>();
            int gifts = 0;
            for (LimitedItem item : items) {
                if (item.rule().equals(rule.id())) {
                    own.add(map("serial", item.serial(), "gift", item.gift(),
                            "where", PlainTextComponentSerializer.plainText().serialize(limits.describe(item.location(), code))));
                    if (item.gift()) {
                        gifts++;
                    }
                }
            }
            if (rule.enabled() || !own.isEmpty()) {
                list.add(map("id", rule.id(), "name", rule.name(), "material", rule.material().getKey().getKey(),
                        "enchantment", rule.enchantment() == null ? "" : rule.enchantment().getKey().getKey(),
                        "enabled", rule.enabled(), "max", rule.perPlayer(), "count", own.size() - gifts, "gifts", gifts, "items", own));
            }
        }
        return list;
    }

    // ============================================================ aanpassen

    private Object saveRule(PanelRequest request) throws Exception {
        ItemLimitModule limits = limits();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            List<String> comments = yaml.getComments("items");
            items = yaml.createSection("items");
            yaml.setComments("items", comments);
        }
        String id = request.optString("id");
        boolean isNew = id == null || !items.isConfigurationSection(id.toLowerCase(Locale.ROOT));
        Material material = LimitRule.material(request.string("material", "Kies een item."));
        if (material == null) {
            throw ApiException.badRequest("Dat item bestaat niet. Gebruik de Minecraft-naam, bijv. totem_of_undying.");
        }
        String enchantmentName = request.optString("enchantment");
        Enchantment enchantment = null;
        if (enchantmentName != null) {
            enchantment = LimitRule.enchantment(enchantmentName);
            if (enchantment == null) {
                throw ApiException.badRequest("De betovering '" + enchantmentName + "' bestaat niet.");
            }
        }
        String name = request.optString("name");
        if (name != null && name.length() > LimitRule.MAX_NAME) {
            throw ApiException.badRequest("De naam is te lang (hooguit " + LimitRule.MAX_NAME + " tekens).");
        }
        int perPlayer = number(body, "perPlayer");
        int serverMax = number(body, "serverMax");
        if (perPlayer == 0 && serverMax == 0) {
            throw ApiException.badRequest("Vul een maximum per speler of voor de hele server in (of allebei).");
        }
        boolean enabled = !body.has("enabled") || bool(body.get("enabled"));
        if (isNew) {
            if (items.getKeys(false).size() >= MAX_RULES) {
                throw ApiException.badRequest("Hooguit " + MAX_RULES + " beperkte items.");
            }
            id = newId(items, id == null ? material.getKey().getKey() : id);
        } else {
            id = id.toLowerCase(Locale.ROOT);
        }
        LimitRule rule = new LimitRule(id, material, enchantment, name == null ? "" : name, perPlayer, serverMax, enabled);
        for (String other : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(other);
            LimitRule existing = section == null || other.equalsIgnoreCase(id) ? null : LimitRule.parse(other, section);
            if (existing != null && existing.material() == material && java.util.Objects.equals(existing.enchantment(), enchantment)) {
                throw ApiException.badRequest("Voor dit item is er al een limiet (" + other + "). Pas die aan.");
            }
        }
        ConfigurationSection section = isNew ? items.createSection(id) : items.getConfigurationSection(id);
        rule.write(section);
        editor.write(FILE, yaml);
        String ruleId = id;
        boolean created = isNew;
        Enchantment chosen = enchantment;
        sync(() -> {
            // Ander item of andere betovering: de oude nummers horen niet meer bij deze regel.
            LimitRule old = created ? null : limits.rule(ruleId);
            if (old != null && (old.material() != material || !java.util.Objects.equals(old.enchantment(), chosen))) {
                limits.forget(ruleId, "regel aangepast");
            }
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, isNew ? "item-limiet toegevoegd" : "item-limiet aangepast", id,
                material.getKey().getKey() + (enchantment == null ? "" : " (" + enchantment.getKey().getKey() + ")")
                        + ", per speler " + perPlayer + ", server " + serverMax + (enabled ? "" : ", uit"));
        return overview(request);
    }

    private static String newId(ConfigurationSection items, String wanted) {
        String base = wanted.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("^[^a-z0-9]+", "");
        if (base.isEmpty()) {
            base = "item";
        }
        if (base.length() > 28) {
            base = base.substring(0, 28);
        }
        String id = base;
        for (int n = 2; items.contains(id); n++) {
            id = base + "-" + n;
        }
        if (!LimitRule.ID.matcher(id).matches()) {
            throw new IllegalStateException("ongeldige naam " + id);
        }
        return id;
    }

    private static int number(JsonObject body, String key) throws ApiException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        try {
            double value = element.getAsDouble();
            if (Double.isNaN(value) || value < 0 || value > LimitRule.MAX_AMOUNT || value != Math.rint(value)) {
                throw ApiException.badRequest("Vul een heel getal in van 0 tot " + LimitRule.MAX_AMOUNT + ".");
            }
            return (int) value;
        } catch (UnsupportedOperationException | NumberFormatException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldig getal voor '" + key + "'.");
        }
    }

    private static boolean bool(JsonElement element) throws ApiException {
        try {
            return element != null && !element.isJsonNull() && element.getAsBoolean();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.badRequest("Ongeldige waarde (aan/uit verwacht).");
        }
    }

    private Object deleteRule(PanelRequest request) throws Exception {
        ItemLimitModule limits = limits();
        String id = request.param("id");
        YamlConfiguration yaml = editor.read(FILE);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (id == null || items == null || !items.isConfigurationSection(id)) {
            throw ApiException.notFound("Dit beperkte item bestaat niet (meer).");
        }
        items.set(id, null);
        editor.write(FILE, yaml);
        int forgotten = sync(() -> {
            int count = limits.forget(id, "limiet weggehaald");
            editor.reload(FILE);
            return count;
        });
        module.log().add(request, "item-limiet verwijderd", id, forgotten + " items telden mee");
        return overview(request);
    }

    private Object saveSettings(PanelRequest request) throws Exception {
        limits();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        if (body.has("overLimit")) {
            String value = request.string("overLimit", "Kies wat er gebeurt boven het maximum.");
            if (!value.equals("keep") && !value.equals("remove")) {
                throw ApiException.badRequest("Kies 'houden' of 'weghalen'.");
            }
            yaml.set("over-limit", value);
        }
        if (body.has("markItems")) {
            yaml.set("mark-items", bool(body.get("markItems")));
        }
        if (body.has("notifyFreed")) {
            yaml.set("notify-freed", bool(body.get("notifyFreed")));
        }
        if (body.has("giftCommands")) {
            List<String> commands = new ArrayList<>();
            JsonElement element = body.get("giftCommands");
            if (element == null || !element.isJsonArray()) {
                throw ApiException.badRequest("Ongeldige lijst met commando's.");
            }
            for (JsonElement command : element.getAsJsonArray()) {
                if (!command.isJsonNull() && !command.isJsonPrimitive()) {
                    throw ApiException.badRequest("Ongeldige lijst met commando's.");
                }
                String label = command.isJsonNull() ? "" : command.getAsString().strip().toLowerCase(Locale.ROOT);
                if (label.startsWith("/")) {
                    label = label.substring(1);
                }
                if (label.isEmpty()) {
                    continue;
                }
                if (!label.matches("[a-z0-9_.:-]{1,48}")) {
                    throw ApiException.badRequest("'" + label + "' is geen commando (alleen de naam, zonder spaties).");
                }
                if (!commands.contains(label) && commands.size() < 20) {
                    commands.add(label);
                }
            }
            yaml.set("gift-commands", commands);
        }
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "item-limiet instellingen", null, null);
        return overview(request);
    }

    private Object release(PanelRequest request) throws Exception {
        ItemLimitModule limits = limits();
        String serial = request.param("serial");
        String actor = request.user().name();
        boolean released = sync(() -> limits.release(serial, actor));
        if (!released) {
            throw ApiException.notFound("Dit item telt al niet meer mee.");
        }
        module.log().add(request, "item vrijgegeven", null, serial);
        return map("ok", true);
    }
}
