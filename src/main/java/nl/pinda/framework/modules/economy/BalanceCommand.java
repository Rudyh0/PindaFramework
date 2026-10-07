package nl.pinda.framework.modules.economy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /balance [speler]: laat contant geld, bank en totaal zien. */
public final class BalanceCommand extends PindaCommand {

    private final EconomyService economy;

    public BalanceCommand(PindaFramework plugin, EconomyService economy) {
        super(plugin, "balance", "Bekijk je saldo", EconomyModule.BALANCE, "bal", "money", "geld", "saldo");
        this.economy = economy;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            Player player = asPlayer(sender);
            if (player != null) {
                show(sender, player.getName(), economy.account(player), true);
            }
            return;
        }
        if (!checkPermission(sender, EconomyModule.BALANCE_OTHERS)) {
            return;
        }
        String name = args[0];
        plugin.players().findKnown(name)
                .thenCompose(known -> known == null
                        ? CompletableFuture.<Lookup>completedFuture(null)
                        : economy.peek(known.uuid()).thenApply(account -> new Lookup(known.name(), account)))
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (result == null) {
                        plugin.lang().send(sender, "general.player-unknown", Text.p("player", name));
                        return;
                    }
                    Account account = result.account() != null
                            ? result.account()
                            : new Account(UUID.randomUUID(), 0, 0, 0);
                    show(sender, result.name(), account, false);
                }))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Kon saldo van " + name + " niet opzoeken", error);
                    return null;
                });
    }

    private record Lookup(String name, Account account) {
    }

    private void show(CommandSender sender, String name, Account account, boolean own) {
        plugin.lang().send(sender, own ? "economy.balance-own" : "economy.balance",
                Text.p("player", name),
                Text.p("cash", economy.format(account.cash())),
                Text.p("bank", economy.format(account.bank())),
                Text.p("total", economy.format(account.total())));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(EconomyModule.BALANCE_OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
