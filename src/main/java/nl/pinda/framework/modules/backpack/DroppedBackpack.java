package nl.pinda.framework.modules.backpack;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Een rugtas die op de grond ligt nadat iemand doodging: een zwevende bundel met een naam
 * erboven. Rechtsklik om te openen. Wie hem sluit na het looten, laat hem in rook opgaan.
 */
final class DroppedBackpack implements InventoryHolder {

    final UUID owner;
    final String ownerName;
    final Location location;
    final long created;
    final long ownerOnlyUntil;
    final long expiresAt;
    ItemStack[] items;
    long cash;
    Inventory inventory;
    boolean removed;

    private ItemDisplay display;
    private TextDisplay label;
    private Interaction hitbox;
    private Component lastLabel;
    private float angle;

    DroppedBackpack(UUID owner, String ownerName, Location location, ItemStack[] items, long cash,
                    long ownerOnlyMillis, long lifeMillis) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.location = location;
        this.items = items;
        this.cash = cash;
        this.created = System.currentTimeMillis();
        this.ownerOnlyUntil = created + ownerOnlyMillis;
        this.expiresAt = created + lifeMillis;
    }

    /** Zijn de entities weg (bijv. omdat de chunk even niet geladen was)? */
    boolean needsSpawn() {
        return !removed && (hitbox == null || !hitbox.isValid());
    }

    void spawn(Material icon) {
        World world = location.getWorld();
        removeEntities();
        lastLabel = null;
        display = world.spawn(location.clone().add(0, 0.55, 0), ItemDisplay.class);
        display.setPersistent(false);
        display.setItemStack(new ItemStack(icon));
        display.setBillboard(Display.Billboard.FIXED);
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));

        label = world.spawn(location.clone().add(0, 1.25, 0), TextDisplay.class);
        label.setPersistent(false);
        label.setBillboard(Display.Billboard.CENTER);
        label.setShadowed(true);

        hitbox = world.spawn(location.clone().add(0, 0.1, 0), Interaction.class);
        hitbox.setPersistent(false);
        hitbox.setInteractionWidth(0.9f);
        hitbox.setInteractionHeight(1.0f);
        hitbox.setResponsive(true);
    }

    UUID hitboxId() {
        return hitbox == null ? null : hitbox.getUniqueId();
    }

    /** Elke seconde: de naam bijwerken en de bundel een stukje draaien. */
    void tick(Component text) {
        if (label != null && label.isValid() && !text.equals(lastLabel)) {
            label.text(text);
            lastLabel = text;
        }
        if (display != null && display.isValid()) {
            angle += (float) Math.toRadians(90);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(20);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(angle),
                    new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        }
    }

    void despawn() {
        removed = true;
        removeEntities();
    }

    private void removeEntities() {
        if (display != null && display.isValid()) {
            display.remove();
        }
        if (label != null && label.isValid()) {
            label.remove();
        }
        if (hitbox != null && hitbox.isValid()) {
            hitbox.remove();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
