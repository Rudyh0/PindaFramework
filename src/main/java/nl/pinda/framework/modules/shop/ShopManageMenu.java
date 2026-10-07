package nl.pinda.framework.modules.shop;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ConfirmMenu;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Je eigen shop beheren. Sleep items uit je inventory naar een vak (of shift-klik ze),
 * klik op een vak om items terug te nemen en rechtsklik om de prijs in te stellen.
 *
 * <pre>
 * rij 0-3: de 36 vakken van je shop
 * rij 4:   [uitleg]
 * rij 5:   [terug] [open/dicht] [info] [naam] [opheffen]
 * </pre>
 */
public final class ShopManageMenu extends Menu {

    private static final int SIZE = 54;
    private static final int HELP_SLOT = 40;
    private static final int BACK_SLOT = 45;
    private static final int TOGGLE_SLOT = 47;
    private static final int INFO_SLOT = 49;
    private static final int RENAME_SLOT = 51;
    private static final int DELETE_SLOT = 53;

    private final ShopService service;
    private final Shop shop;

    public ShopManageMenu(PindaFramework plugin, Player viewer, ShopService service, Shop shop) {
        super(plugin, viewer, 6, plugin.lang().component(viewer, "shop.manage.title", Text.p("shop", shop.name())));
        this.service = service;
        this.shop = shop;
    }

    /** De status van een shop als tekst: open, gesloten, uitverkocht of eigenaar offline. */
    static Component status(PindaFramework plugin, ShopService service, Shop shop, String code) {
        String key;
        if (service.isOpenForBuyers(shop)) {
            key = "shop.status.open";
        } else if (!shop.wantsOpen()) {
            key = "shop.status.closed";
        } else if (!shop.hasStock()) {
            key = "shop.status.sold-out";
        } else if (!service.isOwnerOnline(shop)) {
            key = "shop.status.offline";
        } else {
            key = "shop.status.fee-unpaid";
        }
        return plugin.lang().component(code, key);
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);

        for (Listing listing : shop.listings()) {
            set(listing.slot(), listingItem(lang, code, listing));
        }

        ItemStack filler = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideTooltip().build();
        for (int slot = 36; slot < SIZE; slot++) {
            set(slot, filler);
        }
        set(HELP_SLOT, ItemBuilder.of(Material.BOOK)
                .name(lang.component(code, "shop.manage.help.name"))
                .lore(lang.components(code, "shop.manage.help.lore"))
                .build());

        set(BACK_SLOT, ItemBuilder.of(Material.ARROW)
                .name(lang.component(code, "shop.menu.back"))
                .build(), click -> plugin.getServer().getScheduler().runTask(plugin,
                () -> new ShopHubMenu(plugin, viewer, service).open()));

        boolean open = shop.wantsOpen();
        set(TOGGLE_SLOT, ItemBuilder.of(open ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(lang.component(code, open ? "shop.manage.toggle.open" : "shop.manage.toggle.closed"))
                .lore(lang.components(code, open ? "shop.manage.toggle.lore-close" : "shop.manage.toggle.lore-open",
                        Text.p("fee", service.economy().format(service.dailyFee()))))
                .glint(open)
                .build(), click -> toggle());

        set(INFO_SLOT, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name(lang.component(code, "shop.manage.info.name", Text.p("shop", shop.name())))
                .lore(lang.components(code, "shop.manage.info.lore",
                        Text.c("status", status(plugin, service, shop, code)),
                        Text.p("items", shop.itemsForSale()),
                        Text.p("sales", shop.sales()),
                        Text.p("earned", service.economy().format(shop.earned())),
                        Text.p("fee", service.economy().format(service.dailyFee())),
                        Text.p("tax", service.economy().formatPercent(service.taxPercent())),
                        Text.c("sign", signText(code))))
                .build());

        set(RENAME_SLOT, ItemBuilder.of(Material.NAME_TAG)
                .name(lang.component(code, "shop.manage.rename.name"))
                .lore(lang.components(code, "shop.manage.rename.lore"))
                .build(), click -> rename());

        set(DELETE_SLOT, ItemBuilder.of(Material.LAVA_BUCKET)
                .name(lang.component(code, "shop.manage.delete.name"))
                .lore(lang.components(code, "shop.manage.delete.lore"))
                .build(), click -> delete());
    }

