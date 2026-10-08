package nl.pinda.framework.modules.timber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/**
 * Zoekt bij een omgehakt blok hout de hele boom: de stam en takken, de bladeren en wat eraan
 * hangt (lianen, cacaobonen, ...).
 *
 * <p>Bladeren horen bij deze boom als Minecraft zelf vindt dat ze het dichtst bij dit hout
 * zitten (de 'distance' van bladeren). Zo blijven de bladeren van een boom ernaast staan.
 * Zelf geplaatste bladeren (persistent) tellen nooit mee, en zonder genoeg natuurlijke
 * bladeren is het geen boom maar een bouwwerk: dan gebeurt er niets.
 */
final class TreeDetector {

    /** Een gevonden boom. Van elk blok is bewaard hoe het eruitzag, om later te controleren of het niet veranderd is. */
    record DetectedTree(TimberSettings.TreeType type, Block start, List<Block> logs, List<Block> leaves,
                        List<Block> extras, Map<Block, BlockData> snapshot) {
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private TreeDetector() {
    }

    /** De boom bij dit blok, of null als het geen (natuurlijke) boom is of als hij te groot is. */
    static DetectedTree detect(Block start, TimberSettings settings, Predicate<Block> placed) {
        List<TimberSettings.TreeType> candidates = settings.byLog.get(start.getType());
        if (candidates == null || placed.test(start)) {
            return null;
        }
        for (TimberSettings.TreeType type : candidates) {
            DetectedTree tree = detect(start, type, settings, placed);
            if (tree != null) {
                return tree;
            }
        }
        return null;
    }

    private static DetectedTree detect(Block start, TimberSettings.TreeType type, TimberSettings settings, Predicate<Block> placed) {
        World world = start.getWorld();
        if (settings.requireGround && !onGround(start, type, settings)) {
            return null; // een echte boom staat op aarde, gras, modder, ... en niet op een fundering
        }
        int sx = start.getX();
        int sy = start.getY();
        int sz = start.getZ();
        double maxSquared = type.branchDistance() * type.branchDistance();

        // 1. Stam en takken: al het hout dat aan elkaar zit (ook schuin), niet te ver van de stam
        Set<Block> logs = new LinkedHashSet<>();
        logs.add(start);
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            Block block = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        int x = block.getX() + dx;
                        int y = block.getY() + dy;
                        int z = block.getZ() + dz;
                        if (settings.onlyUpwards && y < sy) {
                            continue;
                        }
                        double hx = x - sx;
                        double hz = z - sz;
                        if (hx * hx + hz * hz > maxSquared || !loaded(world, x, y, z)) {
                            continue;
                        }
                        Block next = world.getBlockAt(x, y, z);
                        if (!type.logs().contains(next.getType()) || logs.contains(next)) {
                            continue;
                        }
                        if (placed.test(next)) {
                            return null; // er zit zelf geplaatst hout aan vast: dan laten we alles staan
                        }
                        logs.add(next);
                        if (logs.size() > settings.maxLogs) {
                            return null; // zo groot is geen gewone boom: waarschijnlijk een bouwwerk
                        }
                        queue.add(next);
                    }
                }
            }
        }

        // Zit er iets gebouwds aan het hout vast (planken, trappen, glas, deuren, ...)? Dan is het geen gewone boom.
        for (Block log : logs) {
            for (int[] face : FACES) {
                int x = log.getX() + face[0];
                int y = log.getY() + face[1];
                int z = log.getZ() + face[2];
                if (loaded(world, x, y, z) && built(world.getBlockAt(x, y, z).getType())) {
                    return null;
                }
            }
        }

        // 2. Bladeren: vanaf het hout, zolang Minecraft vindt dat ze bij dit hout horen
        Set<Block> leaves = new LinkedHashSet<>();
        Map<Block, Integer> distance = new HashMap<>();
        ArrayDeque<Block> leafQueue = new ArrayDeque<>();
        for (Block log : logs) {
            distance.put(log, 0);
            leafQueue.add(log);
        }
        int maxLeaves = settings.maxLeaves;
        while (!leafQueue.isEmpty() && leaves.size() < maxLeaves) {
            Block block = leafQueue.poll();
            int next = distance.get(block) + 1;
            if (next > type.leafDistance()) {
                continue;
            }
            for (int[] face : FACES) {
                int x = block.getX() + face[0];
                int y = block.getY() + face[1];
                int z = block.getZ() + face[2];
                if (!loaded(world, x, y, z)) {
                    continue;
                }
                Block neighbour = world.getBlockAt(x, y, z);
                if (distance.containsKey(neighbour) || !type.leaves().contains(neighbour.getType())) {
                    continue;
                }
                BlockData data = neighbour.getBlockData();
                if (data instanceof Leaves leaf && (leaf.isPersistent() || next > leaf.getDistance())) {
                    continue; // zelf geplaatst, of hoort bij een andere boom die dichterbij staat
                }
                distance.put(neighbour, next);
                leaves.add(neighbour);
                leafQueue.add(neighbour);
            }
        }
        if (leaves.size() < settings.minLeaves) {
            return null;
        }

        // 3. Wat er echt aan de boom hangt of erop ligt: lianen, cacaobonen, sneeuw, mos, ...
        Set<Block> extras = new LinkedHashSet<>();
        if (!settings.attachments.isEmpty()) {
            Set<Block> tree = new HashSet<>(logs);
            tree.addAll(leaves);
            for (Block block : tree) {
                // Erbovenop: sneeuw en mostapijt
                attach(world, block, 0, 1, 0, settings, tree, extras, ON_TOP);
                // Eronder: hangend mos, propagules en lianen (en wat daar weer onder hangt)
                attach(world, block, 0, -1, 0, settings, tree, extras, HANGING);
                // Ernaast: lianen en cacaobonen
                for (int[] face : FACES) {
                    if (face[1] == 0) {
                        attach(world, block, face[0], 0, face[2], settings, tree, extras, SIDE);
                    }
                }
                if (extras.size() >= 400) {
                    break;
                }
            }
        }

        Map<Block, BlockData> snapshot = new HashMap<>();
        for (Block block : logs) {
            snapshot.put(block, block.getBlockData());
        }
        for (Block block : leaves) {
            snapshot.put(block, block.getBlockData());
        }
        for (Block block : extras) {
            snapshot.put(block, block.getBlockData());
        }
        return new DetectedTree(type, start, new ArrayList<>(logs), new ArrayList<>(leaves), new ArrayList<>(extras), snapshot);
    }

    private static final Set<String> ON_TOP = Set.of("SNOW", "MOSS_CARPET", "PALE_MOSS_CARPET");
    private static final Set<String> HANGING = Set.of("PALE_HANGING_MOSS", "MANGROVE_PROPAGULE", "VINE");
    private static final Set<String> SIDE = Set.of("VINE", "COCOA");

    /** Neemt een aanhangsel mee als het van de juiste soort is; hangende dingen ook verder naar beneden. */
    private static void attach(World world, Block from, int dx, int dy, int dz, TimberSettings settings, Set<Block> tree,
                               Set<Block> extras, Set<String> kinds) {
        int x = from.getX() + dx;
        int y = from.getY() + dy;
        int z = from.getZ() + dz;
        if (!loaded(world, x, y, z)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        String name = block.getType().name();
        if (!kinds.contains(name) || !settings.attachments.contains(block.getType()) || tree.contains(block) || !extras.add(block)) {
            return;
        }
        if (HANGING.contains(name)) {
            // Wat eronder hangt van dezelfde soort, bijv. een lange liaan
            Block below = block.getRelative(0, -1, 0);
            for (int step = 0; step < 32 && below.getType() == block.getType() && extras.add(below); step++) {
                below = below.getRelative(0, -1, 0);
            }
        }
    }

    /** Gebouwde blokken: als die aan het hout vastzitten, is het geen gewone boom. */
    static boolean built(Material material) {
        String name = material.name();
        if (name.equals("MOSS_CARPET") || name.equals("PALE_MOSS_CARPET")) {
            return false;
        }
        return name.endsWith("_PLANKS") || name.endsWith("_STAIRS") || name.endsWith("_SLAB") || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE") || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_SIGN")
                || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE") || name.endsWith("_BED") || name.endsWith("_CARPET")
                || name.endsWith("_WOOL") || name.endsWith("_BRICKS") || name.endsWith("_WALL") || name.endsWith("_CONCRETE")
                || name.endsWith("_TERRACOTTA") || name.endsWith("_BANNER") || name.contains("GLASS") || name.startsWith("STRIPPED_")
                || name.endsWith("_WOOD") || name.endsWith("_SHULKER_BOX")
                || switch (name) {
                    case "CHEST", "TRAPPED_CHEST", "BARREL", "CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER", "LADDER",
                         "BOOKSHELF", "CHISELED_BOOKSHELF", "LECTERN", "ANVIL", "HAY_BLOCK", "COBBLESTONE", "STONE_BRICKS",
                         "SMOOTH_STONE", "IRON_BARS", "CHAIN", "ENDER_CHEST" -> true;
                    default -> false;
                };
    }

    /** Staat de stam (onder het gehakte blok door) op natuurlijke grond? */
    private static boolean onGround(Block start, TimberSettings.TreeType type, TimberSettings settings) {
        Block below = start.getRelative(0, -1, 0);
        for (int depth = 0; depth < 40 && type.logs().contains(below.getType()); depth++) {
            below = below.getRelative(0, -1, 0);
        }
        return settings.ground.contains(below.getType());
    }

    /** Nooit een chunk laden alleen om een boom te zoeken. */
    private static boolean loaded(World world, int x, int y, int z) {
        return y >= world.getMinHeight() && y < world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4);
    }
}
