package nl.pinda.framework.modules.shop;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Het startmenu van /shop: naar de marktplaats, of je eigen shop beheren/beginnen. */
public final class ShopHubMenu extends Menu {

    private final ShopService service;

    public ShopHubMenu(PindaFramework plugin, Player viewer, ShopService service) {
        super(plugin, viewer, 3, plugin.lang().component(viewer, "shop.hub.title"));
        this.service = service;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);

        set(11, ItemBuilder.of(Material.EMERALD)
                .name(lang.component(code, "shop.hub.market.name"))
                .lore(lang.components(code, "shop.hub.market.lore", Text.p("count", service.openShops().size())))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            plugin.getServer().getScheduler().runTask(plugin, () -> new MarketMenu(plugin, viewer, service, 0).open());
        });

        set(13, ItemBuilder.of(Material.BOOK)
                .name(lang.component(code, "shop.hub.help.name"))
                .lore(lang.components(code, "shop.hub.help.lore",
                        Text.p("fee", service.economy().format(service.dailyFee())),
                        Text.p("tax", service.economy().formatPercent(service.taxPercent()))))
                .build());

        Shop own = service.shop(viewer.getUniqueId());
        if (own == null) {
            set(15, ItemBuilder.of(Material.CHEST)
                    .name(lang.component(code, "shop.hub.create.name"))
                    .lore(lang.components(code, "shop.hub.create.lore",
                            Text.p("fee", service.economy().format(service.dailyFee()))))
                    .build(), click -> {
                Shop shop = service.create(viewer);
                plugin.lang().send(viewer, "shop.created", Text.p("shop", shop.name()));
                plugin.theme().play(viewer, "success");
                plugin.getServer().getScheduler().runTask(plugin, () -> new ShopManageMenu(plugin, viewer, service, shop).open());
            });
        } else {
            set(15, ItemBuilder.of(Material.CHEST)
                    .name(lang.component(code, "shop.hub.manage.name", Text.p("shop", own.name())))
                    .lore(lang.components(code, "shop.hub.manage.lore",
                            Text.c("status", ShopManageMenu.status(plugin, service, own, code))))
                    .glint(service.isOpenForBuyers(own))
                    .build(), click -> {
                plugin.theme().play(viewer, "click");
                plugin.getServer().getScheduler().runTask(plugin, () -> new ShopManageMenu(plugin, viewer, service, own).open());
            });
        }

        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }
}
