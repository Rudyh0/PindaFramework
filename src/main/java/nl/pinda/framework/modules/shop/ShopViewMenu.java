package nl.pinda.framework.modules.shop;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Een shop bekijken als koper. Klik op een item om te kiezen hoeveel je wilt kopen. */
public final class ShopViewMenu extends Menu {

    private final ShopService service;
    private final Shop shop;
    private final boolean fromMarket;

    /**
     * @param fromMarket true als de koper via de marktplaats kwam (dan gaat "terug" daarheen)
     */
    public ShopViewMenu(PindaFramework plugin, Player viewer, ShopService service, Shop shop, boolean fromMarket) {
        super(plugin, viewer, 6, plugin.lang().component(viewer, "shop.view.title", Text.p("shop", shop.name())));
        this.service = service;
        this.shop = shop;
        this.fromMarket = fromMarket;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);

        for (Listing listing : shop.listings()) {
            if (!listing.forSale()) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(lang.component(code, "shop.view.price", Text.p("price", service.economy().format(listing.price()))));
            lore.add(lang.component(code, "shop.view.stock", Text.p("stock", listing.stock())));
            lore.add(Component.empty());
            lore.add(lang.component(code, "shop.view.click"));
            set(listing.slot(), ShopItems.display(listing.template(), listing.stock(), lore), click -> {
                plugin.theme().play(viewer, "click");
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> new BuyMenu(plugin, viewer, service, shop, listing.slot(), fromMarket).open());
            });
        }

        ItemStack filler = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideTooltip().build();
        for (int slot = 36; slot < 54; slot++) {
            set(slot, filler);
        }
        if (fromMarket) {
            set(45, ItemBuilder.of(Material.ARROW)
                    .name(lang.component(code, "shop.menu.back"))
                    .build(), click -> plugin.getServer().getScheduler().runTask(plugin,
                    () -> new MarketMenu(plugin, viewer, service, 0).open()));
        }
        set(49, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(plugin.getServer().getOfflinePlayer(shop.owner()))
                .name(lang.component(code, "shop.view.info.name", Text.p("shop", shop.name())))
                .lore(lang.components(code, "shop.view.info.lore",
                        Text.p("player", shop.ownerName()), Text.p("sales", shop.sales())))
                .build());
        set(53, ItemBuilder.of(Material.GOLD_INGOT)
                .name(lang.component(code, "shop.view.balance.name"))
                .lore(lang.components(code, "shop.view.balance.lore",
                        Text.p("cash", service.economy().format(service.economy().account(viewer).cash())),
                        Text.p("bank", service.economy().format(service.economy().account(viewer).bank()))))
                .build());
    }
}
