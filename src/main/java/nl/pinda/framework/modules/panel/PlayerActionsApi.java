package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.homes.Home;
import nl.pinda.framework.modules.homes.HomesModule;
import nl.pinda.framework.modules.shop.ShopService;
import nl.pinda.framework.modules.spawn.SpawnModule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * Alles wat een admin in-game met een speler kan doen: spelmodus, healen, teleporteren,
 * inventory bekijken en aanpassen, items geven, berichten sturen en homes beheren.
 */
final class PlayerActionsApi extends PanelApi {

    PlayerActionsApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.post("/api/players/{uuid}/action", PanelUser.PLAYERS_MANAGE, this::action);
        server.get("/api/players/{uuid}/inventory", PanelUser.PLAYERS_MANAGE, this::inventory);
        server.post("/api/players/{uuid}/inventory/remove", PanelUser.PLAYERS_MANAGE, this::removeItem);
        server.get("/api/players/{uuid}/homes", PanelUser.PLAYERS_MANAGE, this::homes);
        server.post("/api/players/{uuid}/homes/delete", PanelUser.PLAYERS_MANAGE, this::deleteHome);
        server.get("/api/materials", PanelUser.PLAYERS_MANAGE, request -> materials());
    }

    private Player online(UUID uuid) throws ApiException {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) {
            throw ApiException.badRequest("Deze speler is niet online.");
        }
        return player;
    }

    // ============================================================ acties

    private Object action(PanelRequest request) throws Exception {
        UUID uuid = request.uuidParam("uuid");
        String action = request.string("action", "Kies een actie.").toLowerCase(Locale.ROOT);
        String value = request.optString("value");
        requireLower(request.user(), uuid, "Je kunt dit alleen doen bij spelers met een lagere rang dan jij.");
        String actor = request.user().name();
        int amount = request.body().has("amount") ? request.body().get("amount").getAsInt() : 1;
        String result = sync(() -> {
            Player player = online(uuid);
            switch (action) {
                case "gamemode" -> {
                    GameMode mode;
                    try {
                        mode = GameMode.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw ApiException.badRequest("Onbekende spelmodus.");
                    }
                    player.setGameMode(mode);
                    return player.getName() + " -> " + mode.name().toLowerCase(Locale.ROOT);
                }
                case "heal" -> {
                    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
                    player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
                    player.setFoodLevel(20);
                    player.setSaturation(20f);
                    player.setFireTicks(0);
                    for (PotionEffect effect : player.getActivePotionEffects()) {
                        player.removePotionEffect(effect.getType());
                    }
                    return player.getName();
                }
                case "feed" -> {
                    player.setFoodLevel(20);
                    player.setSaturation(20f);
                    return player.getName();
                }
                case "fly" -> {
                    boolean fly = !player.getAllowFlight();
                    player.setAllowFlight(fly);
                    if (!fly) {
                        player.setFlying(false);
                    }
                    return player.getName() + (fly ? " aan" : " uit");
                }
                case "spawn" -> {
                    SpawnModule spawn = enabled(SpawnModule.class);
                    Location location = spawn != null ? spawn.spawn() : plugin.getServer().getWorlds().get(0).getSpawnLocation();
                    player.teleportAsync(location);
                    return player.getName();
                }
                case "teleport" -> {
                    Player target = value == null ? null : plugin.getServer().getPlayerExact(value);
                    if (target == null) {
                        throw ApiException.badRequest("Kies een speler die online is.");
                    }
                    player.teleportAsync(target.getLocation());
                    return player.getName() + " -> " + target.getName();
                }
                case "message" -> {
                    if (value == null || value.length() > 256) {
                        throw ApiException.badRequest("Typ een bericht (maximaal 256 tekens).");
                    }
                    plugin.lang().send(player, "panel.message", Text.p("actor", actor), Text.p("message", value));
                    plugin.theme().play(player, "message");
                    return player.getName() + ": " + value;
                }
                case "clear-inventory" -> {
                    player.getInventory().clear();
                    return player.getName();
                }
                case "give" -> {
                    Material material = value == null ? null : Material.matchMaterial(value);
                    if (material == null || !material.isItem() || material.isAir()) {
                        throw ApiException.badRequest("Onbekend item.");
                    }
                    int count = Math.max(1, Math.min(64 * 36, amount));
                    int left = count;
                    while (left > 0) {
                        int stack = Math.min(left, material.getMaxStackSize());
                        for (ItemStack rest : player.getInventory().addItem(new ItemStack(material, stack)).values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), rest);
                        }
                        left -= stack;
                    }
                    return count + "x " + material.name().toLowerCase(Locale.ROOT) + " aan " + player.getName();
                }
                default -> throw ApiException.badRequest("Onbekende actie.");
            }
        });
        module.log().add(request, "speler: " + action, null, result);
        return map("result", result);
    }

    // ============================================================ inventory

    private Object inventory(PanelRequest request) throws Exception {
        UUID uuid = request.uuidParam("uuid");
        return sync(() -> {
            Player player = online(uuid);
            return map("inventory", items(player.getInventory()), "enderchest", items(player.getEnderChest()));
        });
    }

    private static List<Map<String, Object>> items(Inventory inventory) {
        List<Map<String, Object>> items = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            items.add(map("slot", slot,
                    "material", item.getType().name().toLowerCase(Locale.ROOT),
                    "name", PlainTextComponentSerializer.plainText().serialize(ShopService.itemName(item)),
                    "amount", item.getAmount(),
                    "enchanted", !item.getEnchantments().isEmpty()));
        }
        return items;
    }

    private Object removeItem(PanelRequest request) throws Exception {
        UUID uuid = request.uuidParam("uuid");
        int slot;
        try {
            slot = Integer.parseInt(request.string("slot", "Kies een vak."));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Ongeldig vak.");
        }
        requireLower(request.user(), uuid, "Je kunt dit alleen doen bij spelers met een lagere rang dan jij.");
        boolean ender = request.body().has("enderchest") && request.body().get("enderchest").getAsBoolean();
        int target = slot;
        String removed = sync(() -> {
            Player player = online(uuid);
            Inventory inventory = ender ? player.getEnderChest() : player.getInventory();
            if (target < 0 || target >= inventory.getSize()) {
                throw ApiException.badRequest("Ongeldig vak.");
            }
            ItemStack item = inventory.getItem(target);
            if (item == null || item.isEmpty()) {
                throw ApiException.badRequest("Dat vak is al leeg.");
            }
            inventory.setItem(target, null);
            return item.getAmount() + "x " + item.getType().name().toLowerCase(Locale.ROOT) + " van " + player.getName();
        });
        module.log().add(request, "item verwijderd", null, removed);
        return inventory(request);
    }

    // ============================================================ homes

    private Object homes(PanelRequest request) throws Exception {
        HomesModule homes = require(HomesModule.class, "homes");
        UUID uuid = request.uuidParam("uuid");
        Map<String, Home> map = await(homes.homesOf(uuid));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Home home : homes.sorted(map)) {
            list.add(map("name", home.name(), "world", home.world(), "x", home.blockX(), "y", home.blockY(),
                    "z", home.blockZ(), "created", home.created()));
        }
        return map("homes", list);
    }

    private Object deleteHome(PanelRequest request) throws Exception {
        HomesModule homes = require(HomesModule.class, "homes");
        UUID uuid = request.uuidParam("uuid");
        String name = request.string("name", "Kies een home.");
        requireLower(request.user(), uuid, "Je kunt dit alleen doen bij spelers met een lagere rang dan jij.");
        Map<String, Home> map = await(homes.homesOf(uuid));
        if (!map.containsKey(name)) {
            throw ApiException.notFound("Deze home bestaat niet.");
        }
        sync(() -> {
            homes.deleteHome(uuid, name);
            return null;
        });
        String owner = new PlayersApi(module).known(uuid).name();
        module.log().add(request, "home verwijderd", owner, name);
        return homes(request);
    }

    // ============================================================ items

    private Object materials() {
        List<String> names = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isAir() && !material.name().startsWith("LEGACY_")) {
                names.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        return map("materials", names);
    }
}
