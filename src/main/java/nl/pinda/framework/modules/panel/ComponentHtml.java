package nl.pinda.framework.modules.panel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Zet een Minecraft-tekst (component) om naar HTML, zodat het paneel laat zien hoe het er in-game uitziet. */
final class ComponentHtml {

    private ComponentHtml() {
    }

    static String render(Component component) {
        StringBuilder out = new StringBuilder();
        append(component, Style.empty(), out);
        return out.toString();
    }

    private static void append(Component component, Style parent, StringBuilder out) {
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        String text = "";
        if (component instanceof TextComponent textComponent) {
            text = textComponent.content();
        } else if (component instanceof TranslatableComponent translatable) {
            text = translatable.key();
        }
        if (!text.isEmpty()) {
            span(style, text, out);
        }
        for (Component child : component.children()) {
            append(child, style, out);
        }
    }

    private static void span(Style style, String text, StringBuilder out) {
        StringBuilder css = new StringBuilder();
        TextColor color = style.color();
        if (color != null) {
            css.append("color:").append(color.asHexString()).append(';');
        }
        if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            css.append("font-weight:700;");
        }
        if (style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) {
            css.append("font-style:italic;");
        }
        boolean underlined = style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE;
        boolean strike = style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE;
        if (underlined || strike) {
            css.append("text-decoration:").append(underlined ? "underline " : "").append(strike ? "line-through" : "").append(';');
        }
        StringBuilder classes = new StringBuilder();
        if (style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) {
            classes.append("mc-obf ");
        }
        if (style.clickEvent() != null) {
            classes.append("mc-click ");
        }
        String title = null;
        HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.value() instanceof Component hoverText) {
            title = PlainTextComponentSerializer.plainText().serialize(hoverText);
            classes.append("mc-hover ");
        }
        out.append("<span");
        if (!css.isEmpty()) {
            out.append(" style=\"").append(css).append('"');
        }
        if (!classes.isEmpty()) {
            out.append(" class=\"").append(classes.toString().trim()).append('"');
        }
        if (title != null) {
            out.append(" title=\"").append(escape(title)).append('"');
        }
        out.append('>').append(escape(text).replace("\n", "<br>")).append("</span>");
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
