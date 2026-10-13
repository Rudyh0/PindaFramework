package nl.pinda.framework.modules.rules;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /regels - de regels van de server als boek.
 * /regels &lt;speler&gt; - het boek bij iemand anders openen (staff).
 */
final class RulesCommand extends PindaCommand {

    private final RulesModule module;

    RulesCommand(PindaFramework plugin, RulesModule module) {
        super(plugin, "regels", "De regels van de server", RulesModule.USE, "rules");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase(RulesModule.READ_ARGUMENT)) {
            // De knop "Gelezen" onderaan het boek
            if (sender instanceof Player player) {
                module.closed(player);
            }
            return;
        }
        if (args.length > 0 && sender.hasPermission(RulesModule.OTHERS)) {
            Player target = findPlayerOrFail(sender, args[0]);
            if (target == null) {
                return;
            }
            if (module.open(target)) {
                plugin.lang().send(sender, "rules.shown", Text.p("player", target.getName()));
            } else {
                plugin.lang().send(sender, "rules.empty");
            }
            return;
        }
        Player player = asPlayer(sender);
        if (player != null && !module.open(player)) {
            plugin.lang().send(player, "rules.empty");
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(RulesModule.OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
