package nl.pinda.framework.modules.gamemode;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Snel van spelmodus wisselen: /gm, /gmc, /gms, /gma en /gmsp. */
public final class GamemodeModule extends PindaModule {

    public static final String OTHERS = "pinda.gamemode.others";

    public GamemodeModule(PindaFramework plugin) {
        super(plugin, "gamemode");
    }

    @Override
    protected void onEnable() {
        command(new GamemodeCommand(plugin, this, "gm", null));
        command(new GamemodeCommand(plugin, this, "gmc", GameMode.CREATIVE));
        command(new GamemodeCommand(plugin, this, "gms", GameMode.SURVIVAL));
        command(new GamemodeCommand(plugin, this, "gma", GameMode.ADVENTURE));
        command(new GamemodeCommand(plugin, this, "gmsp", GameMode.SPECTATOR));
    }

    /** Herkent 0-3, s/c/a/sp, Engelse en Nederlandse namen. Geeft null als het niets is. */
    public static GameMode parse(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival", "overleven" -> GameMode.SURVIVAL;
            case "1", "c", "creative", "creatief" -> GameMode.CREATIVE;
            case "2", "a", "adventure", "avontuur" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator", "toeschouwer" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    public static String key(GameMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    public static String permission(GameMode mode) {
        return "pinda.gamemode." + key(mode);
    }

    /** Verandert de spelmodus, met controle op permissies en nette meldingen. */
    public void change(CommandSender sender, Player target, GameMode mode) {
        if (!sender.hasPermission(permission(mode))) {
            fail(sender, "general.no-permission");
            return;
        }
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !sender.hasPermission(OTHERS)) {
            fail(sender, "general.no-permission");
            return;
        }

        target.setGameMode(mode);
        String modeKey = "gamemode.modes." + key(mode);
        if (self) {
            plugin.lang().send(target, "gamemode.changed", Text.c("mode", plugin.lang().component(target, modeKey)));
            plugin.theme().play(target, "success");
            return;
        }
        Component senderMode = plugin.lang().component(sender, modeKey);
        plugin.lang().send(sender, "gamemode.changed-other",
                Text.p("player", target.getName()), Text.c("mode", senderMode));
        plugin.theme().play(sender, "success");
        String senderName = sender instanceof Player player ? player.getName() : "Console";
        plugin.lang().send(target, "gamemode.changed-by",
                Text.p("player", senderName), Text.c("mode", plugin.lang().component(target, modeKey)));
    }

    private void fail(CommandSender sender, String key) {
        plugin.lang().send(sender, key);
        plugin.theme().play(sender, "error");
    }
}
