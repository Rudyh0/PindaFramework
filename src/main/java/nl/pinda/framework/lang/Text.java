package nl.pinda.framework.lang;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Korte helpers om placeholders in berichten in te vullen. */
public final class Text {

    private Text() {
    }

    /**
     * Een placeholder met platte tekst, zoals een spelernaam. Opmaakcodes in de waarde
     * worden niet uitgevoerd, zodat spelers geen kleuren of klikacties kunnen injecteren.
     */
    public static TagResolver p(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Een placeholder met een al opgemaakt component. */
    public static TagResolver c(String key, ComponentLike value) {
        return Placeholder.component(key, value);
    }
}
