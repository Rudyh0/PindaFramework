package nl.pinda.framework.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** Een klik op een knop in een menu. */
public record MenuClick(Player player, ClickType click, int slot) {
}
