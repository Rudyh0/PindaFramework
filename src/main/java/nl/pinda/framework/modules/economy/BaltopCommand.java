package nl.pinda.framework.modules.economy;

import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/** /baltop: de 10 rijkste spelers (contant + bank). */
public final class BaltopCommand extends PindaCommand {

    private final EconomyService economy;

    public BaltopCommand(PindaFramework plugin, EconomyService economy) {
        super(plugin, "baltop", "De rijkste spelers", EconomyModule.BALTOP, "geldtop", "moneytop");
        this.economy = economy;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        economy.top(10).thenAccept(entries -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (entries.isEmpty()) {
                plugin.lang().send(sender, "economy.baltop-empty");
                return;
            }
            plugin.lang().send(sender, "economy.baltop-header");
            int rank = 1;
            for (EconomyService.TopEntry entry : entries) {
                plugin.lang().send(sender, "economy.baltop-entry",
                        Text.p("rank", rank++), Text.p("player", entry.name()),
                        Text.p("amount", economy.format(entry.total())));
            }
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de baltop niet laden", error);
            return null;
        });
    }
}
