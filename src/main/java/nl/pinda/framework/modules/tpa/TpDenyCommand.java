package nl.pinda.framework.modules.tpa;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /tpdeny [speler]: weigert het (nieuwste) verzoek. */
public final class TpDenyCommand extends PindaCommand {

    private final TpaModule module;

    public TpDenyCommand(PindaFramework plugin, TpaModule module) {
        super(plugin, "tpdeny", "Weiger een TPA-verzoek", TpaModule.USE, "tpno");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            module.deny(player, args.length == 0 || args[0].isBlank() ? null : args[0]);
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender instanceof Player player) {
            return module.incomingNames(player);
        }
        return List.of();
    }
}
