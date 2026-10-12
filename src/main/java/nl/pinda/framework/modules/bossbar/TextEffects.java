package nl.pinda.framework.modules.bossbar;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

/**
 * Bewegende effecten over een tekst: elke letter krijgt zijn eigen kleur (of verschijnt pas
 * later). Opmaak zoals vet blijft staan. De berekening staat ook in het paneel (app.js,
 * bbEffect) voor het voorbeeld; houd ze gelijk.
 */
final class TextEffects {

    private static final TextColor DEFAULT_A = TextColor.color(0xFFC857);
    private static final TextColor DEFAULT_B = TextColor.color(0xE9724C);

    private TextEffects() {
    }

    /** Eén letter met zijn opmaak, of een stukje dat we niet aanraken (bijv. een vertaalde tekst). */
    private record Piece(String text, Style style, Component opaque) {
    }

    /**
     * @param ticks      hoe lang de bossbar al staat
     * @param frameTicks hoe lang het huidige frame al staat
     */
    static Component apply(Component source, TextEffect effect, List<TextColor> colors, double speed, long ticks, long frameTicks) {
        if (effect == TextEffect.NONE) {
            return source;
        }
        List<Piece> pieces = new ArrayList<>();
        flatten(source, Style.empty(), pieces);
        if (pieces.isEmpty()) {
            return source;
        }
        int length = pieces.size();
        List<Piece> out = new ArrayList<>(length);
        switch (effect) {
            case TYPEWRITER -> {
                int visible = (int) Math.min(length, Math.floor(frameTicks * 0.75 * speed) + 1);
                out.addAll(pieces.subList(0, visible));
            }
            case WAVE -> {
                List<TextColor> wave = colors.size() >= 2 ? colors
                        : colors.size() == 1 ? List.of(colors.get(0), NamedTextColor.WHITE) : List.of(DEFAULT_A, DEFAULT_B);
                double phase = ticks * 0.02 * speed;
                for (int index = 0; index < length; index++) {
                    double position = frac((double) index / length - phase);
                    double segment = position * wave.size();
                    int at = (int) Math.floor(segment) % wave.size();
                    TextColor color = TextColor.lerp((float) (segment - Math.floor(segment)), wave.get(at), wave.get((at + 1) % wave.size()));
                    out.add(recolor(pieces.get(index), color));
                }
            }
            case SHINE -> {
                TextColor shine = colors.isEmpty() ? NamedTextColor.WHITE : colors.get(0);
                double width = 3;
                double period = length + 2 * width + 10;
                double center = (ticks * 0.5 * speed) % period - width;
                for (int index = 0; index < length; index++) {
                    Piece piece = pieces.get(index);
                    double distance = Math.abs(index - center);
                    if (distance >= width || piece.opaque() != null) {
                        out.add(piece);
                        continue;
                    }
                    float strength = (float) (1 - distance / width);
                    out.add(recolor(piece, TextColor.lerp(strength, base(piece), shine)));
                }
            }
            case PULSE -> {
                TextColor first = colors.isEmpty() ? DEFAULT_A : colors.get(0);
                float amount = (float) ((1 - Math.cos(2 * Math.PI * ticks * speed / 40.0)) / 2);
                for (Piece piece : pieces) {
                    TextColor other = colors.size() >= 2 ? colors.get(1) : base(piece);
                    out.add(recolor(piece, TextColor.lerp(amount, other, first)));
                }
            }
            default -> {
                return source;
            }
        }
        return rebuild(out);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static TextColor base(Piece piece) {
        TextColor color = piece.style().color();
        return color == null ? NamedTextColor.WHITE : color;
    }

    private static Piece recolor(Piece piece, TextColor color) {
        return piece.opaque() != null ? piece : new Piece(piece.text(), piece.style().color(color), null);
    }

    private static void flatten(Component component, Style parent, List<Piece> out) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text) {
            String content = text.content();
            for (int index = 0; index < content.length(); ) {
                int codePoint = content.codePointAt(index);
                int next = index + Character.charCount(codePoint);
                out.add(new Piece(content.substring(index, next), style, null));
                index = next;
            }
        } else {
            out.add(new Piece(null, style, component.children(List.of()).style(style)));
        }
        for (Component child : component.children()) {
            flatten(child, style, out);
        }
    }

    private static Component rebuild(List<Piece> pieces) {
        TextComponent.Builder root = Component.text();
        StringBuilder run = new StringBuilder();
        Style runStyle = null;
        for (Piece piece : pieces) {
            if (piece.opaque() != null) {
                if (runStyle != null) {
                    root.append(Component.text(run.toString(), runStyle));
                    run.setLength(0);
                    runStyle = null;
                }
                root.append(piece.opaque());
                continue;
            }
            if (runStyle != null && !runStyle.equals(piece.style())) {
                root.append(Component.text(run.toString(), runStyle));
                run.setLength(0);
            }
            runStyle = piece.style();
            run.append(piece.text());
        }
        if (runStyle != null) {
            root.append(Component.text(run.toString(), runStyle));
        }
        return root.build();
    }
}
