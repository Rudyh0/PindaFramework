package nl.pinda.framework.command;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Registreert de commando's van alle modules bij Paper.
 *
 * <p>Per commando kan in config.yml onder {@code commands.<naam>} worden ingesteld of het
 * aan staat en welke aliassen het heeft.
 */
public final class CommandManager {

    private final PindaFramework plugin;
    private final List<PindaCommand> commands = new ArrayList<>();
    private boolean hooked;

    public CommandManager(PindaFramework plugin) {
        this.plugin = plugin;
    }

    public void register(PindaCommand command) {
        commands.add(command);
    }

    public List<PindaCommand> all() {
        return List.copyOf(commands);
    }

    /** Koppelt de commando's aan Paper. Wordt één keer aangeroepen bij het opstarten. */
    public void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            ConfigurationSection section = plugin.mainConfig().getConfigurationSection("commands");
            for (PindaCommand command : commands) {
                String name = command.name();
                if (section != null && !section.getBoolean(name + ".enabled", true)) {
                    continue;
                }
                List<String> aliases = section != null && section.isList(name + ".aliases")
                        ? section.getStringList(name + ".aliases")
                        : command.defaultAliases();
                if (command.overridesAliases()) {
                    // Als eigen label registreren, zodat ze bestaande (vanilla) commando's vervangen.
                    registrar.register(name, command.description(), List.of(), command);
                    for (String alias : aliases) {
                        registrar.register(alias, command.description(), List.of(), command);
                    }
                } else {
                    registrar.register(name, command.description(), aliases, command);
                }
            }
        });
    }
}
