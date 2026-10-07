package nl.pinda.framework.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Basis voor alle commando's van het framework.
 *
 * <p>Fouten tijdens het uitvoeren worden opgevangen en gelogd, zodat een speler altijd een
 * nette melding krijgt. Suggesties worden automatisch gefilterd op wat de speler al typte.
 */
public abstract class PindaCommand implements BasicCommand {

    protected final PindaFramework plugin;
    private final String name;
    private final String description;
    private final String permission;
    private final List<String> defaultAliases;

    /**
     * @param name        de naam van het commando, zonder slash
     * @param description korte omschrijving (zichtbaar in /help)
     * @param permission  permissie om het commando te zien en te gebruiken, of null voor iedereen
     * @param aliases     standaard-aliassen; beheerders kunnen die aanpassen in config.yml
     */
    protected PindaCommand(PindaFramework plugin, String name, String description, String permission, String... aliases) {
        this.plugin = plugin;
        this.name = name;
        this.description = description;
        this.permission = permission;
        this.defaultAliases = List.of(aliases);
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final List<String> defaultAliases() {
        return defaultAliases;
    }

    @Override
    public String permission() {
        return permission;
    }

    @Override
    public final void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        try {
            run(sender, args);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Fout bij het uitvoeren van /" + name + " door " + sender.getName(), e);
            plugin.lang().send(sender, "general.command-error");
        }
    }

    @Override
    public final Collection<String> suggest(CommandSourceStack source, String[] args) {
        try {
            String current = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            return complete(source.getSender(), args).stream()
                    .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(current))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Voert het commando uit. */
    protected abstract void run(CommandSender sender, String[] args);

    /**
     * Suggesties voor het argument dat nu getypt wordt. Let op: bij het eerste argument kan
     * {@code args} leeg zijn of één (lege) waarde bevatten.
     */
    protected List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }

    // -------------------------------------------------------------- helpers

    /** Geeft de speler terug, of stuurt een melding en geeft null als het de console is. */
    protected Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.lang().send(sender, "general.player-only");
        return null;
    }

    /** Controleert een permissie en stuurt een melding als die ontbreekt. */
    protected boolean checkPermission(CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        plugin.lang().send(sender, "general.no-permission");
        plugin.theme().play(sender, "error");
        return false;
    }

    /** Index van het argument dat nu getypt wordt (0 = eerste argument). */
    protected static int argIndex(String[] args) {
        return Math.max(0, args.length - 1);
    }
}
