package nl.pinda.framework.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.settings.SettingsMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /pinda: beheer van het framework (herladen, modules, setupmenu). */
public final class AdminCommand extends PindaCommand {

    private static final String RELOAD = "pinda.admin.reload";
    private static final String MODULES = "pinda.admin.modules";
    private static final String SETUP = "pinda.admin.setup";

    public AdminCommand(PindaFramework plugin) {
        super(plugin, "pinda", "Beheer van PindaFramework", null);
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(RELOAD) || sender.hasPermission(MODULES) || sender.hasPermission(SETUP);
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "modules" -> modules(sender);
            case "setup" -> setup(sender, args);
            default -> plugin.lang().send(sender, "admin.help", Text.p("version", plugin.version()));
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(RELOAD)) {
                options.add("reload");
            }
            if (sender.hasPermission(MODULES)) {
                options.add("modules");
            }
            if (sender.hasPermission(SETUP)) {
                options.add("setup");
            }
            return options;
        }
        if (index == 1 && args[0].equalsIgnoreCase("setup") && sender.hasPermission(SETUP)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }

    private void reload(CommandSender sender) {
        if (!checkPermission(sender, RELOAD)) {
            return;
        }
        try {
            long millis = plugin.reload();
            plugin.lang().send(sender, "admin.reloaded", Text.p("time", millis));
            plugin.theme().play(sender, "success");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Herladen mislukt", e);
            plugin.lang().send(sender, "admin.reload-failed");
            plugin.theme().play(sender, "error");
        }
    }

    private void modules(CommandSender sender) {
        if (!checkPermission(sender, MODULES)) {
            return;
        }
        LanguageManager lang = plugin.lang();
        Collection<PindaModule> modules = plugin.modules().all();
        lang.send(sender, "admin.modules-header", Text.p("count", modules.size()));
        for (PindaModule module : modules) {
            Component status = lang.component(sender, module.isEnabled() ? "general.enabled" : "general.disabled");
            lang.send(sender, "admin.modules-entry", Text.p("module", module.id()), Text.c("status", status));
        }
        lang.send(sender, "admin.modules-footer");
    }

    private void setup(CommandSender sender, String[] args) {
        if (!checkPermission(sender, SETUP)) {
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = findPlayerOrFail(sender, args[1]);
            if (target == null) {
                return;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return;
            }
        }
        new SettingsMenu(plugin, target, SettingsMenu.Mode.SETUP).open();
        plugin.theme().play(target, "menu-open");
        if (target != sender) {
            plugin.lang().send(sender, "admin.setup-opened", Text.p("player", target.getName()));
        }
    }
}
