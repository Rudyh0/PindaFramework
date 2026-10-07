package nl.pinda.framework.modules.ranks;

import java.util.List;
import net.kyori.adventure.text.format.TextColor;

/**
 * Een rang uit ranks.yml.
 *
 * @param id          de naam in de config (pinda, pindamod, pindaadmin)
 * @param displayName hoe de rang heet (PindaMod)
 * @param weight      hoger = belangrijker
 * @param inherits    de rang waarvan deze rang alle permissies erft (of null)
 * @param operator    spelers met deze rang zijn operator (mogen alles)
 * @param color       kleur van de rang en de spelernaam
 * @param chatColor   kleur van chatberichten
 * @param prefix      de prefix in MiniMessage, bijv. "&lt;gold&gt;[Admin]&lt;/gold&gt; "
 * @param permissions permissies; "-node" zet iets juist uit, "plugin.*" geeft alles van een plugin
 */
public record Rank(String id, String displayName, int weight, String inherits, boolean operator,
                   TextColor color, TextColor chatColor, String prefix, List<String> permissions) {
}
