package nl.pinda.framework.modules.itemlimits;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Het nummer op een beperkt item. Elk exemplaar krijgt een eigen nummer (en stapelt daarom niet
 * meer), zodat het framework weet welk item van wie is, ook als het in een kist ligt.
 */
final class LimitTags {

    /** Hoe diep we in shulkerdozen en bundels kijken (een bundel in een shulkerdoos, ...). */
    static final int MAX_DEPTH = 3;

    private final NamespacedKey serialKey;
    private final NamespacedKey ruleKey;
    private final NamespacedKey markKey;
    private final NamespacedKey giftKey;

    LimitTags(Plugin plugin) {
        serialKey = new NamespacedKey(plugin, "limit_serial");
        ruleKey = new NamespacedKey(plugin, "limit_rule");
        markKey = new NamespacedKey(plugin, "limit_mark");
        giftKey = new NamespacedKey(plugin, "limit_gift");
    }

    /** Het nummer van een item, of null als het (nog) geen nummer heeft. */
    String serial(ItemStack item) {
        PersistentDataContainer data = data(item);
        return data == null ? null : data.get(serialKey, PersistentDataType.STRING);
    }

    /** Onder welke regel het item zijn nummer kreeg. */
    String rule(ItemStack item) {
        PersistentDataContainer data = data(item);
        return data == null ? null : data.get(ruleKey, PersistentDataType.STRING);
    }

    /** Voor wie dit item een cadeau van de server is (of null). */
    UUID giftFor(ItemStack item) {
        PersistentDataContainer data = data(item);
        String value = data == null ? null : data.get(giftKey, PersistentDataType.STRING);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PersistentDataContainer data(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer();
    }

    /**
     * Geeft één item (aantal 1) een nummer. Met mark komt er een regel "Beperkt item" in de beschrijving.
     *
     * @param giftFor een cadeau van de server voor deze speler (of null)
     */
    void tag(ItemStack item, String rule, String serial, Component mark, UUID giftFor) {
        item.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(serialKey, PersistentDataType.STRING, serial);
            data.set(ruleKey, PersistentDataType.STRING, rule);
            if (giftFor != null) {
                data.set(giftKey, PersistentDataType.STRING, giftFor.toString());
            }
            meta.setMaxStackSize(1);
            if (mark != null && !data.has(markKey, PersistentDataType.STRING)) {
                Component line = mark.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(line);
                meta.lore(lore);
                data.set(markKey, PersistentDataType.STRING, plain(line));
            }
        });
    }

    /** Haalt het nummer (en de regel in de beschrijving) weer weg: een gewoon item, dat weer stapelt. */
    void untag(ItemStack item) {
        item.editMeta(meta -> {
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.remove(serialKey);
            data.remove(ruleKey);
            data.remove(giftKey);
            String mark = data.get(markKey, PersistentDataType.STRING);
            data.remove(markKey);
            meta.setMaxStackSize(null);
            List<Component> lore = meta.lore();
            if (mark != null && lore != null) {
                List<Component> kept = new ArrayList<>();
                boolean removed = false;
                for (Component line : lore) {
                    if (!removed && plain(line).equals(mark)) {
                        removed = true;
                        continue;
                    }
                    kept.add(line);
                }
                meta.lore(kept.isEmpty() ? null : kept);
            }
        });
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ============================================================ in dozen en bundels kijken

    /** Elk item, ook wat erin zit (shulkerdozen en bundels). Alleen om te lezen. */
    static void forEach(ItemStack item, Consumer<ItemStack> visit) {
        forEach(item, visit, 0);
    }

    private static void forEach(ItemStack item, Consumer<ItemStack> visit, int depth) {
        if (item == null || item.isEmpty()) {
            return;
        }
        visit.accept(item);
        if (depth >= MAX_DEPTH || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta box && box.hasBlockState() && box.getBlockState() instanceof ShulkerBox shulker) {
            for (ItemStack child : shulker.getInventory().getContents()) {
                forEach(child, visit, depth + 1);
            }
        } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            for (ItemStack child : bundle.getItems()) {
                forEach(child, visit, depth + 1);
            }
        }
    }

    /** Wat er met één item moet gebeuren: null = niets, anders de items die ervoor in de plaats komen. */
    interface Change {
        List<ItemStack> apply(ItemStack item);
    }

    /**
     * Past een item aan, ook wat erin zit. Geeft null als er niets veranderde, anders de items
     * die ervoor in de plaats komen: de eerste op dezelfde plek, de rest moet de aanroeper ergens
     * kwijt (wat niet meer in een doos of bundel paste, komt daar ook bij).
     */
    static List<ItemStack> map(ItemStack item, Change change) {
        return map(item, change, 0);
    }

    private static List<ItemStack> map(ItemStack item, Change change, int depth) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        ItemStack current = item;
        boolean changed = false;
        List<ItemStack> overflow = new ArrayList<>();
        if (depth < MAX_DEPTH && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof BlockStateMeta box && box.hasBlockState() && box.getBlockState() instanceof ShulkerBox shulker) {
                Inventory inventory = shulker.getInventory();
                boolean inner = false;
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    List<ItemStack> result = map(inventory.getItem(slot), change, depth + 1);
                    if (result == null) {
                        continue;
                    }
                    inner = true;
                    inventory.setItem(slot, result.isEmpty() ? null : result.get(0));
                    for (ItemStack rest : result.subList(Math.min(1, result.size()), result.size())) {
                        int free = inventory.firstEmpty();
                        if (free >= 0) {
                            inventory.setItem(free, rest);
                        } else {
                            overflow.add(rest);
                        }
                    }
                }
                if (inner) {
                    box.setBlockState(shulker);
                    current = item.clone();
                    current.setItemMeta(box);
                    changed = true;
                }
            } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                List<ItemStack> kept = new ArrayList<>();
                boolean inner = false;
                for (ItemStack child : bundle.getItems()) {
                    List<ItemStack> result = map(child, change, depth + 1);
                    if (result == null) {
                        kept.add(child);
                        continue;
                    }
                    inner = true;
                    if (!result.isEmpty()) {
                        kept.add(result.get(0));
                        overflow.addAll(result.subList(1, result.size()));
                    }
                }
                if (inner) {
                    bundle.setItems(kept);
                    current = item.clone();
                    current.setItemMeta(bundle);
                    changed = true;
                }
            }
        }
        List<ItemStack> self = change.apply(current);
        if (self == null && !changed && overflow.isEmpty()) {
            return null;
        }
        List<ItemStack> result = new ArrayList<>(self == null ? List.of(current) : self);
        result.addAll(overflow);
        return result;
    }
}
