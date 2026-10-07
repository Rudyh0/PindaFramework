package nl.pinda.framework.modules.settings;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /instellingen: opent het menu met persoonlijke instellingen. */
public final class SettingsCommand extends PindaCommand {

    public SettingsCommand(PindaFramework plugin) {
        super(plugin, "instellingen", "Open je persoonlijke instellingen", "pinda.settings.use", "settings");
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        new SettingsMenu(plugin, player, SettingsMenu.Mode.NORMAL).open();
        plugin.theme().play(player, "menu-open");
    }
}
