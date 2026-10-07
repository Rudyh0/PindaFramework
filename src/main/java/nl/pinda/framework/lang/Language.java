package nl.pinda.framework.lang;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** Eén taalbestand uit de map lang/, bijvoorbeeld lang/nl.yml. */
public final class Language {

    private final String code;
    private final YamlConfiguration yaml;

    public Language(String code, YamlConfiguration yaml) {
        this.code = code;
        this.yaml = yaml;
    }

    /** De code van de taal, gelijk aan de bestandsnaam (nl, en, ...). */
    public String code() {
        return code;
    }

    /** De naam van de taal in die taal zelf (Nederlands, English, ...). */
    public String name() {
        return yaml.getString("language.name", code);
    }

    /** Het item waarmee de taal in menu's getoond wordt. */
    public Material icon() {
        Material material = Material.matchMaterial(yaml.getString("language.icon", "PAPER"));
        return material != null && material.isItem() ? material : Material.PAPER;
    }

    /** Client-talen (zoals "nl" of "nl_be") die automatisch deze taal krijgen. */
    public List<String> clientLocales() {
        List<String> locales = yaml.getStringList("language.client-locales");
        if (locales.isEmpty()) {
            return List.of(code);
        }
        return locales.stream().map(locale -> locale.toLowerCase(Locale.ROOT)).toList();
    }

    YamlConfiguration yaml() {
        return yaml;
    }
}
