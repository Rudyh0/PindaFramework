package nl.pinda.framework.modules.tpa;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /tpaccept [speler]: accepteert het (nieuwste) verzoek. */
public final class TpAcceptCommand extends PindaCommand {

    private final TpaModule module;

    public TpAcceptCommand(PindaFramework plugin, TpaModule module) {
        super(plugin, "tpaccept", "Accepteer een TPA-verzoek", TpaModule.USE, "tpyes");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player != null) {
            module.accept(player, args.length == 0 || args[0].isBlank() ? null : args[0]);
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
