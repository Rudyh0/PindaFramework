package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.modules.backpack.BackpackModule;
import nl.pinda.framework.modules.shop.ShopService;
import org.bukkit.inventory.ItemStack;

/** De rugtas van een speler in het paneel: bekijken (ook offline) en items weghalen. */
final class BackpackApi extends PanelApi {

    BackpackApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/players/{uuid}/backpack", PanelUser.PLAYERS, this::backpack);
        server.post("/api/players/{uuid}/backpack/remove", PanelUser.PLAYERS_MANAGE, this::remove);
    }

    private BackpackModule backpacks() throws ApiException {
        return require(BackpackModule.class, "backpack");
    }

    private Object backpack(PanelRequest request) throws Exception {
        BackpackModule backpacks = backpacks();
        UUID uuid = request.uuidParam("uuid");
        ItemStack[] items = syncAwait(() -> backpacks.contents(uuid));
        BackpackModule.DroppedInfo dropped = sync(() -> backpacks.droppedOf(uuid));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            list.add(map("slot", slot,
                    "material", item.getType().name().toLowerCase(Locale.ROOT),
                    "name", PlainTextComponentSerializer.plainText().serialize(ShopService.itemName(item)),
                    "amount", item.getAmount(),
                    "enchanted", !item.getEnchantments().isEmpty()));
        }
        // Een rugtas kan groter zijn dan ingesteld (als hij kleiner is gemaakt terwijl er meer in zat)
        int highest = -1;
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] != null && !items[slot].isEmpty()) {
                highest = slot;
            }
        }
        int extra = backpacks.moneyShown() ? 2 : 1;
        int rows = Math.max(backpacks.rows(), Math.min(6, (highest + extra + 8) / 9));
        int moneySlot = backpacks.moneyShown() ? rows * 9 - 1 : -1;
        if (moneySlot >= 0 && moneySlot < items.length && items[moneySlot] != null && !items[moneySlot].isEmpty()) {
            moneySlot = -1;
        }
        return map("rows", rows, "capacity", rows * 9 - (moneySlot >= 0 ? 1 : 0), "moneySlot", moneySlot,
                "items", list,
                "dropped", dropped == null ? null : map("world", dropped.world(), "x", dropped.x(), "y", dropped.y(), "z", dropped.z(),
                        "ownerOnlyUntil", dropped.ownerOnlyUntil(), "expiresAt", dropped.expiresAt(),
                        "items", dropped.items(), "cash", money(dropped.cash())));
    }

    private Object remove(PanelRequest request) throws Exception {
        BackpackModule backpacks = backpacks();
        UUID uuid = request.uuidParam("uuid");
        int slot;
        try {
            slot = Integer.parseInt(request.string("slot", "Kies een vak."));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Ongeldig vak.");
        }
        requireLower(request.user(), uuid, "Je kunt dit alleen doen bij spelers met een lagere rang dan jij.");
        int target = slot;
        ItemStack removed = syncAwait(() -> backpacks.removeItem(uuid, target));
        if (removed == null) {
            throw ApiException.badRequest("Dat vak is al leeg.");
        }
        module.log().add(request, "item uit rugtas verwijderd", null,
                removed.getAmount() + "x " + removed.getType().name().toLowerCase(Locale.ROOT));
        return backpack(request);
    }
}
