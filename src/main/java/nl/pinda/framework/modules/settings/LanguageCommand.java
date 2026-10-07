package nl.pinda.framework.modules.settings;

import java.util.List;
import java.util.stream.Collectors;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Language;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /taal [code]: wisselt direct van taal, of opent het instellingenmenu. */
public final class LanguageCommand extends PindaCommand {

    public LanguageCommand(PindaFramework plugin) {
        super(plugin, "taal", "Stel je taal in", "pinda.language.use", "language", "lang");
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            new SettingsMenu(plugin, player, SettingsMenu.Mode.NORMAL).open();
            plugin.theme().play(player, "menu-open");
            return;
        }

        Language language = plugin.lang().find(args[0]);
        if (language == null) {
            String available = plugin.lang().languages().stream()
                    .map(Language::code)
                    .collect(Collectors.joining(", "));
            plugin.lang().send(player, "settings.language-unknown",
                    Text.p("language", args[0]), Text.p("languages", available));
            plugin.theme().play(player, "error");
            return;
        }

        PindaPlayer data = plugin.players().get(player);
        data.language(language.code());
        plugin.players().save(data);
        plugin.lang().send(player, "settings.language-changed", Text.p("language", language.name()));
        plugin.theme().play(player, "success");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0) {
            return plugin.lang().languages().stream().map(Language::code).toList();
        }
        return List.of();
    }
}
