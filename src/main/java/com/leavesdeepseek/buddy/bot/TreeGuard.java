package com.leavesdeepseek.buddy.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判断原木是否属于「自然生长的树」，避免假人拆村民房子、玩家建筑。
 * <p>判定条件（全部满足才算树）：</p>
 * <ol>
 *   <li>从这块原木向下找到树干最底部，底部必须踩在泥土/草方块等自然地面上；</li>
 *   <li>向上找到树干顶部，顶部周围（水平 2~3 格、上下 3 格）必须有树叶；</li>
 *   <li>树干总高度不超过 {@link #MAX_TRUNK_HEIGHT} 格。</li>
 * </ol>
 * 村民房子的木柱子一般踩在圆石/木板/石砖上，而且柱顶不会挨着树叶，因此会被拒绝。
 */
public final class TreeGuard {

    /** 自然地面：只有站在这些方块上的原木才可能是自然生长的树 */
    private static final Set<Material> SOIL = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.DIRT_PATH, Material.FARMLAND,
            Material.MOSS_BLOCK, Material.MUD, Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS,
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY, Material.SNOW_BLOCK,
            Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
            Material.STONE, Material.DEEPSLATE, Material.TUFF);

    private static final int MAX_TRUNK_HEIGHT = 32;

    private TreeGuard() {
    }

    public static boolean isLog(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_LOG")
                || name.endsWith("_WOOD")
                || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE");
    }

    public static boolean isLeaf(Material material) {
        return material != null && material.name().endsWith("_LEAVES");
    }

    /**
     * 沿垂直方向向下找到树干最底部。
     */
    public static Block trunkBase(Block log) {
        Block cursor = log;
        for (int step = 0; step < MAX_TRUNK_HEIGHT; step++) {
            Block below = cursor.getRelative(BlockFace.DOWN);
            if (!isLog(below.getType())) {
                break;
            }
            cursor = below;
        }
        return cursor;
    }

    /**
     * 沿垂直方向向上找到树干最顶部。
     */
    public static Block trunkTop(Block log) {
        Block cursor = log;
        for (int step = 0; step < MAX_TRUNK_HEIGHT; step++) {
            Block above = cursor.getRelative(BlockFace.UP);
            if (!isLog(above.getType())) {
                break;
            }
            cursor = above;
        }
        return cursor;
    }

    /**
     * 这块原木是否属于一棵自然生长的树。
     *
     * @param radius 树叶搜索半径上限（配置里的 log-leaf-radius，内部限制在 2~3）
     */
    public static boolean isTreeLog(World world, Block block, int radius) {
        if (world == null || block == null || !isLog(block.getType())) {
            return false;
        }
        Block base = trunkBase(block);
        Block top = trunkTop(block);
        if (top.getY() - base.getY() + 1 > MAX_TRUNK_HEIGHT) {
            return false;
        }
        // 树必须长在自然地面上；房子柱子通常踩在圆石/木板/石砖上
        if (!SOIL.contains(base.getRelative(BlockFace.DOWN).getType())) {
            return false;
        }
        // 树干底部周围挤满了木板/楼梯/圆石之类的建筑方块，说明这是房子的一部分
        if (looksLikeStructure(base)) {
            return false;
        }
        // 树冠必须在树干顶部附近；村庄木柱顶部不会挨着树叶
        int leafRadius = Math.max(2, Math.min(3, radius));
        return hasLeavesNear(top, leafRadius, 3);
    }

    /**
     * 树干底部周围是否挤满了人造方块（木板、楼梯、圆石、玻璃等）。
     * 村民房子的墙角柱、玩家建筑的木柱都会命中这条。
     */
    private static boolean looksLikeStructure(Block base) {
        World world = base.getWorld();
        if (world == null) {
            return true;
        }
        int artificial = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Material material = world.getBlockAt(base.getX() + dx, base.getY() + dy,
                            base.getZ() + dz).getType();
                    if (isArtificial(material)) {
                        artificial++;
                    }
                }
            }
        }
        return artificial >= 8;
    }

    private static boolean isArtificial(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_PLANKS")
                || name.endsWith("_STAIRS")
                || name.endsWith("_SLAB")
                || name.endsWith("_FENCE")
                || name.endsWith("_FENCE_GATE")
                || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_BRICKS")
                || name.endsWith("_WALL")
                || name.endsWith("_TERRACOTTA")
                || name.endsWith("_CARPET")
                || name.endsWith("_BED")
                || name.contains("GLASS")
                || name.equals("COBBLESTONE")
                || name.equals("MOSSY_COBBLESTONE")
                || name.equals("SMOOTH_STONE")
                || name.equals("STONE_BRICKS")
                || name.equals("TORCH")
                || name.equals("LANTERN");
    }

    private static boolean hasLeavesNear(Block center, int horizontal, int vertical) {
        World world = center.getWorld();
        if (world == null) {
            return false;
        }
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                for (int dy = -1; dy <= vertical; dy++) {
                    if (isLeaf(world.getBlockAt(center.getX() + dx, center.getY() + dy,
                            center.getZ() + dz).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 收集一整棵树的所有原木（同一树种、间距离散 2 格以内连通）。
     */
    public static List<Block> treeBlocks(World world, Block any, int maxBlocks) {
        List<Block> result = new ArrayList<>();
        if (world == null || any == null || !isLog(any.getType())) {
            return result;
        }
        String family = any.getType().name();
        int limit = Math.max(1, Math.min(maxBlocks, 256));
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(any);
        seen.add(key(any));
        while (!queue.isEmpty() && result.size() < limit) {
            Block current = queue.poll();
            if (!isLog(current.getType())) {
                continue;
            }
            result.add(current);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Block next = world.getBlockAt(current.getX() + dx, current.getY() + dy,
                                current.getZ() + dz);
                        if (!isLog(next.getType()) || !next.getType().name().equals(family)) {
                            continue;
                        }
                        if (seen.add(key(next))) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 在附近找最近的一棵真正的树，返回树干底部；找不到返回 null。
     */
    public static Block findNearestTree(World world, Location origin, int radius) {
        if (world == null || origin == null) {
            return null;
        }
        int r = Math.max(2, Math.min(24, radius));
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        // 同一棵树的每一段原木都会走到同一个树干底部，判重后只校验一次，避免重复扫描
        Set<Long> checkedBases = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -8; dy <= 20; dy++) {
                    Block block = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isLog(block.getType())) {
                        continue;
                    }
                    double distance = block.getLocation().distanceSquared(origin);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    Block base = trunkBase(block);
                    if (!checkedBases.add(key(base))) {
                        continue;
                    }
                    if (!isTreeLog(world, base, 3)) {
                        continue;
                    }
                    best = base;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static long key(Block block) {
        return ((long) (block.getX() & 0x3FFFFFF) << 38)
                | ((long) (block.getZ() & 0x3FFFFFF) << 12)
                | (block.getY() & 0xFFF);
    }
}
