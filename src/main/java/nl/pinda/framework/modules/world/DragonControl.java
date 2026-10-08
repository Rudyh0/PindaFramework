package nl.pinda.framework.modules.world;

import io.papermc.paper.event.block.DragonEggFormEvent;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.storage.ServerData;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.BoundingBox;

/**
 * De ender dragon: komt na een instelbare tijd vanzelf terug, laat elke keer een drakenei
 * achter en iedereen hoort wie hem verslagen heeft.
 *
 * <p>Minecraft laat de draak alleen terugkomen als er spelers in de buurt van het eiland zijn.
 * Is hij "klaar" terwijl er niemand in de End is, dan begint het zodra er iemand komt.
 */
public final class DragonControl implements Listener {

    private static final String KILLED = "dragon.killed-at";
    private static final String KILLER = "dragon.killer";
    private static final String KILLS = "dragon.kills";
    private static final String FORCE = "dragon.force-respawn";
    /** Vanilla kijkt binnen 192 blokken van het midden (0, 128, 0) wie er bij het gevecht is. */
    private static final double ARENA_RANGE = 192;
    /** Lukt het terugkomen niet, dan proberen we het pas na een minuut opnieuw. */
    private static final long RETRY_MILLIS = 60_000;

    /** Hoe het met de draak gaat, voor /dragon en het paneel. */
    public record Status(boolean end, boolean alive, boolean loaded, double health, double maxHealth, long killedAt, String killer,
                         int kills, boolean respawning, boolean forced, long respawnAt, int playersNear) {

        static Status noEnd() {
            return new Status(false, false, false, 0, 0, 0, null, 0, false, false, 0, 0);
        }
    }

    /** Wat er gebeurde bij "nu terug laten komen". */
    public enum Result { STARTED, WAITING, QUEUED, ALIVE, BUSY, NO_END, FAILED }

    /** Leeft hij, is hij net verslagen (doodsanimatie van 10 seconden) of is hij weg? */
    private enum State { ALIVE, DYING, DEAD }

    private final PindaFramework plugin;
    private final WorldControlModule module;
    private long retryAt;

