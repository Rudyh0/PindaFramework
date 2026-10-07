package nl.pinda.framework.modules.gamemode;

import java.util.ArrayList;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /gm &lt;modus&gt; [speler], of een snelkoppeling zoals /gmc [speler].
 * Bij een snelkoppeling ligt de modus vast.
 */
public final class GamemodeCommand extends PindaCommand {

    private static final List<String> MODE_NAMES = List.of("survival", "creative", "adventure", "spectator");

    private final GamemodeModule module;
    private final GameMode fixedMode;

    public GamemodeCommand(PindaFramework plugin, GamemodeModule module, String label, GameMode fixedMode) {
        super(plugin, label,
                fixedMode == null ? "Verander je spelmodus" : "Spelmodus " + GamemodeModule.key(fixedMode),
                fixedMode == null ? null : GamemodeModule.permission(fixedMode));
        this.module = module;
        this.fixedMode = fixedMode;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        if (fixedMode != null) {
            return sender.hasPermission(GamemodeModule.permission(fixedMode));
        }
        for (GameMode mode : GameMode.values()) {
            if (sender.hasPermission(GamemodeModule.permission(mode))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        GameMode mode = fixedMode;
        int playerArg = 0;
        if (mode == null) {
            if (args.length == 0 || args[0].isBlank()) {
                plugin.lang().send(sender, "gamemode.usage");
                return;
            }
            mode = GamemodeModule.parse(args[0]);
            if (mode == null) {
                plugin.lang().send(sender, "gamemode.unknown-mode", Text.p("mode", args[0]));
                plugin.theme().play(sender, "error");
                return;
            }
            playerArg = 1;
        }

        Player target;
        if (args.length > playerArg && !args[playerArg].isBlank()) {
            target = findPlayerOrFail(sender, args[playerArg]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.lang().send(sender, "gamemode.usage-console", Text.p("command", name()));
            return;
        }
        module.change(sender, target, mode);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        int playerIndex = fixedMode == null ? 1 : 0;
        if (fixedMode == null && index == 0) {
            List<String> modes = new ArrayList<>();
            for (String name : MODE_NAMES) {
                GameMode mode = GamemodeModule.parse(name);
                if (mode != null && sender.hasPermission(GamemodeModule.permission(mode))) {
                    modes.add(name);
                }
            }
            return modes;
        }
        if (index == playerIndex && sender.hasPermission(GamemodeModule.OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