    private Component signText(String code) {
        Shop.SignLocation sign = shop.sign();
        if (sign == null) {
            return plugin.lang().component(code, "shop.manage.info.no-sign");
        }
        return plugin.lang().component(code, "shop.manage.info.sign",
                Text.p("x", sign.x()), Text.p("y", sign.y()), Text.p("z", sign.z()));
    }

    private ItemStack listingItem(LanguageManager lang, String code, Listing listing) {
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(code, "shop.manage.listing.stock", Text.p("stock", listing.stock())));
        if (listing.price() > 0) {
            lore.add(lang.component(code, "shop.manage.listing.price",
                    Text.p("price", service.economy().format(listing.price()))));
        } else {
            lore.add(lang.component(code, "shop.manage.listing.no-price"));
        }
        if (listing.stock() == 0) {
            lore.add(lang.component(code, "shop.manage.listing.sold-out"));
        }
        lore.add(Component.empty());
        lore.addAll(lang.components(code, "shop.manage.listing.actions"));
        return ShopItems.display(listing.template(), listing.stock(), lore);
    }

    // ---------------------------------------------------------------- klikken

    @Override
    protected boolean onRawClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        ClickType click = event.getClick();

        // Klikken in de eigen inventory
        if (raw >= SIZE) {
            if (click.isShiftClick()) {
                ItemStack current = event.getCurrentItem();
                Inventory clicked = event.getClickedInventory();
                if (current != null && !current.isEmpty() && clicked != null) {
                    addFromInventory(clicked, event.getSlot(), current);
                }
            } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                event.setCancelled(false); // gewoon items oppakken/neerleggen in je eigen inventory
            }
            return true;
        }
        if (raw < 0) {
            return true;
        }

        // Knoppen onderaan
        if (raw >= Shop.SLOTS) {
            runAction(event);
            return true;
        }

        // Een vak van de shop
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.isEmpty()) {
            placeCursor(raw, cursor);
            return true;
        }
        Listing listing = shop.listing(raw);
        if (listing == null) {
            return true;
        }
        if (click == ClickType.RIGHT) {
            askPrice(listing);
        } else if (click.isShiftClick()) {
            takeBack(listing, Integer.MAX_VALUE);
        } else if (click == ClickType.LEFT) {
            takeBack(listing, listing.template().getMaxStackSize());
        }
        return true;
    }

    @Override
    protected boolean onRawDrag(InventoryDragEvent event) {
        // Slepen binnen de eigen inventory mag; in de shop leg je items met een klik.
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE) {
                event.setCancelled(true);
                return true;
            }
        }
        return true;
    }

    private void placeCursor(int slot, ItemStack cursor) {
        ItemStack items = cursor.clone();
        if (service.addStock(shop, slot, items)) {
            viewer.setItemOnCursor(null);
            plugin.theme().play(viewer, "click");
            afterAdd(slot);
        } else {
            plugin.lang().send(viewer, "shop.slot-taken");
            plugin.theme().play(viewer, "error");
        }
        refresh();
    }

    private void addFromInventory(Inventory inventory, int slot, ItemStack current) {
        ItemStack items = current.clone();
        int target = service.slotFor(shop, items);
        if (target < 0) {
            plugin.lang().send(viewer, "shop.full");
            plugin.theme().play(viewer, "error");
            return;
        }
        inventory.setItem(slot, null);
        if (!service.addStock(shop, target, items)) {
            inventory.setItem(slot, items); // kan eigenlijk niet, maar voor de zekerheid terugzetten
            return;
        }
        plugin.theme().play(viewer, "click");
        afterAdd(target);
        refresh();
    }

    /** Herinnering om een prijs te zetten voor nieuwe vakken. */
    private void afterAdd(int slot) {
        Listing listing = shop.listing(slot);
        if (listing != null && listing.price() <= 0) {
            plugin.lang().send(viewer, "shop.set-price-hint");
        }
    }

    private void takeBack(Listing listing, int wanted) {
        if (listing.stock() == 0) {
            service.removeEmpty(shop, listing);
            plugin.theme().play(viewer, "click");
            refresh();
            return;
        }
        int taken = service.takeBack(shop, listing, viewer, wanted);
        if (taken <= 0) {
            plugin.lang().send(viewer, "shop.inventory-full");
            plugin.theme().play(viewer, "error");
            return;
        }
        plugin.theme().play(viewer, "click");
        refresh();
    }

    private void askPrice(Listing listing) {
        plugin.lang().send(viewer, "shop.price-prompt", Text.c("item", ShopService.itemName(listing.template())));
        plugin.input().ask(viewer, text -> {
            if (shop.listing(listing.slot()) != listing) {
                reopen();
                return;
            }
            long cents = Money.parse(text);
            if (cents <= 0) {
                plugin.lang().send(viewer, "economy.invalid-amount", Text.p("input", text));
                plugin.theme().play(viewer, "error");
            } else if (cents > service.maxPrice()) {
                plugin.lang().send(viewer, "shop.price-too-high", Text.p("max", service.economy().format(service.maxPrice())));
                plugin.theme().play(viewer, "error");
            } else {
                service.setPrice(shop, listing, cents);
                plugin.lang().send(viewer, "shop.price-set", Text.c("item", ShopService.itemName(listing.template())),
                        Text.p("price", service.economy().format(cents)));
                plugin.theme().play(viewer, "success");
            }
            reopen();
        }, this::reopen);
    }

    private void rename() {
        plugin.lang().send(viewer, "shop.rename-prompt");
        plugin.input().ask(viewer, text -> {
            String name = ShopService.trimName(text);
            if (name.length() < 3) {
                plugin.lang().send(viewer, "shop.name-too-short");
                plugin.theme().play(viewer, "error");
            } else {
                service.rename(shop, name);
                plugin.lang().send(viewer, "shop.renamed", Text.p("shop", name));
                plugin.theme().play(viewer, "success");
            }
            reopen();
        }, this::reopen);
    }

    private void toggle() {
        boolean open = !shop.wantsOpen();
        service.setOpen(shop, open);
        if (open) {
            plugin.lang().send(viewer, shop.wantsOpen()
                    ? (shop.hasStock() ? "shop.opened" : "shop.opened-empty")
                    : "shop.open-failed");
        } else {
            plugin.lang().send(viewer, "shop.closed-by-owner");
        }
        plugin.theme().play(viewer, "click");
        refresh();
    }

    private void delete() {
        if (!shop.isEmpty()) {
            plugin.lang().send(viewer, "shop.delete-not-empty");
            plugin.theme().play(viewer, "error");
            return;
        }
        ItemStack subject = ItemBuilder.of(Material.LAVA_BUCKET)
                .name(plugin.lang().component(viewer, "shop.manage.delete.confirm", Text.p("shop", shop.name())))
                .build();
        plugin.getServer().getScheduler().runTask(plugin, () -> new ConfirmMenu(plugin, viewer, subject, () -> {
            if (service.delete(shop)) {
                plugin.lang().send(viewer, "shop.deleted");
                plugin.theme().play(viewer, "success");
                new ShopHubMenu(plugin, viewer, service).open();
            } else {
                plugin.lang().send(viewer, "shop.delete-not-empty");
                reopen();
            }
        }, this::reopen).open());
    }

    private void reopen() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && service.shop(shop.owner()) == shop) {
                new ShopManageMenu(plugin, viewer, service, shop).open();
            }
        });
    }
}
