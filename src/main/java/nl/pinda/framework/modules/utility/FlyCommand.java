package nl.pinda.framework.modules.utility;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.TargetCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /fly [speler]: vliegen aan of uit. */
public final class FlyCommand extends TargetCommand {

    public FlyCommand(PindaFramework plugin) {
        super(plugin, "fly", "Vliegen aan of uit", "pinda.fly", "pinda.fly.others");
    }

    @Override
    protected void apply(CommandSender sender, Player target, boolean self) {
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        if (!enable) {
            target.setFlying(false);
        }
        feedback(sender, target, self,
                enable ? "utility.fly-on" : "utility.fly-off",
                enable ? "utility.fly-on-other" : "utility.fly-off-other");
    }
}
