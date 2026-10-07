package nl.pinda.framework.player;

import java.util.UUID;

/** Een speler die ooit op de server is geweest (online of offline). */
public record KnownPlayer(UUID uuid, String name) {
}
