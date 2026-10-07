package nl.pinda.framework.modules.economy;

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

/**
 * Het bankmenu: links storten (met kosten), rechts opnemen (gratis).
 *
 * <pre>
 * rij 0: [info]
 * rij 1: stort 10 | 100 | 1.000        neem 10 | 100 | 1.000 op
 * rij 2: alles storten                  alles opnemen
 * rij 3: [sluiten]
 * </pre>
 */
public final class BankMenu extends Menu {

    private static final long[] AMOUNTS = {10_00, 100_00, 1_000_00};
    private static final Material[] DEPOSIT_ICONS = {Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK};
    private static final Material[] WITHDRAW_ICONS = {Material.IRON_NUGGET, Material.IRON_INGOT, Material.IRON_BLOCK};
    private static final int[] DEPOSIT_SLOTS = {10, 11, 12};
    private static final int[] WITHDRAW_SLOTS = {14, 15, 16};

    private final EconomyService economy;

    public BankMenu(PindaFramework plugin, Player viewer, EconomyService economy) {
        super(plugin, viewer, 4, plugin.lang().component(viewer, "economy.bank-menu.title"));
        this.economy = economy;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        Account account = economy.account(viewer);

        set(4, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name(lang.component(code, "economy.bank-menu.info.name"))
                .lore(lang.components(code, "economy.bank-menu.info.lore",
                        Text.p("cash", economy.format(account.cash())),
                        Text.p("bank", economy.format(account.bank())),
                        Text.p("fee-percent", economy.formatPercent(economy.depositFeePercent()))))
                .build());

        for (int i = 0; i < AMOUNTS.length; i++) {
            long amount = AMOUNTS[i];
            set(DEPOSIT_SLOTS[i], depositButton(code, DEPOSIT_ICONS[i], amount, account.cash() >= amount, false),
                    click -> transferAndRefresh(amount, true));
            set(WITHDRAW_SLOTS[i], withdrawButton(code, WITHDRAW_ICONS[i], amount, account.bank() >= amount, false),
                    click -> transferAndRefresh(amount, false));
        }
        set(20, depositButton(code, Material.CHEST, account.cash(), account.cash() > 0, true),
                click -> transferAndRefresh(economy.account(viewer).cash(), true));
        set(24, withdrawButton(code, Material.BARREL, account.bank(), account.bank() > 0, true),
                click -> transferAndRefresh(economy.account(viewer).bank(), false));

        set(31, ItemBuilder.of(Material.BARRIER)
                .name(lang.component(code, "settings.menu.close.name"))
                .build(), click -> closeLater());

        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private ItemStack depositButton(String code, Material icon, long amount, boolean available, boolean all) {
        LanguageManager lang = plugin.lang();
        long fee = economy.depositFee(amount);
        List<Component> lore = available
                ? lang.components(code, "economy.bank-menu.deposit.lore",
                        Text.p("fee", economy.format(fee)), Text.p("credited", economy.format(amount - fee)))
                : List.of(lang.component(code, "economy.bank-menu.deposit.unavailable"));
        return ItemBuilder.of(icon)
                .name(lang.component(code, all ? "economy.bank-menu.deposit-all" : "economy.bank-menu.deposit.name",
                        Text.p("amount", economy.format(amount))))
                .lore(lore)
                .build();
    }

    private ItemStack withdrawButton(String code, Material icon, long amount, boolean available, boolean all) {
        LanguageManager lang = plugin.lang();
        List<Component> lore = available
                ? lang.components(code, "economy.bank-menu.withdraw.lore")
                : List.of(lang.component(code, "economy.bank-menu.withdraw.unavailable"));
        return ItemBuilder.of(icon)
                .name(lang.component(code, all ? "economy.bank-menu.withdraw-all" : "economy.bank-menu.withdraw.name",
                        Text.p("amount", economy.format(amount))))
                .lore(lore)
                .build();
    }

    private void transferAndRefresh(long cents, boolean deposit) {
        transfer(plugin, economy, viewer, cents, deposit);
        refresh();
    }

    /** Stort of neemt op, met de juiste meldingen. Ook gebruikt door /bank storten|opnemen. */
    static void transfer(PindaFramework plugin, EconomyService economy, Player player, long cents, boolean deposit) {
        Account account = economy.account(player);
        if (deposit) {
            EconomyService.Deposit result = cents > 0 ? economy.deposit(player, cents) : null;
            if (result == null) {
                plugin.lang().send(player, "economy.not-enough-cash", Text.p("amount", economy.format(account.cash())));
                plugin.theme().play(player, "error");
                return;
            }
            plugin.lang().send(player, "economy.deposited",
                    Text.p("amount", economy.format(result.amount())),
                    Text.p("fee", economy.format(result.fee())),
                    Text.p("credited", economy.format(result.credited())));
        } else {
            if (cents <= 0 || !economy.withdrawFromBank(player, cents)) {
                plugin.lang().send(player, "economy.not-enough-bank", Text.p("amount", economy.format(account.bank())));
                plugin.theme().play(player, "error");
                return;
            }
            plugin.lang().send(player, "economy.withdrew", Text.p("amount", economy.format(cents)));
        }
        plugin.theme().play(player, "success");
    }
}
