package nl.pinda.framework.modules.timber;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * De boom valt echt om: elk blok wordt een blokweergave (BlockDisplay) die om de voet van de
 * stam kantelt, weg van de speler, steeds sneller zoals bij een echte boom. Minecraft laat
 * de beweging zelf vloeiend verlopen (interpolatie), dus de server stuurt maar een paar stappen.
 * Waar de boom neerkomt, vallen de spullen.
 */
final class FallAnimation {

    /** Eén blok van de boom: hoe het eruitzag, waar het stond (t.o.v. het draaipunt) en wat het oplevert. */
    record Piece(BlockData data, Vector3f corner, List<ItemStack> drops, boolean log) {
    }

    private static final int STEP = 3;
    private static final Vector3f ONE = new Vector3f(1, 1, 1);
    /** Net niet helemaal plat, dan zakt de kruin niet zo ver de grond in. */
    private static final float MAX_ANGLE = (float) Math.toRadians(86);

    private final PindaFramework plugin;
    private final TimberSettings settings;
    private final Location pivot;
    private final Vector3f axis;
    private final List<Piece> pieces;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private final Player player;
    private final BiConsumer<Location, List<ItemStack>> dropper;
    private final Runnable whenDone;
    private BukkitTask task;
    private int elapsed;
    private boolean landed;
    private boolean dropped;
    private boolean finished;

    /**
     * @param direction de kant waar de boom heen valt (horizontaal, genormaliseerd)
     * @param dropper   laat de spullen van een blok vallen op een plek
     * @param whenDone  als de animatie klaar (of afgebroken) is
     */
    FallAnimation(PindaFramework plugin, TimberSettings settings, Location pivot, Vector3f direction, List<Piece> pieces,
                  Player player, BiConsumer<Location, List<ItemStack>> dropper, Runnable whenDone) {
        this.plugin = plugin;
        this.settings = settings;
        this.pivot = pivot;
        // Kantelen naar 'direction': draaien om de as loodrecht daarop (omhoog x richting)
        this.axis = new Vector3f(direction.z, 0, -direction.x).normalize();
        this.pieces = pieces;
        this.player = player;
        this.dropper = dropper;
        this.whenDone = whenDone;
    }

    void start(Display.Brightness brightness) {
        World world = pivot.getWorld();
        float reach = 4;
        for (Piece piece : pieces) {
            reach = Math.max(reach, piece.corner().length() + 2);
        }
        for (Piece piece : pieces) {
            BlockDisplay display = world.spawn(pivot, BlockDisplay.class);
            display.setPersistent(false);
            display.setBlock(piece.data());
            display.setTransformation(new Transformation(new Vector3f(piece.corner()), new Quaternionf(), ONE, new Quaternionf()));
            // Groot genoeg, anders verdwijnt de boom als het draaipunt net buiten beeld is
            display.setDisplayWidth(reach * 2);
            display.setDisplayHeight(reach * 2);
            if (brightness != null) {
                display.setBrightness(brightness);
            }
            displays.add(display);
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::frame, 1L, STEP);
    }

    private Quaternionf rotation(float progress) {
        // Eerst langzaam, dan steeds sneller (zoals vallen)
        return new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, MAX_ANGLE * progress * progress);
    }

    private void frame() {
        if (landed) {
            return;
        }
        elapsed += STEP;
        float progress = Math.min(1f, elapsed / (float) settings.durationTicks);
        Quaternionf rotation = rotation(progress);
        for (int index = 0; index < displays.size(); index++) {
            BlockDisplay display = displays.get(index);
            if (!display.isValid()) {
                continue;
            }
            Vector3f translation = rotation.transform(new Vector3f(pieces.get(index).corner()));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(STEP);
            display.setTransformation(new Transformation(translation, new Quaternionf(rotation), ONE, new Quaternionf()));
        }
        if (progress >= 1f) {
            task.cancel();
            landed = true;
            // Even wachten tot de laatste stap op het scherm klaar is, dan de klap
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> land(rotation), STEP);
        }
    }

    /** Waar een blok terechtkomt (het midden van het blok). */
    private Location landing(Quaternionf rotation, Piece piece) {
        Vector3f center = rotation.transform(new Vector3f(piece.corner()).add(0.5f, 0.5f, 0.5f));
        return pivot.clone().add(center.x, center.y, center.z);
    }

    private void land(Quaternionf rotation) {
        if (finished || dropped) {
            return;
        }
        dropped = true;
        World world = pivot.getWorld();
        if (settings.soundLand != null && !settings.soundLand.isBlank()) {
            world.playSound(landing(rotation, pieces.get(pieces.size() / 2)), settings.soundLand, 1.2f, 0.7f);
        }
        Set<LivingEntity> hit = new HashSet<>();
        int effects = 0;
        for (Piece piece : pieces) {
            Location location = landing(rotation, piece);
            if (settings.particles && effects++ % 3 == 0) {
                world.spawnParticle(Particle.BLOCK, location, 6, 0.35, 0.35, 0.35, 0, piece.data());
            }
            if (piece.log() && settings.damage > 0) {
                for (LivingEntity entity : world.getNearbyLivingEntities(location, 0.9, 0.9, 0.9)) {
                    if (entity != player && hit.add(entity)) {
                        entity.damage(settings.damage);
                    }
                }
            }
            if ("landing".equals(settings.dropMode) && !piece.drops().isEmpty()) {
                dropper.accept(safe(location), piece.drops());
            }
        }
        if (!"landing".equals(settings.dropMode)) {
            List<ItemStack> all = new ArrayList<>();
            for (Piece piece : pieces) {
                all.addAll(piece.drops());
            }
            dropper.accept(pivot.clone().add(0, 0.5, 0), all);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, this::finish, Math.max(1, settings.lingerTicks));
    }

    /** Een plek waar items kunnen liggen: niet in een blok, anders een stukje omhoog (of bij de stam). */
    private Location safe(Location location) {
        Block block = location.getBlock();
        for (int up = 0; up < 5; up++) {
            if (!block.getType().isSolid()) {
                return block.getLocation().add(0.5, 0.2, 0.5);
            }
            block = block.getRelative(0, 1, 0);
        }
        return pivot.clone().add(0, 0.5, 0);
    }

    /** Ruimt de animatie op. */
    void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (task != null) {
            task.cancel();
        }
        for (BlockDisplay display : displays) {
            if (display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
        whenDone.run();
    }

    /** Meteen stoppen (bijv. als de plugin stopt): de spullen vallen dan bij de stam. */
    void abort() {
        if (finished) {
            return;
        }
        if (!dropped) {
            dropped = true;
            List<ItemStack> all = new ArrayList<>();
            for (Piece piece : pieces) {
                all.addAll(piece.drops());
            }
            dropper.accept(pivot.clone().add(0, 0.5, 0), all);
        }
        landed = true;
        finish();
    }
}
