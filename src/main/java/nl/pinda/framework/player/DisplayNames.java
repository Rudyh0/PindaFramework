package nl.pinda.framework.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import nl.pinda.framework.PindaFramework;
import org.bukkit.entity.Player;

/**
 * Bepaalt hoe een spelernaam eruitziet in de tablist en in berichten. Modules leveren
 * stukjes aan: de rangen een prefix en naamkleur, AFK een [AFK] erachter. Zo zitten ze
 * elkaar niet in de weg.
 */
public final class DisplayNames {

    private final PindaFramework plugin;
    private final Map<String, Function<Player, Component>> prefixes = new LinkedHashMap<>();
    private final Map<String, Function<Player, Component>> suffixes = new LinkedHashMap<>();
    private Function<Player, TextColor> nameColor;

    public DisplayNames(PindaFramework plugin) {
        this.plugin = plugin;
    }

    public void setPrefix(String id, Function<Player, Component> provider) {
        prefixes.put(id, provider);
    }

    public void removePrefix(String id) {
        prefixes.remove(id);
    }

    public void setSuffix(String id, Function<Player, Component> provider) {
        suffixes.put(id, provider);
    }

    public void removeSuffix(String id) {
        suffixes.remove(id);
    }

    public void setNameColor(Function<Player, TextColor> provider) {
        this.nameColor = provider;
    }

    /** De naam in de kleur van de speler (zonder prefix of suffix). */
    public Component name(Player player) {
        TextColor color = nameColor == null ? null : nameColor.apply(player);
        return color == null ? Component.text(player.getName()) : Component.text(player.getName(), color);
    }

    /** Werkt de tablist-naam en de displaynaam van een speler bij. */
    public void refresh(Player player) {
        Component prefix = Component.empty();
        boolean custom = nameColor != null;
        for (Function<Player, Component> provider : prefixes.values()) {
            Component part = provider.apply(player);
            if (part != null) {
                prefix = prefix.append(part);
                custom = true;
            }
        }
        Component suffix = Component.empty();
        for (Function<Player, Component> provider : suffixes.values()) {
            Component part = provider.apply(player);
            if (part != null) {
                suffix = suffix.append(part);
                custom = true;
            }
        }
        if (!custom) {
            player.playerListName(null);
            player.displayName(null);
            return;
        }
        Component name = name(player);
        player.displayName(name);
        player.playerListName(Component.empty().append(prefix).append(name).append(suffix));
    }

    public void refreshAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player);
        }
    }
}
