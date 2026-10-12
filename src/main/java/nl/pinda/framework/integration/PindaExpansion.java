package nl.pinda.framework.integration;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import nl.pinda.framework.PindaFramework;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * De placeholders van PindaFramework voor andere plugins (TAB, hologrammen, ...):
 * %pinda_rank%, %pinda_money%, %pinda_top_money_1_name%, ... De waarden komen uit
 * {@link PindaPlaceholders}. Zie de README voor de hele lijst.
 */
final class PindaExpansion extends PlaceholderExpansion {

    private final PindaFramework plugin;
    private final PindaPlaceholders values;

    PindaExpansion(PindaFramework plugin) {
        this.plugin = plugin;
        this.values = new PindaPlaceholders(plugin);
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pinda";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Rudyh0";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.version();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            // null = onbekende placeholder: dan laat PlaceholderAPI de tekst staan
            return values.resolve(player, params.toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return "";
        }
    }
}
