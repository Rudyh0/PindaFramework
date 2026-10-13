package nl.pinda.framework.modules.duel;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.input.ChatInput;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * De inzet kiezen. Links je eigen inzet (klik op items in je inventory om ze in te zetten, klik
 * op je inzet om iets terug te nemen), rechts die van de tegenstander. Onderaan geld en klaar.
 *
 * <pre>
 * rij 0:  [jij]  .  .  .  [uitleg]  .  .  .  [tegenstander]
 * rij 1-4: jouw inzet (4x4) | scheiding | inzet tegenstander (4x4)
 * rij 5:  [klaar] [-] [geld] [+] [afblazen] . [geld tegenstander] . [klaar tegenstander]
 * </pre>
 */
final class WagerMenu extends Menu {

    static final int SIZE = 54;
    static final int[] OWN = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};
    static final int[] THEIRS = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};
    private static final int OWN_HEAD = 0;
    private static final int INFO = 4;
    private static final int THEIR_HEAD = 8;
    private static final int READY = 45;
    private static final int MINUS = 46;
    private static final int MONEY = 47;
    private static final int PLUS = 48;
    private static final int CANCEL = 49;
    private static final int THEIR_MONEY = 51;
    private static final int THEIR_READY = 53;

    private final Session session;
    private final Session.Side side;
    private boolean asking;

    WagerMenu(PindaFramework plugin, Player viewer, Session session, Session.Side side) {
        super(plugin, viewer, 6, plugin.lang().component(viewer, "duel.wager.title",
                Text.p("player", session.other(side).name)));
        this.session = session;
        this.side = side;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        Session.Side other = session.other(side);
        EconomyService economy = economy();
        boolean money = economy != null && session.settings().stakeMoney();
        boolean items = session.settings().stakeItems();
        TagResolver otherName = Text.p("player", other.name);

        set(OWN_HEAD, ItemBuilder.of(Material.PLAYER_HEAD).head(viewer)
                .name(lang.component(code, "duel.wager.own.name"))
                .lore(lang.components(code, "duel.wager.own.lore", Text.p("money", format(economy, side.money)),
                        Text.p("count", side.itemCount())))
                .build());
        OfflinePlayer otherPlayer = plugin.getServer().getOfflinePlayer(other.uuid);
        set(THEIR_HEAD, ItemBuilder.of(Material.PLAYER_HEAD).head(otherPlayer)
                .name(lang.component(code, "duel.wager.theirs.name", otherName))
                .lore(lang.components(code, "duel.wager.theirs.lore", otherName, Text.p("money", format(economy, other.money)),
                        Text.p("count", other.itemCount())))
                .build());
        set(INFO, ItemBuilder.of(Material.BOOK)
                .name(lang.component(code, "duel.wager.info.name"))
                .lore(lang.components(code, items ? "duel.wager.info.lore" : "duel.wager.info.lore-no-items", otherName,
                        Text.p("seconds", session.secondsLeft(session.settings().wagerSeconds()))))
                .build());

        // Jouw inzet: klik om terug te nemen (links: alles, rechts: één)
        for (int index = 0; index < OWN.length; index++) {
            if (index < side.items.size()) {
                int position = index;
                ItemStack item = side.items.get(index).clone();
                set(OWN[index], withHint(item, lang.components(code, "duel.wager.own-item")), click ->
                        session.unstake(side, position, click.click() == ClickType.RIGHT ? 1 : Integer.MAX_VALUE));
            } else if (!items) {
                set(OWN[index], ItemBuilder.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                        .name(lang.component(code, "duel.wager.items-off-pane")).build());
            }
        }
        // Inzet van de tegenstander (alleen bekijken)
        for (int index = 0; index < THEIRS.length; index++) {
            if (index < other.items.size()) {
                set(THEIRS[index], other.items.get(index).clone());
            } else {
                set(THEIRS[index], ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).hideTooltip().build());
            }
        }
        ItemStack divider = ItemBuilder.of(Material.IRON_BARS).name(lang.component(code, "duel.wager.divider")).build();
        for (int slot : new int[] {13, 22, 31, 40}) {
            set(slot, divider);
        }

        // Klaar
        set(READY, ItemBuilder.of(side.ready ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                .name(lang.component(code, side.ready ? "duel.wager.ready.done" : "duel.wager.ready.waiting"))
                .lore(lang.components(code, side.ready ? "duel.wager.ready.lore-done" : "duel.wager.ready.lore-waiting", otherName))
                .glint(side.ready)
                // Een tick later: zijn beide klaar, dan gaan de menu's dicht (niet midden in een klik).
                .build(), click -> plugin.getServer().getScheduler().runTask(plugin, () -> session.toggleReady(side)));
        set(THEIR_READY, ItemBuilder.of(other.ready ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(lang.component(code, other.ready ? "duel.wager.their-ready.done" : "duel.wager.their-ready.waiting", otherName))
                .build());

        // Geld
        if (money) {
            String step = economy.format(session.settings().moneyStep());
            String bigStep = economy.format(session.settings().moneyStep() * 10);
            set(MINUS, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                    .name(lang.component(code, "duel.wager.minus.name", Text.p("amount", step)))
                    .lore(lang.components(code, "duel.wager.minus.lore", Text.p("amount", step), Text.p("big", bigStep)))
                    .build(), click -> session.setMoney(side, side.money - step(click.click())));
            set(MONEY, ItemBuilder.of(side.money > 0 ? Material.GOLD_INGOT : Material.GOLD_NUGGET)
                    .name(lang.component(code, "duel.wager.money.name", Text.p("amount", economy.format(side.money))))
                    .lore(lang.components(code, "duel.wager.money.lore", Text.p("max", economy.format(session.maxMoney(viewer)))))
                    .glint(side.money > 0)
                    .build(), click -> askAmount());
            set(PLUS, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                    .name(lang.component(code, "duel.wager.plus.name", Text.p("amount", step)))
                    .lore(lang.components(code, "duel.wager.plus.lore", Text.p("amount", step), Text.p("big", bigStep)))
                    .build(), click -> session.setMoney(side, side.money + step(click.click())));
            set(THEIR_MONEY, ItemBuilder.of(other.money > 0 ? Material.GOLD_INGOT : Material.GOLD_NUGGET)
                    .name(lang.component(code, "duel.wager.their-money", otherName, Text.p("amount", economy.format(other.money))))
                    .build());
        }

        set(CANCEL, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "duel.wager.cancel.name"))
                .lore(lang.components(code, "duel.wager.cancel.lore", otherName))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            plugin.getServer().getScheduler().runTask(plugin, () -> session.cancelBy(side));
        });

        // Alleen de rand vullen: de vakjes van je inzet blijven leeg (daar komen je items).
        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build();
        Inventory inventory = getInventory();
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack current = inventory.getItem(slot);
            if ((current == null || current.isEmpty()) && !isOwnSlot(slot)) {
                inventory.setItem(slot, filler);
            }
        }
    }

    private static boolean isOwnSlot(int slot) {
        for (int own : OWN) {
            if (own == slot) {
                return true;
            }
        }
        return false;
    }

    private long step(ClickType click) {
        long step = session.settings().moneyStep();
        return click.isShiftClick() ? step * 10 : step;
    }

    private static String format(EconomyService economy, long cents) {
        return economy == null ? "0" : economy.format(cents);
    }

    private EconomyService economy() {
        return session.economy();
    }

    /** Het item zelf, met onderaan de uitleg hoe je het terugneemt. */
    private static ItemStack withHint(ItemStack item, List<Component> hint) {
        item.editMeta(meta -> {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.addAll(hint);
            meta.lore(lore);
        });
        return item;
    }

    /** Een bedrag typen in de chat; daarna gaat het menu weer open. */
    private void askAmount() {
        ChatInput input = plugin.input();
        if (input == null) {
            return;
        }
        asking = true;
        plugin.theme().play(viewer, "click");
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.lang().send(viewer, "duel.wager.money.ask", Text.p("max", format(economy(), session.maxMoney(viewer))));
            input.ask(viewer, text -> {
                asking = false;
                long cents = Money.parse(text);
                if (cents < 0) {
                    plugin.lang().send(viewer, "duel.wager.money.invalid", Text.p("input", text));
                    plugin.theme().play(viewer, "error");
                } else {
                    session.setMoney(side, cents);
                }
                session.openWager(side);
            }, () -> {
                asking = false;
                session.openWager(side);
            });
        });
    }

    // ============================================================ klikken

    @Override
    protected boolean onRawClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || session.phase != Session.Phase.WAGER) {
            return true;
        }
        if (raw >= SIZE) {
            // Een item uit je eigen inventory inzetten: links de hele stapel, rechts één.
            ClickType click = event.getClick();
            Inventory clicked = event.getClickedInventory();
            if (clicked == null || !(click == ClickType.LEFT || click == ClickType.RIGHT || click.isShiftClick())) {
                return true;
            }
            ItemStack current = clicked.getItem(event.getSlot());
            if (current != null && !current.isEmpty()) {
                session.stake(side, clicked, event.getSlot(), click == ClickType.RIGHT ? 1 : current.getAmount());
            }
            return true;
        }
        runAction(event);
        return true;
    }

    @Override
    protected boolean onRawDrag(InventoryDragEvent event) {
        event.setCancelled(true);
        return true;
    }

    @Override
    protected void onClose(InventoryCloseEvent.Reason reason) {
        if (asking || session.closingMenus || session.phase != Session.Phase.WAGER || !plugin.isEnabled()
                || reason == InventoryCloseEvent.Reason.OPEN_NEW || reason == InventoryCloseEvent.Reason.DISCONNECT) {
            return;
        }
        // Dicht gedaan: in de chat staat hoe hij weer opengaat (of hoe je het afblaast).
        plugin.getServer().getScheduler().runTask(plugin, () -> session.wagerClosed(side));
    }
}