    DragonControl(PindaFramework plugin, WorldControlModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private ServerData data() {
        return plugin.serverData();
    }

    private YamlConfiguration cfg() {
        return module.settings();
    }

    boolean autoRespawn() {
        return cfg().getBoolean("dragon.respawn", true);
    }

    long delayMillis() {
        return Math.max(0, cfg().getLong("dragon.respawn-minutes", 120)) * 60_000L;
    }

    /** De End met een drakengevecht (de eerste, als er meer zijn), of null. */
    static World end(PindaFramework plugin) {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END && world.getEnderDragonBattle() != null) {
                return world;
            }
        }
        return null;
    }

    /** Hoort dit gevecht bij de End die we bijhouden? */
    private boolean ours(World world) {
        World end = end(plugin);
        return end != null && end.equals(world);
    }

    /**
     * Hoe gaat het met de draak? Is hij niet geladen, dan kijken we naar de bossbar van het
     * gevecht: die is zichtbaar zolang de draak niet verslagen is (dat zet Minecraft elke tick).
     */
    private static State state(DragonBattle battle) {
        EnderDragon dragon = battle.getEnderDragon();
        if (dragon != null) {
            return dragon.isDead() || dragon.getHealth() <= 0 ? State.DYING : State.ALIVE;
        }
        return battle.getBossBar().isVisible() ? State.ALIVE : State.DEAD;
    }

    private static List<Player> playersNear(World end) {
        Location center = new Location(end, 0, 128, 0);
        List<Player> near = new ArrayList<>();
        for (Player player : end.getPlayers()) {
            if (!player.isDead() && player.getLocation().distanceSquared(center) <= ARENA_RANGE * ARENA_RANGE) {
                near.add(player);
            }
        }
        return near;
    }

    // ============================================================ elke 5 seconden

    void check() {
        World end = end(plugin);
        if (end == null) {
            return;
        }
        DragonBattle battle = end.getEnderDragonBattle();
        State state = state(battle);
        if (state == State.ALIVE && battle.getEnderDragon() != null && data().get(FORCE) != null) {
            data().remove(FORCE);
        }
        if (state != State.DEAD) {
            return;
        }
        long now = System.currentTimeMillis();
        long killed = data().getLong(KILLED, 0);
        if (killed <= 0) {
            // Verslagen terwijl we het niet zagen (bijv. met /kill of voordat deze module er was).
            killed = now;
            data().set(KILLED, now);
        }
        if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE || now < retryAt) {
            return;
        }
        boolean forced = data().get(FORCE) != null;
        if (!forced && (!autoRespawn() || now < killed + delayMillis())) {
            return;
        }
        if (playersNear(end).isEmpty()) {
            return;
        }
        if (startRespawn(end, battle)) {
            data().remove(FORCE);
        }
    }

    /**
     * Start het terugkomen zoals Minecraft het doet als een speler vier end crystals op het
     * portaal zet: we zetten die crystals er zelf neer (onbreekbaar, zodat niemand het kan
     * onderbreken). Crystals die er al staan, gebruiken we.
     */
    private boolean startRespawn(World end, DragonBattle battle) {
        if (!battle.hasBeenPreviouslyKilled()) {
            // Zonder eerste overwinning kan Minecraft geen nieuwe draak maken.
            retryAt = System.currentTimeMillis() + RETRY_MILLIS;
            return false;
        }
        Location portal = battle.getEndPortalLocation();
        if (portal == null) {
            battle.generateEndPortal(true);
            portal = battle.getEndPortalLocation();
        }
        if (portal == null || !end.isChunkLoaded(portal.getBlockX() >> 4, portal.getBlockZ() >> 4)) {
            return false;
        }
        List<EnderCrystal> crystals = new ArrayList<>();
        List<EnderCrystal> spawned = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            int x = portal.getBlockX() + face.getModX() * 3;
            int y = portal.getBlockY() + 1;
            int z = portal.getBlockZ() + face.getModZ() * 3;
            EnderCrystal existing = null;
            for (Entity entity : end.getNearbyEntities(new BoundingBox(x, y, z, x + 1, y + 1, z + 1))) {
                if (entity instanceof EnderCrystal crystal && !crystal.isDead()) {
                    existing = crystal;
                    break;
                }
            }
            if (existing != null) {
                existing.setInvulnerable(true);
                crystals.add(existing);
                continue;
            }
            EnderCrystal crystal = end.spawn(new Location(end, x + 0.5, y, z + 0.5), EnderCrystal.class, created -> {
                created.setShowingBottom(false);
                created.setInvulnerable(true);
            });
            crystals.add(crystal);
            spawned.add(crystal);
        }
        boolean started;
        try {
            started = battle.initiateRespawn(crystals);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("De ender dragon kon niet terugkomen: " + e.getMessage());
            started = false;
        }
        if (!started) {
            spawned.forEach(Entity::remove);
            retryAt = System.currentTimeMillis() + RETRY_MILLIS;
            return false;
        }
        plugin.getLogger().info("De ender dragon komt terug.");
        return true;
    }

    /** Laat de draak nu terugkomen (of zodra dat kan). */
    Result respawnNow() {
        World end = end(plugin);
        if (end == null) {
            return Result.NO_END;
        }
        DragonBattle battle = end.getEnderDragonBattle();
        State state = state(battle);
        if (state == State.ALIVE) {
            return Result.ALIVE;
        }
        if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            return Result.BUSY;
        }
        if (state == State.DYING) {
            data().set(FORCE, 1);
            return Result.QUEUED;
        }
        if (playersNear(end).isEmpty()) {
            data().set(FORCE, 1);
            return Result.WAITING;
        }
        retryAt = 0;
        if (!startRespawn(end, battle)) {
            return Result.FAILED;
        }
        data().remove(FORCE);
        return Result.STARTED;
    }

    Status status() {
        World end = end(plugin);
        if (end == null) {
            return Status.noEnd();
        }
        DragonBattle battle = end.getEnderDragonBattle();
        boolean alive = state(battle) == State.ALIVE;
        EnderDragon dragon = battle.getEnderDragon();
        double health = 0;
        double max = 200;
        if (dragon != null) {
            health = Math.max(0, dragon.getHealth());
            AttributeInstance attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
            max = attribute != null ? attribute.getValue() : 200;
        }
        long killed = alive ? 0 : data().getLong(KILLED, 0);
        boolean respawning = !alive && battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE;
        boolean forced = !alive && data().get(FORCE) != null;
        long respawnAt = 0;
        if (!alive && !respawning) {
            if (forced) {
                respawnAt = System.currentTimeMillis();
            } else if (autoRespawn()) {
                respawnAt = (killed > 0 ? killed : System.currentTimeMillis()) + delayMillis();
            }
        }
        String killer = data().get(KILLER);
        return new Status(true, alive, alive && dragon != null, health, max, killed,
                killer == null || killer.isEmpty() ? null : killer,
                (int) data().getLong(KILLS, 0), respawning, forced, respawnAt, playersNear(end).size());
    }

    // ============================================================ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || dragon.getDragonBattle() == null || !ours(dragon.getWorld())) {
            return;
        }
        Player killer = dragon.getKiller();
        data().set(KILLED, System.currentTimeMillis());
        data().set(KILLER, killer != null ? killer.getName() : "");
        data().set(KILLS, data().getLong(KILLS, 0) + 1);
        data().remove(FORCE);
        if (cfg().getBoolean("dragon.announce-kill", true)) {
            if (killer != null) {
                broadcast("world.dragon.killed", Text.p("player", killer.getName()));
            } else {
                broadcast("world.dragon.killed-unknown");
            }
        }
    }

    /**
     * Minecraft legt alleen na de eerste overwinning een ei; Paper roept het event daarna ook
     * aan, maar al geannuleerd. Wij zetten het weer aan (vroeg, zodat andere plugins het nog
     * kunnen tegenhouden).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onEggForm(DragonEggFormEvent event) {
        if (event.isCancelled() && cfg().getBoolean("dragon.egg-every-kill", true) && ours(event.getBlock().getWorld())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !ours(dragon.getWorld())) {
            return;
        }
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) {
            return;
        }
        data().set(KILLED, 0);
        data().remove(FORCE);
        retryAt = 0;
        // De allereerste draak van een nieuwe End is niet "terug".
        if (battle.hasBeenPreviouslyKilled()) {
            announceRespawn();
        }
    }

    // ============================================================ meldingen

    private void broadcast(String key, TagResolver... resolvers) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.lang().send(player, key, resolvers);
        }
        plugin.lang().send(plugin.getServer().getConsoleSender(), key, resolvers);
    }

    private void announceRespawn() {
        if (!cfg().getBoolean("dragon.announce-respawn", true)) {
            return;
        }
        broadcast("world.dragon.respawn");
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1f);
        }
    }
}
