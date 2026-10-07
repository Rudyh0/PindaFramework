package nl.pinda.framework.modules.economy;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/** /eco give|take|set &lt;speler&gt; &lt;bedrag&gt; [contant|bank]: saldo's beheren (staff). */
public final class EcoCommand extends PindaCommand {

    private final EconomyService economy;

    public EcoCommand(PindaFramework plugin, EconomyService economy) {
        super(plugin, "eco", "Saldo's beheren", EconomyModule.ADMIN, "economy");
        this.economy = economy;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.lang().send(sender, "economy.admin-usage");
            return;
        }
        String action = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "geef" -> "give";
            case "take", "neem" -> "take";
            case "set", "zet" -> "set";
            default -> null;
        };
        if (action == null) {
            plugin.lang().send(sender, "economy.admin-usage");
            return;
        }
        long cents = Money.parse(args[2]);
        if (cents < 0 || (cents == 0 && !action.equals("set"))) {
            plugin.lang().send(sender, "economy.invalid-amount", Text.p("input", args[2]));
            return;
        }
        boolean bank = args.length < 4 || !(args[3].equalsIgnoreCase("contant") || args[3].equalsIgnoreCase("cash"));
        String where = plugin.lang().raw(plugin.lang().languageOf(sender), bank ? "economy.where-bank" : "economy.where-cash");
        String note = "door " + sender.getName();
        String name = args[1];

        plugin.players().findKnown(name).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (known == null) {
                plugin.lang().send(sender, "general.player-unknown", Text.p("player", name));
                return;
            }
            CompletableFuture<Void> result = switch (action) {
                case "give" -> economy.give(known.uuid(), cents, bank, "admin-give", note);
                case "take" -> economy.give(known.uuid(), -cents, bank, "admin-take", note);
                default -> economy.set(known.uuid(), cents, bank, note);
            };
            result.whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Saldo aanpassen mislukt", error);
                    plugin.lang().send(sender, "general.command-error");
                    return;
                }
                plugin.lang().send(sender, "economy.admin-" + (action.equals("give") ? "given" : action.equals("take") ? "taken" : "set"),
                        Text.p("player", known.name()), Text.p("amount", economy.format(cents)),
                        Text.p("where", where == null ? "" : where));
                plugin.theme().play(sender, "success");
            }));
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + name + " niet opzoeken", error);
            return null;
        });
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return switch (argIndex(args)) {
            case 0 -> List.of("give", "take", "set");
            case 1 -> visiblePlayers(sender);
            case 3 -> List.of("bank", "contant");
            default -> List.of();
        };
    }
}
