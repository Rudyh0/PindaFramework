package nl.pinda.framework.modules.panel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.shop.Listing;
import nl.pinda.framework.modules.shop.Shop;
import nl.pinda.framework.modules.shop.ShopModule;
import nl.pinda.framework.modules.shop.ShopService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Alle spelersshops bekijken en zo nodig sluiten. */
final class ShopsApi extends PanelApi {

    ShopsApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/shops", PanelUser.SHOPS, this::list);
        server.get("/api/shops/{uuid}", PanelUser.SHOPS, this::detail);
        server.post("/api/shops/{uuid}/close", PanelUser.SHOPS_MANAGE, this::close);
    }

    private Object list(PanelRequest request) throws Exception {
        ShopModule shops = require(ShopModule.class, "shop");
        return sync(() -> {
            ShopService service = shops.service();
            List<Shop> all = service.all();
            all.sort(Comparator.comparing((Shop shop) -> !service.isOpenForBuyers(shop))
                    .thenComparing(shop -> shop.name().toLowerCase(Locale.ROOT)));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Shop shop : all) {
                list.add(summary(this, service, shop));
            }
            return map("shops", list, "dailyFee", money(service.dailyFee()), "tax", service.taxPercent());
        });
    }

    private Object detail(PanelRequest request) throws Exception {
        ShopModule shops = require(ShopModule.class, "shop");
        UUID owner = request.uuidParam("uuid");
        return sync(() -> {
            ShopService service = shops.service();
            Shop shop = service.shop(owner);
            if (shop == null) {
                throw ApiException.notFound("Deze speler heeft geen shop.");
            }
            Map<String, Object> data = summary(this, service, shop);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Listing listing : shop.listings()) {
                ItemStack item = listing.template();
                items.add(map(
                        "slot", listing.slot(),
                        "material", item.getType().name().toLowerCase(Locale.ROOT),
                        "name", PlainTextComponentSerializer.plainText().serialize(ShopService.itemName(item)),
                        "enchanted", !item.getEnchantments().isEmpty(),
                        "stock", listing.stock(),
                        "price", listing.price() > 0 ? money(listing.price()) : null,
                        "forSale", listing.forSale()));
            }
            data.put("items", items);
            return data;
        });
    }

    private Object close(PanelRequest request) throws Exception {
        ShopModule shops = require(ShopModule.class, "shop");
        UUID owner = request.uuidParam("uuid");
        String actor = request.user().name();
        Map<String, Object> result = sync(() -> {
            ShopService service = shops.service();
            Shop shop = service.shop(owner);
            if (shop == null) {
                throw ApiException.notFound("Deze speler heeft geen shop.");
            }
            if (!shop.wantsOpen()) {
                throw ApiException.badRequest("Deze shop is al dicht.");
            }
            service.setOpen(shop, false);
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                plugin.lang().send(online, "panel.shop-closed", Text.p("actor", actor));
                plugin.theme().play(online, "error");
            }
            return summary(this, service, shop);
        });
        module.log().add(request, "shop sluiten", (String) result.get("ownerName"), (String) result.get("name"));
        return result;
    }

    /** De kerngegevens van een shop (hoofdthread). */
    static Map<String, Object> summary(PanelApi api, ShopService service, Shop shop) {
        Shop.SignLocation sign = shop.sign();
        return map(
                "owner", shop.owner().toString(),
                "ownerName", shop.ownerName(),
                "name", shop.name(),
                "wantsOpen", shop.wantsOpen(),
                "open", service.isOpenForBuyers(shop),
                "ownerOnline", service.isOwnerOnline(shop),
                "feePaidToday", LocalDate.now().toString().equals(shop.feeDay()),
                "forSale", shop.itemsForSale(),
                "slots", shop.listings().size(),
                "maxSlots", Shop.SLOTS,
                "sales", shop.sales(),
                "earned", api.money(shop.earned()),
                "created", shop.created(),
                "sign", sign == null ? null : map("world", sign.world(), "x", sign.x(), "y", sign.y(), "z", sign.z()));
    }
}
