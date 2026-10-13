package nl.pinda.framework.modules.rules;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Verdeelt tekst over de bladzijden van een boek, zoals Minecraft dat zou doen: een bladzijde is
 * 114 pixels breed en heeft 14 regels. Minecraft zelf schuift te lange tekst niet door naar de
 * volgende bladzijde (die valt eraf), dus dat doen we hier vooraf.
 *
 * <p>De breedte van letters is die van het standaardlettertype van Minecraft (vet = 1 pixel breder).
 * We vullen een bladzijde met hooguit 13 regels: Minecraft breekt soms net iets anders af.
 */
public final class BookLayout {

    static final int PAGE_WIDTH = 114;
    static final int PAGE_LINES = 14;
    static final int SAFE_LINES = 13;
    static final int MAX_PAGES = 100;

    /** Eén bladzijde: het component voor het boek, en de regels zoals wij ze afbreken (voor het paneel). */
    public record Page(Component content, List<Component> lines) {
    }

    /** Eén letter met zijn opmaak. */
    private record Glyph(int codePoint, Style style) {
    }

    /** Hoe een regel overgaat in de volgende: Enter in de tekst, bij een spatie, of midden in een woord. */
    private enum Break { HARD, SPACE, WORD, END }

    private record Line(List<Glyph> glyphs, Break after) {
    }

    private BookLayout() {
    }

    // ============================================================ breedte

    /** Hoe breed een letter is in het standaardlettertype van Minecraft (met de ruimte erna). */
    static int width(int codePoint, boolean bold) {
        int width = switch (codePoint) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', '|', 'i' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> codePoint < 0x2000 ? 6 : 9; // symbolen en emoji zijn breder
        };
        return bold ? width + 1 : width;
    }

    // ============================================================ opdelen

    /**
     * Zet blokken tekst (een titel, een regel, ...) op bladzijden. Elk blok begint op een nieuwe
     * regel, met een lege regel ertussen. Past een blok niet meer op de bladzijde, maar wel op een
     * hele, dan begint het op de volgende; anders loopt het door.
     */
    static List<Page> paginate(List<Component> blocks) {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        for (Component block : blocks) {
            List<Line> lines = wrap(flatten(block));
            if (lines.isEmpty()) {
                continue;
            }
            int needed = lines.size() + (page.isEmpty() ? 0 : 1);
            if (!page.isEmpty() && page.size() + needed > SAFE_LINES && lines.size() <= SAFE_LINES) {
                pages.add(page);
                page = new ArrayList<>();
            }
            if (!page.isEmpty()) {
                page.add(new Line(List.of(), Break.HARD)); // lege regel tussen twee blokken
            }
            for (Line line : lines) {
                if (page.size() >= SAFE_LINES) {
                    pages.add(page);
                    page = new ArrayList<>();
                }
                page.add(line);
            }
        }
        if (!page.isEmpty()) {
            pages.add(page);
        }
        List<Page> result = new ArrayList<>();
        for (List<Line> lines : pages.subList(0, Math.min(pages.size(), MAX_PAGES))) {
            result.add(build(lines));
        }
        return result;
    }

    /** Elke letter met de opmaak die hij uiteindelijk heeft (kinderen erven van hun ouder). */
    static List<Glyph> flatten(Component component) {
        List<Glyph> glyphs = new ArrayList<>();
        flatten(component, Style.empty(), glyphs);
        return glyphs;
    }

    private static void flatten(Component component, Style parent, List<Glyph> out) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        String text = component instanceof TextComponent textComponent ? textComponent.content()
                : PlainTextComponentSerializer.plainText().serialize(component.children(List.of()));
        text.codePoints().forEach(codePoint -> {
            if (codePoint != '\r') {
                out.add(new Glyph(codePoint, style));
            }
        });
        for (Component child : component.children()) {
            flatten(child, style, out);
        }
    }

    /** Breekt de letters af in regels van hooguit 114 pixels, bij een spatie als dat kan. */
    private static List<Line> wrap(List<Glyph> glyphs) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        int width = 0;
        int lastSpace = -1;
        for (int index = 0; index < glyphs.size(); index++) {
            Glyph glyph = glyphs.get(index);
            if (glyph.codePoint() == '\n') {
                lines.add(new Line(glyphs.subList(start, index), Break.HARD));
                start = index + 1;
                width = 0;
                lastSpace = -1;
                continue;
            }
            int w = width(glyph.codePoint(), bold(glyph.style()));
            if (width + w > PAGE_WIDTH && index > start) {
                if (lastSpace >= start) {
                    lines.add(new Line(glyphs.subList(start, lastSpace), Break.SPACE));
                    start = lastSpace + 1;
                } else {
                    lines.add(new Line(glyphs.subList(start, index), Break.WORD));
                    start = index;
                }
                width = 0;
                for (int i = start; i < index; i++) {
                    width += width(glyphs.get(i).codePoint(), bold(glyphs.get(i).style()));
                }
                lastSpace = -1;
            }
            if (glyph.codePoint() == ' ') {
                lastSpace = index;
            }
            width += w;
        }
        if (start < glyphs.size()) {
            lines.add(new Line(glyphs.subList(start, glyphs.size()), Break.END));
        }
        return lines;
    }

    private static boolean bold(Style style) {
        return style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
    }

    /**
     * De bladzijde voor het boek: bij een spatie zetten we die spatie terug (Minecraft breekt daar
     * zelf af), een Enter blijft een Enter. Zo breekt Minecraft af waar wij dat deden.
     */
    private static Page build(List<Line> lines) {
        List<Glyph> all = new ArrayList<>();
        List<Component> preview = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            all.addAll(line.glyphs());
            preview.add(component(line.glyphs()));
            if (index < lines.size() - 1) {
                Style style = line.glyphs().isEmpty() ? Style.empty() : line.glyphs().get(line.glyphs().size() - 1).style();
                switch (line.after()) {
                    case HARD, END -> all.add(new Glyph('\n', Style.empty()));
                    case SPACE -> all.add(new Glyph(' ', style));
                    case WORD -> {
                        // midden in een woord: Minecraft breekt daar zelf af
                    }
                }
            }
        }
        return new Page(component(all), preview);
    }

    /** Letters weer samen tot een component (opeenvolgende letters met dezelfde opmaak in één stuk). */
    private static Component component(List<Glyph> glyphs) {
        List<Component> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Style current = null;
        for (Glyph glyph : glyphs) {
            if (current != null && !glyph.style().equals(current)) {
                parts.add(Component.text(text.toString(), current));
                text.setLength(0);
            }
            current = glyph.style();
            text.appendCodePoint(glyph.codePoint());
        }
        if (current != null && !text.isEmpty()) {
            parts.add(Component.text(text.toString(), current));
        }
        return parts.isEmpty() ? Component.empty() : Component.empty().children(parts);
    }
}
