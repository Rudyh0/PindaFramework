package nl.pinda.framework.modules.staff;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /invsee &lt;speler&gt;: bekijk de inventory van een online speler.
 * Aanpassen kan alleen met pinda.invsee.modify.
 */
public final class InvseeCommand extends PindaCommand {

    private final StaffModule module;

    public InvseeCommand(PindaFramework plugin, StaffModule module) {
        super(plugin, "invsee", "Bekijk de inventory van een speler", StaffModule.INVSEE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player viewer = asPlayer(sender);
        if (viewer == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(viewer, "invsee.usage");
            return;
        }
        Player target = findPlayerOrFail(viewer, args[0]);
        if (target == null) {
            return;
        }
        if (target.getUniqueId().equals(viewer.getUniqueId())) {
            plugin.lang().send(viewer, "invsee.self");
            plugin.theme().play(viewer, "error");
            return;
        }
        module.openInventory(viewer, target);
        plugin.lang().send(viewer, viewer.hasPermission(StaffModule.INVSEE_MODIFY) ? "invsee.opened" : "invsee.opened-read-only",
                Text.p("player", target.getName()));
        plugin.theme().play(viewer, "menu-open");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0) {
            return visiblePlayers(sender).stream().filter(name -> !name.equals(sender.getName())).toList();
        }
        return List.of();
    }
}
