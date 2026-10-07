package nl.pinda.framework.menu;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** Maakt menu-items met een naam, lore en eventueel een glans. Teksten zijn standaard niet cursief. */
public final class ItemBuilder {

    private final Material material;
    private final List<Component> lore = new ArrayList<>();
    private int amount = 1;
    private Component name;
    private Boolean glint;
    private OfflinePlayer headOwner;
    private boolean hideTooltip;
    private boolean hideAttributes;

    private ItemBuilder(Material material) {
        this.material = material;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    public ItemBuilder amount(int amount) {
        this.amount = Math.max(1, Math.min(99, amount));
        return this;
    }

    public ItemBuilder name(Component name) {
        this.name = name;
        return this;
    }

    public ItemBuilder lore(Component line) {
        this.lore.add(line);
        return this;
    }

    public ItemBuilder lore(List<Component> lines) {
        this.lore.addAll(lines);
        return this;
    }

    /** Laat het item glanzen alsof het betoverd is. */
    public ItemBuilder glint(boolean glint) {
        this.glint = glint;
        return this;
    }

    /** Geeft een spelershoofd het gezicht van deze speler. */
    public ItemBuilder head(OfflinePlayer owner) {
        this.headOwner = owner;
        return this;
    }

    /** Verbergt de tooltip volledig, handig voor opvulling. */
    public ItemBuilder hideTooltip() {
        this.hideTooltip = true;
        return this;
    }

    /** Verbergt de aanvalsschade en -snelheid van gereedschap en wapens in de tooltip. */
    public ItemBuilder hideAttributes() {
        this.hideAttributes = true;
        return this;
    }

    public ItemStack build() {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(meta -> {
            if (name != null) {
                meta.customName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                        .colorIfAbsent(NamedTextColor.WHITE));
            }
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (Component line : lore) {
                    lines.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
                            .colorIfAbsent(NamedTextColor.GRAY));
                }
                meta.lore(lines);
            }
            if (glint != null) {
                meta.setEnchantmentGlintOverride(glint);
            }
            if (hideTooltip) {
                meta.setHideTooltip(true);
            }
            if (hideAttributes) {
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            }
            if (headOwner != null && meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(headOwner);
            }
        });
        return item;
    }
}
