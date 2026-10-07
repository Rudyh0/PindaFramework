package nl.pinda.framework.modules.shop;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;

/** Hulp om shop-items in menu's te tonen, met extra regels onder de bestaande lore. */
final class ShopItems {

    private ShopItems() {
    }

    /**
     * Een kopie van het item om te tonen, met het aantal (maximaal één stack) en extra regels.
     * Eigen namen, betoveringen en lore van het item blijven zichtbaar.
     */
    static ItemStack display(ItemStack template, int shownAmount, List<Component> extraLore) {
        ItemStack item = template.clone();
        item.setAmount(Math.max(1, Math.min(shownAmount, item.getMaxStackSize())));
        item.editMeta(meta -> {
            List<Component> existing = meta.lore();
            List<Component> lore = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            for (Component line : extraLore) {
                lore.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                        .colorIfAbsent(NamedTextColor.GRAY));
            }
            meta.lore(lore);
        });
        return item;
    }
}
