package nl.pinda.framework.modules.timber;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /timber - bomen in één keer omhakken aan- of uitzetten (kan ook in /instellingen). */
final class TimberCommand extends PindaCommand {

    private final TimberModule module;

    TimberCommand(PindaFramework plugin, TimberModule module) {
        super(plugin, "timber", "Bomen in één keer omhakken aan of uit", TimberModule.USE, "bomenkappen", "treefeller");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        boolean enabled = module.toggle(player);
        plugin.lang().send(player, enabled ? "timber.enabled" : "timber.disabled");
        plugin.theme().play(player, enabled ? "success" : "click");
    }
}
