package nl.pinda.framework.command;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Basis voor commando's die op jezelf of (met een extra permissie) op een ander werken,
 * zoals /fly [speler] en /heal [speler].
 */
public abstract class TargetCommand extends PindaCommand {

    private final String othersPermission;

    protected TargetCommand(PindaFramework plugin, String name, String description, String permission,
                            String othersPermission, String... aliases) {
        super(plugin, name, description, permission, aliases);
        this.othersPermission = othersPermission;
    }

    @Override
    protected final void run(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 0 && !args[0].isBlank()) {
            target = findPlayerOrFail(sender, args[0]);
            if (target == null) {
                return;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return;
            }
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !checkPermission(sender, othersPermission)) {
            return;
        }
        apply(sender, target, self);
    }

    /** Voert het commando uit op het doel. */
    protected abstract void apply(CommandSender sender, Player target, boolean self);

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(othersPermission)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }

    /**
     * Meldingen na het uitvoeren: het doel krijgt de "zelf"-melding, en wie het voor een ander
     * deed krijgt de "ander"-melding met &lt;player&gt;.
     */
    protected void feedback(CommandSender sender, Player target, boolean self, String selfKey, String otherKey,
                            TagResolver... extra) {
        plugin.lang().send(target, selfKey, extra);
        plugin.theme().play(target, "success");
        if (!self) {
            List<TagResolver> resolvers = new ArrayList<>(List.of(extra));
            resolvers.add(Text.p("player", target.getName()));
            plugin.lang().send(sender, otherKey, resolvers.toArray(TagResolver[]::new));
            plugin.theme().play(sender, "success");
        }
    }
}
