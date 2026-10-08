package nl.pinda.framework.modules.scoreboard;

import net.kyori.adventure.text.Component;

/** Eén regel op het scoreboard: links de tekst, rechts de waarde (of null). */
public record SidebarLine(Component left, Component right) {
}
