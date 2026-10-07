package nl.pinda.framework.modules.staff;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.TargetCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /vanish [speler]: onzichtbaar voor spelers zonder pinda.vanish.see. */
public final class VanishCommand extends TargetCommand {

    private final StaffModule module;

    public VanishCommand(PindaFramework plugin, StaffModule module) {
        super(plugin, "vanish", "Word onzichtbaar", StaffModule.VANISH, StaffModule.VANISH_OTHERS, "v");
        this.module = module;
    }

    @Override
    protected void apply(CommandSender sender, Player target, boolean self) {
        boolean vanished = module.toggleVanish(target);
        feedback(sender, target, self,
                vanished ? "vanish.enabled" : "vanish.disabled",
                vanished ? "vanish.on-other" : "vanish.off-other");
    }
}
