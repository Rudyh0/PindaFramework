package nl.pinda.framework.modules.tpa;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /tpa &lt;speler&gt; (naar de ander toe) en /tpahere &lt;speler&gt; (de ander naar jou). */
public final class TpaCommand extends PindaCommand {

    private final TpaModule module;
    private final boolean here;

    public TpaCommand(PindaFramework plugin, TpaModule module, boolean here) {
        super(plugin, here ? "tpahere" : "tpa",
                here ? "Vraag een speler om naar jou te teleporteren" : "Vraag of je naar een speler mag teleporteren",
                here ? TpaModule.HERE : TpaModule.USE);
        this.module = module;
        this.here = here;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(player, "tpa.usage", Text.p("command", name()));
            return;
        }
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            plugin.lang().send(player, "general.player-not-found", Text.p("player", args[0]));
            plugin.theme().play(player, "error");
            return;
        }
        module.request(player, target, here);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0) {
            return List.of();
        }
        boolean allowSelf = module.allowSelf();
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(online -> allowSelf || online != sender)
                .map(Player::getName)
                .toList();
    }
}
