package nl.pinda.framework.modules.msg;

import java.util.Arrays;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /msg &lt;speler&gt; &lt;bericht&gt; (ook /tell, /w, /whisper, /m, /pm). */
public final class MsgCommand extends PindaCommand {

    private final MsgModule module;

    public MsgCommand(PindaFramework plugin, MsgModule module) {
        super(plugin, "msg", "Stuur een privébericht", MsgModule.USE, "tell", "w", "whisper", "m", "pm");
        this.module = module;
        overrideAliases();
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            plugin.lang().send(sender, "msg.usage");
            return;
        }
        Player target = findPlayerOrFail(sender, args[0]);
        if (target == null) {
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        module.send(sender, target, message);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0) {
            return List.of();
        }
        return visiblePlayers(sender).stream()
                .filter(name -> !name.equals(sender.getName()))
                .toList();
    }
}
