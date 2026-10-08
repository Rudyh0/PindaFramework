package nl.pinda.framework.modules.world;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;

/** Waar een explosie vandaan komt, met de naam in de config (explosions.block-damage.&lt;id&gt;). */
public enum ExplosionSource {

    CREEPER("creeper", false),
    TNT("tnt", false),
    TNT_MINECART("tnt-minecart", false),
    GHAST("ghast", false),
    WITHER("wither", false),
    END_CRYSTAL("end-crystal", true),
    BED("bed", true),
    RESPAWN_ANCHOR("respawn-anchor", true);

    private final String id;
    private final boolean vanillaDefault;

    ExplosionSource(String id, boolean vanillaDefault) {
        this.id = id;
        this.vanillaDefault = vanillaDefault;
    }

    /** De naam in de config en het paneel. */
    public String id() {
        return id;
    }

    /** De standaardwaarde voor blokschade als de config niets zegt. */
    public boolean defaultDamage() {
        return vanillaDefault;
    }

    public static ExplosionSource byId(String id) {
        for (ExplosionSource source : values()) {
            if (source.id.equalsIgnoreCase(id)) {
                return source;
            }
        }
        return null;
    }

    /** De bron van een explosie door een entity, of null als we die niet kennen (zoals wind charges). */
    static ExplosionSource of(Entity entity) {
        if (entity == null) {
            return null;
        }
        return switch (entity.getType()) {
            case CREEPER -> CREEPER;
            case TNT -> TNT;
            case TNT_MINECART -> TNT_MINECART;
            case FIREBALL -> GHAST;
            case WITHER, WITHER_SKULL -> WITHER;
            case END_CRYSTAL -> END_CRYSTAL;
            default -> null;
        };
    }

    /** De bron van een ontploffend blok (een bed of een respawn anchor), of null. */
    static ExplosionSource of(BlockState state) {
        if (state == null) {
            return null;
        }
        Material type = state.getType();
        if (Tag.BEDS.isTagged(type)) {
            return BED;
        }
        if (type == Material.RESPAWN_ANCHOR) {
            return RESPAWN_ANCHOR;
        }
        return null;
    }
}
