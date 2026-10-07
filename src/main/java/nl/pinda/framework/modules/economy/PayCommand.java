package nl.pinda.framework.modules.economy;

import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /pay &lt;speler&gt; &lt;bedrag&gt;: betaal iemand. Het geld komt bij de ontvanger contant binnen.
 * Werkt ook als de ontvanger offline is.
 */
public final class PayCommand extends PindaCommand {

    private final EconomyService economy;

    public PayCommand(PindaFramework plugin, EconomyService economy) {
        super(plugin, "pay", "Betaal een speler", EconomyModule.PAY, "betaal");
        this.economy = economy;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            plugin.lang().send(player, "economy.usage-pay");
            return;
        }
        long cents = Money.parse(args[1]);
        if (cents <= 0) {
            fail(player, "economy.invalid-amount", Text.p("input", args[1]));
            return;
        }
        if (cents < economy.payMinimum()) {
            fail(player, "economy.pay-minimum", Text.p("amount", economy.format(economy.payMinimum())));
            return;
        }
        String targetName = args[0];
        Player online = findPlayer(player, targetName);
        if (online != null) {
            pay(player, new KnownPlayer(online.getUniqueId(), online.getName()), cents);
            return;
        }
        plugin.players().findKnown(targetName).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (known == null) {
                fail(player, "general.player-unknown", Text.p("player", targetName));
                return;
            }
            pay(player, known, cents);
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + targetName + " niet opzoeken", error);
            return null;
        });
    }

    private void pay(Player payer, KnownPlayer target, long cents) {
        if (target.uuid().equals(payer.getUniqueId())) {
            fail(payer, "economy.pay-self");
            return;
        }
        if (!economy.take(payer, cents, "pay", "aan " + target.name())) {
            fail(payer, "economy.not-enough",
                    Text.p("amount", economy.format(economy.spendable(economy.account(payer)))));
            return;
        }
        String amount = economy.format(cents);
        economy.give(target.uuid(), cents, false, "pay-received", "van " + payer.getName())
                .whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Betaling aan " + target.name() + " mislukt", error);
                        economy.give(payer.getUniqueId(), cents, false, "refund", "betaling mislukt");
                        fail(payer, "economy.pay-failed");
                        return;
                    }
                    plugin.lang().send(payer, "economy.paid", Text.p("amount", amount), Text.p("player", target.name()));
                    plugin.theme().play(payer, "success");
                    Player receiver = plugin.getServer().getPlayer(target.uuid());
                    if (receiver != null) {
                        plugin.lang().send(receiver, "economy.received", Text.p("amount", amount),
                                Text.p("player", payer.getName()));
                        plugin.theme().play(receiver, "message");
                    }
                }));
    }

    private void fail(Player player, String key, TagResolver... resolvers) {
        plugin.lang().send(player, key, resolvers);
        plugin.theme().play(player, "error");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0) {
            return visiblePlayers(sender).stream().filter(name -> !name.equals(sender.getName())).toList();
        }
        return List.of();
    }
}
