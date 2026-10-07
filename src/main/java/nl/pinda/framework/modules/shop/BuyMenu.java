package nl.pinda.framework.modules.shop;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Kiezen hoeveel je koopt: 1, 8, 16, 32, 64 of zoveel als kan. */
public final class BuyMenu extends Menu {

    private static final int[] AMOUNTS = {1, 8, 16, 32, 64};
    private static final int[] AMOUNT_SLOTS = {10, 11, 12, 13, 14};
    private static final int MAX_SLOT = 16;

    private final ShopService service;
    private final Shop shop;
    private final int slot;
    private final boolean fromMarket;

    public BuyMenu(PindaFramework plugin, Player viewer, ShopService service, Shop shop, int slot, boolean fromMarket) {
        super(plugin, viewer, 3, plugin.lang().component(viewer, "shop.buy.title", Text.p("shop", shop.name())));
        this.service = service;
        this.shop = shop;
        this.slot = slot;
        this.fromMarket = fromMarket;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        Listing listing = shop.listing(slot);
        if (listing == null || !listing.forSale()) {
            set(4, ItemBuilder.of(Material.BARRIER).name(lang.component(code, "shop.buy.sold-out")).build());
            addBack(code);
            fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
            return;
        }

        List<Component> preview = new ArrayList<>();
        preview.add(lang.component(code, "shop.view.price", Text.p("price", service.economy().format(listing.price()))));
        preview.add(lang.component(code, "shop.view.stock", Text.p("stock", listing.stock())));
        set(4, ShopItems.display(listing.template(), 1, preview));

        int max = service.maxBuyable(viewer, listing);
        for (int i = 0; i < AMOUNTS.length; i++) {
            int amount = AMOUNTS[i];
            if (amount > listing.stock()) {
                set(AMOUNT_SLOTS[i], unavailable(code, amount, "shop.buy.not-enough-stock"));
                continue;
            }
            if (amount > max) {
                set(AMOUNT_SLOTS[i], unavailable(code, amount, "shop.buy.cannot"));
                continue;
            }
            List<Component> lore = List.of(lang.component(code, "shop.buy.total",
                    Text.p("price", service.economy().format(listing.price() * amount))));
            set(AMOUNT_SLOTS[i], ShopItems.display(listing.template(), amount,
                    withName(code, amount, lore)), click -> buy(amount));
        }

        if (max > 0) {
            List<Component> lore = List.of(lang.component(code, "shop.buy.total",
                    Text.p("price", service.economy().format(listing.price() * max))));
            set(MAX_SLOT, ItemBuilder.of(Material.HOPPER)
                    .name(lang.component(code, "shop.buy.max", Text.p("amount", max)))
                    .lore(lore)
                    .build(), click -> buy(max));
        } else {
            set(MAX_SLOT, ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(lang.component(code, "shop.buy.cannot"))
                    .build());
        }

        set(18, ItemBuilder.of(Material.GOLD_INGOT)
                .name(lang.component(code, "shop.view.balance.name"))
                .lore(lang.components(code, "shop.view.balance.lore",
                        Text.p("cash", service.economy().format(service.economy().account(viewer).cash())),
                        Text.p("bank", service.economy().format(service.economy().account(viewer).bank()))))
                .build());
        addBack(code);
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private List<Component> withName(String code, int amount, List<Component> lore) {
        List<Component> lines = new ArrayList<>();
        lines.add(plugin.lang().component(code, "shop.buy.amount", Text.p("amount", amount)));
        lines.addAll(lore);
        return lines;
    }

    private ItemStack unavailable(String code, int amount, String reasonKey) {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(plugin.lang().component(code, "shop.buy.amount", Text.p("amount", amount)))
                .lore(plugin.lang().component(code, reasonKey))
                .build();
    }

    private void addBack(String code) {
        set(22, ItemBuilder.of(Material.ARROW)
                .name(plugin.lang().component(code, "shop.menu.back"))
                .build(), click -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (service.isOpenForBuyers(shop)) {
                new ShopViewMenu(plugin, viewer, service, shop, fromMarket).open();
            } else {
                new MarketMenu(plugin, viewer, service, 0).open();
            }
        }));
    }

    private void buy(int amount) {
        ShopService.PurchaseResult result = service.buy(viewer, shop, slot, amount);
        switch (result) {
            case OK -> {
                // Melding en geluid komen uit de service
            }
            case CLOSED -> fail("shop.closed", Text.p("player", shop.ownerName()), Text.p("shop", shop.name()));
            case SOLD_OUT -> fail("shop.buy.sold-out");
            case OWN_SHOP -> fail("shop.own-shop");
            case NO_SPACE -> fail("shop.inventory-full");
            case NO_MONEY -> fail("economy.not-enough",
                    Text.p("amount", service.economy().format(service.economy().spendable(service.economy().account(viewer)))));
        }
        refresh();
    }

    private void fail(String key, TagResolver... resolvers) {
        plugin.lang().send(viewer, key, resolvers);
        plugin.theme().play(viewer, "error");
    }
}
