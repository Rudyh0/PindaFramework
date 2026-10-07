package nl.pinda.framework.modules.economy;

import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /bank: opent de bank. Of direct: /bank storten|opnemen &lt;bedrag|alles&gt;. */
public final class BankCommand extends PindaCommand {

    private final EconomyService economy;

    public BankCommand(PindaFramework plugin, EconomyService economy) {
        super(plugin, "bank", "Open de bank", EconomyModule.BANK);
        this.economy = economy;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            new BankMenu(plugin, player, economy).open();
            plugin.theme().play(player, "menu-open");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        Boolean deposit = switch (action) {
            case "storten", "stort", "deposit", "d" -> true;
            case "opnemen", "neem", "withdraw", "w" -> false;
            default -> null;
        };
        if (deposit == null) {
            plugin.lang().send(player, "economy.bank-usage");
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(player, "economy.bank-usage");
            return;
        }

        Account account = economy.account(player);
        boolean all = args[1].equalsIgnoreCase("alles") || args[1].equalsIgnoreCase("all");
        long cents = all ? (deposit ? account.cash() : account.bank()) : Money.parse(args[1]);
        if (cents <= 0) {
            if (all) {
                plugin.lang().send(player, deposit ? "economy.not-enough-cash" : "economy.not-enough-bank",
                        Text.p("amount", economy.format(deposit ? account.cash() : account.bank())));
            } else {
                plugin.lang().send(player, "economy.invalid-amount", Text.p("input", args[1]));
            }
            plugin.theme().play(player, "error");
            return;
        }
        BankMenu.transfer(plugin, economy, player, cents, deposit);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            return List.of("storten", "opnemen");
        }
        if (index == 1) {
            return List.of("alles", "100", "1000");
        }
        return List.of();
    }
}
