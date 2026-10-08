package nl.pinda.framework.modules.timber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
                        if (!type.logs().contains(next.getType()) || logs.contains(next) || placed.test(next)) {
                            continue;
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

        // 2. Bladeren: vanaf het hout, zolang Minecraft vindt dat ze bij dit hout horen
        Set<Block> leaves = new LinkedHashSet<>();
        Map<Block, Integer> distance = new HashMap<>();
        ArrayDeque<Block> leafQueue = new ArrayDeque<>();
        for (Block log : logs) {
            distance.put(log, 0);
            leafQueue.add(log);
        }
        int maxLeaves = settings.maxLogs * 10;
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

        // 3. Wat eraan hangt: lianen, cacaobonen, mos, ...
        Set<Block> extras = new LinkedHashSet<>();
        if (!settings.attachments.isEmpty()) {
            ArrayDeque<Block> extraQueue = new ArrayDeque<>(logs);
            extraQueue.addAll(leaves);
            while (!extraQueue.isEmpty() && extras.size() < 400) {
                Block block = extraQueue.poll();
                for (int[] face : FACES) {
                    int x = block.getX() + face[0];
                    int y = block.getY() + face[1];
                    int z = block.getZ() + face[2];
                    if (!loaded(world, x, y, z)) {
                        continue;
                    }
                    Block neighbour = world.getBlockAt(x, y, z);
                    if (settings.attachments.contains(neighbour.getType()) && !logs.contains(neighbour)
                            && !leaves.contains(neighbour) && extras.add(neighbour)) {
                        extraQueue.add(neighbour);
                    }
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
