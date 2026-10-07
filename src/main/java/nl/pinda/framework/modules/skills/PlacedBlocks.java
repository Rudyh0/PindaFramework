package nl.pinda.framework.modules.skills;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Onthoudt welke blokken door spelers zijn neergezet, zodat die geen XP geven. Alleen voor
 * blokken die XP kunnen opleveren. De gegevens staan in de chunk zelf (geen database nodig)
 * en worden in het geheugen gehouden zolang de chunk geladen is.
 */
final class PlacedBlocks implements Listener {

    private static final class ChunkData {
        final Set<Long> positions;
        boolean dirty;

        ChunkData(Set<Long> positions) {
            this.positions = positions;
        }
    }

    private final NamespacedKey key;
    private final Supplier<SkillRules> rules;
    private final Map<UUID, Map<Long, ChunkData>> worlds = new HashMap<>();

    PlacedBlocks(PindaFramework plugin, Supplier<SkillRules> rules) {
        this.key = new NamespacedKey(plugin, "placed_blocks");
        this.rules = rules;
    }

    // ============================================================ opvragen en bijhouden

    boolean isPlaced(Block block) {
        ChunkData data = data(block.getChunk(), false);
        return data != null && data.positions.contains(pack(block));
    }

    void mark(Block block) {
        ChunkData data = data(block.getChunk(), true);
        if (data.positions.add(pack(block))) {
            data.dirty = true;
        }
    }

    void unmark(Block block) {
        ChunkData data = data(block.getChunk(), false);
        if (data != null && data.positions.remove(pack(block))) {
            data.dirty = true;
        }
    }

    private static long pack(Block block) {
        return ((long) (block.getY() + 4096) << 8) | ((long) (block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private ChunkData data(Chunk chunk, boolean create) {
        Map<Long, ChunkData> chunks = worlds.computeIfAbsent(chunk.getWorld().getUID(), id -> new HashMap<>());
        long chunkKey = chunk.getChunkKey();
        ChunkData data = chunks.get(chunkKey);
        if (data != null) {
            return data;
        }
        long[] stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.LONG_ARRAY);
        if (stored == null && !create) {
            return null;
        }
        Set<Long> positions = new HashSet<>();
        if (stored != null) {
            for (long position : stored) {
                positions.add(position);
            }
        }
        data = new ChunkData(positions);
        chunks.put(chunkKey, data);
        return data;
    }

    private void write(Chunk chunk, ChunkData data) {
        if (!data.dirty) {
            return;
        }
        if (data.positions.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
        } else {
            long[] array = new long[data.positions.size()];
            int index = 0;
            for (long position : data.positions) {
                array[index++] = position;
            }
            chunk.getPersistentDataContainer().set(key, PersistentDataType.LONG_ARRAY, array);
        }
        data.dirty = false;
    }

    /** Schrijft alles weg naar de chunks (bij stoppen). */
    void saveAll(Iterable<World> loadedWorlds) {
        for (World world : loadedWorlds) {
            saveWorld(world, false);
        }
        worlds.clear();
    }

    private void saveWorld(World world, boolean keep) {
        Map<Long, ChunkData> chunks = worlds.get(world.getUID());
        if (chunks == null) {
            return;
        }
        for (Map.Entry<Long, ChunkData> entry : new ArrayList<>(chunks.entrySet())) {
            long chunkKey = entry.getKey();
            int x = (int) chunkKey;
            int z = (int) (chunkKey >> 32);
            if (!world.isChunkLoaded(x, z)) {
                chunks.remove(chunkKey);
                continue;
            }
            write(world.getChunkAt(x, z), entry.getValue());
        }
        if (!keep) {
            worlds.remove(world.getUID());
        }
    }

    // ============================================================ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (rules.get().tracked.contains(block.getType())) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!rules.get().generatedGivesXp && rules.get().tracked.contains(event.getNewState().getType())) {
            mark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /** Neergezette blokken die door een zuiger verschoven worden, blijven neergezet. */
    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> placed = new ArrayList<>();
        for (Block block : blocks) {
            if (isPlaced(block)) {
                placed.add(block);
            }
        }
        for (Block block : placed) {
            unmark(block);
        }
        for (Block block : placed) {
            mark(block.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            unmark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            unmark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        unmark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<Long, ChunkData> chunks = worlds.get(event.getWorld().getUID());
        if (chunks == null) {
            return;
        }
        ChunkData data = chunks.remove(event.getChunk().getChunkKey());
        if (data != null) {
            write(event.getChunk(), data);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldSave(WorldSaveEvent event) {
        saveWorld(event.getWorld(), true);
    }
}
