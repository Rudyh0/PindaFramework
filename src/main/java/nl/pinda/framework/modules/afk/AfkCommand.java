package nl.pinda.framework.modules.afk;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /afk [reden]: zet jezelf AFK, of haalt je eruit. */
public final class AfkCommand extends PindaCommand {

    private final AfkModule module;

    public AfkCommand(PindaFramework plugin, AfkModule module) {
        super(plugin, "afk", "Zet jezelf AFK", AfkModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (module.isAfk(player)) {
            module.activity(player);
            return;
        }
        String reason = String.join(" ", args).trim();
        module.setAfk(player, true, reason.isEmpty() ? null : reason);
    }
}
