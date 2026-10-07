package nl.pinda.framework.modules.spawn;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /setspawn: zet de spawn op je huidige plek. */
public final class SetSpawnCommand extends PindaCommand {

    private final SpawnModule module;

    public SetSpawnCommand(PindaFramework plugin, SpawnModule module) {
        super(plugin, "setspawn", "Zet de spawn op je huidige plek", SpawnModule.SET);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        module.setSpawn(player.getLocation());
        plugin.lang().send(player, "spawn.set");
        plugin.theme().play(player, "success");
    }
}
