package nl.pinda.framework.modules.tpa;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /tpacancel: trekt je openstaande verzoek in. */
public final class TpaCancelCommand extends PindaCommand {

    private final TpaModule module;

    public TpaCancelCommand(PindaFramework plugin, TpaModule module) {
        super(plugin, "tpacancel", "Trek je TPA-verzoek in", TpaModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            module.cancel(player);
        }
    }
}
