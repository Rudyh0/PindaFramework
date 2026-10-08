package nl.pinda.framework.modules.backpack;

import java.util.List;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /backpack (/bp, /rugtas, /rugzak, /rt, /rz) - je rugtas openen
 * /backpack &lt;speler&gt; - de rugtas van iemand anders bekijken (staff)
 */
final class BackpackCommand extends PindaCommand {

    private final BackpackModule module;

    BackpackCommand(PindaFramework plugin, BackpackModule module) {
        super(plugin, "backpack", "Je rugtas openen", BackpackModule.USE, "bp", "rugtas", "rugzak", "rt", "rz");
        overrideAliases();
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0) {
            module.openOwn(player);
            return;
        }
        if (!checkPermission(sender, BackpackModule.OTHERS)) {
            return;
        }
        plugin.players().findKnown(args[0]).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (known == null) {
                plugin.lang().send(player, "general.player-not-found", Text.p("player", args[0]));
                return;
            }
            module.open(player, known.uuid(), known.name());
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + args[0] + " niet opzoeken", error);
            return null;
        });
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(BackpackModule.OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
