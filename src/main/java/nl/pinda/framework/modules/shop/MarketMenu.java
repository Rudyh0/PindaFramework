package nl.pinda.framework.modules.shop;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** De marktplaats: alle shops die nu open zijn. */
public final class MarketMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final ShopService service;
    private final int page;

    public MarketMenu(PindaFramework plugin, Player viewer, ShopService service, int page) {
        super(plugin, viewer, 6, plugin.lang().component(viewer, "shop.market.title"));
        this.service = service;
        this.page = page;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        List<Shop> shops = service.openShops();
        int pages = Math.max(1, (shops.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.max(0, Math.min(page, pages - 1));

        int start = current * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < shops.size(); i++) {
            Shop shop = shops.get(start + i);
            boolean own = shop.owner().equals(viewer.getUniqueId());
            set(i, ItemBuilder.of(Material.PLAYER_HEAD)
                    .head(plugin.getServer().getOfflinePlayer(shop.owner()))
                    .name(lang.component(code, "shop.market.shop.name", Text.p("shop", shop.name())))
                    .lore(lang.components(code, own ? "shop.market.shop.lore-own" : "shop.market.shop.lore",
                            Text.p("player", shop.ownerName()), Text.p("items", shop.itemsForSale()),
                            Text.p("sales", shop.sales())))
                    .build(), click -> openShop(shop));
        }
        if (shops.isEmpty()) {
            set(22, ItemBuilder.of(Material.OAK_SIGN)
                    .name(lang.component(code, "shop.market.empty.name"))
                    .lore(lang.components(code, "shop.market.empty.lore"))
                    .build());
        }

        ItemStack filler = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideTooltip().build();
        for (int slot = 45; slot < 54; slot++) {
            set(slot, filler);
        }
        set(45, ItemBuilder.of(Material.ARROW)
                .name(lang.component(code, "shop.menu.back"))
                .build(), click -> plugin.getServer().getScheduler().runTask(plugin,
                () -> new ShopHubMenu(plugin, viewer, service).open()));
        set(49, ItemBuilder.of(Material.EMERALD)
                .name(lang.component(code, "shop.market.info.name"))
                .lore(lang.components(code, "shop.market.info.lore", Text.p("count", shops.size())))
                .build());
        if (current > 0) {
            set(48, ItemBuilder.of(Material.ARROW).name(lang.component(code, "menu.previous")).build(),
                    click -> reopen(current - 1));
        }
        if (current < pages - 1) {
            set(50, ItemBuilder.of(Material.ARROW).name(lang.component(code, "menu.next")).build(),
                    click -> reopen(current + 1));
        }
    }

    private void openShop(Shop shop) {
        plugin.theme().play(viewer, "click");
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (shop.owner().equals(viewer.getUniqueId()) && !service.allowOwnPurchases()) {
                new ShopManageMenu(plugin, viewer, service, shop).open();
            } else if (service.isOpenForBuyers(shop)) {
                new ShopViewMenu(plugin, viewer, service, shop, true).open();
            } else {
                plugin.lang().send(viewer, "shop.closed", Text.p("player", shop.ownerName()), Text.p("shop", shop.name()));
                new MarketMenu(plugin, viewer, service, page).open();
            }
        });
    }

    private void reopen(int newPage) {
        plugin.getServer().getScheduler().runTask(plugin, () -> new MarketMenu(plugin, viewer, service, newPage).open());
    }
}
