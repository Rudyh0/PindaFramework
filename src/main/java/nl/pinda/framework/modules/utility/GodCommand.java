package nl.pinda.framework.modules.utility;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.TargetCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /god [speler]: onkwetsbaar en geen honger. Gaat uit bij uitloggen. */
public final class GodCommand extends TargetCommand {

    private final UtilityModule module;

    public GodCommand(PindaFramework plugin, UtilityModule module) {
        super(plugin, "god", "Onkwetsbaar aan of uit", "pinda.god", "pinda.god.others", "godmode");
        this.module = module;
    }

    @Override
    protected void apply(CommandSender sender, Player target, boolean self) {
        boolean enabled = module.toggleGod(target);
        if (enabled) {
            target.setFireTicks(0);
        }
        feedback(sender, target, self,
                enabled ? "utility.god-on" : "utility.god-off",
                enabled ? "utility.god-on-other" : "utility.god-off-other");
    }
}
