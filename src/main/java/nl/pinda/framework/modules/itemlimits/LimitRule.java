package nl.pinda.framework.modules.itemlimits;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Eén beperkt item, zoals het in modules/itemlimits.yml staat (onder 'items').
 *
 * @param material    het item
 * @param enchantment alleen items met deze betovering (bijv. Mending-boeken), of null voor elk
 * @param name        de naam in meldingen en het paneel; leeg = de Minecraft-naam
 * @param perPlayer   hoeveel één speler er mag hebben (0 = geen maximum)
 * @param serverMax   hoeveel er op de hele server mogen zijn (0 = geen maximum)
 */
public record LimitRule(String id, Material material, Enchantment enchantment, String name, int perPlayer,
                        int serverMax, boolean enabled) {

    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    public static final int MAX_AMOUNT = 100_000;
    public static final int MAX_NAME = 48;

    /** Leest een regel uit de config. Null als het item niet bestaat. */
    public static LimitRule parse(String id, ConfigurationSection section) {
        Material material = material(section.getString("material", ""));
        if (material == null) {
            return null;
        }
        String enchantmentKey = section.getString("enchantment", "");
        Enchantment enchantment = enchantmentKey == null || enchantmentKey.isBlank() ? null : enchantment(enchantmentKey);
        if (enchantment == null && enchantmentKey != null && !enchantmentKey.isBlank()) {
            return null; // een betovering die niet bestaat: liever niets dan elk item van dit soort
        }
        String name = section.getString("name", "");
        return new LimitRule(id.toLowerCase(Locale.ROOT), material, enchantment, clip(name == null ? "" : name.trim()),
                clamp(section.getInt("per-player", 0)), clamp(section.getInt("server-max", 0)), section.getBoolean("enabled", true));
    }

    /** Zet de regel in de config (voor het paneel). */
    public void write(ConfigurationSection section) {
        section.set("material", material.getKey().getKey());
        section.set("enchantment", enchantment == null ? "" : enchantment.getKey().getKey());
        section.set("name", name);
        section.set("per-player", perPlayer);
        section.set("server-max", serverMax);
        section.set("enabled", enabled);
    }

    /** Hoort dit item onder deze regel? */
    public boolean matches(ItemStack item) {
        if (item == null || item.getType() != material) {
            return false;
        }
        if (enchantment == null) {
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.hasEnchant(enchantment)
                || meta instanceof EnchantmentStorageMeta stored && stored.hasStoredEnchant(enchantment);
    }

    /** De naam zoals spelers hem zien: de eigen naam, of de Minecraft-naam in hun taal. */
    public Component displayName() {
        if (!name.isEmpty()) {
            return Component.text(name);
        }
        Component item = Component.translatable(material);
        if (enchantment != null) {
            return item.append(Component.text(" (")).append(enchantment.description()).append(Component.text(")"));
        }
        return item;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("material", material.getKey().getKey());
        map.put("enchantment", enchantment == null ? "" : enchantment.getKey().getKey());
        map.put("name", name);
        map.put("perPlayer", perPlayer);
        map.put("serverMax", serverMax);
        map.put("enabled", enabled);
        return map;
    }

    // ============================================================ hulpjes

    public static Material material(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(name.trim());
        return material == null || !material.isItem() || material.isAir() || material.name().startsWith("LEGACY_") ? null : material;
    }

    public static Enchantment enchantment(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_AMOUNT, value));
    }

    private static String clip(String text) {
        return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
    }
}
