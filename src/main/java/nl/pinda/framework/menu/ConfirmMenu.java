package nl.pinda.framework.menu;

import nl.pinda.framework.PindaFramework;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Een "Weet je het zeker?"-menu. In het midden staat waar het om gaat, links Ja, rechts Nee.
 * De acties worden op de volgende tick uitgevoerd, zodat ze zelf weer een menu mogen openen.
 */
public final class ConfirmMenu extends Menu {

    private final ItemStack subject;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    /**
     * @param subject   het item in het midden, bijv. de home die verwijderd wordt
     * @param onConfirm wat er gebeurt bij Ja
     * @param onCancel  wat er gebeurt bij Nee (mag null zijn: dan sluit het menu)
     */
    public ConfirmMenu(PindaFramework plugin, Player viewer, ItemStack subject, Runnable onConfirm, Runnable onCancel) {
        super(plugin, viewer, 3, plugin.lang().component(viewer, "menu.confirm.title"));
        this.subject = subject;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected void render() {
        set(13, subject);
        set(11, ItemBuilder.of(Material.LIME_CONCRETE)
                .name(plugin.lang().component(viewer, "menu.confirm.accept"))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            plugin.getServer().getScheduler().runTask(plugin, onConfirm);
        });
        set(15, ItemBuilder.of(Material.RED_CONCRETE)
                .name(plugin.lang().component(viewer, "menu.confirm.decline"))
                .build(), click -> {
            plugin.theme().play(viewer, "click");
            if (onCancel != null) {
                plugin.getServer().getScheduler().runTask(plugin, onCancel);
            } else {
                closeLater();
            }
        });
        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }
}
