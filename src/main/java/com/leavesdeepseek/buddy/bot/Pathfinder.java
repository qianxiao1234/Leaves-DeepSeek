package com.leavesdeepseek.buddy.bot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 基于方块网格的 A* 寻路。
 * <p>只做“像玩家一样走过去”的路径：支持上台阶、下落，避开岩浆与危险方块。</p>
 */
public final class Pathfinder {

    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE);

    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** 斜向：两个正交方向都通时才允许，避免卡在墙角；代价按 √2 计算 */
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double DIAGONAL_COST = 1.414;
    /** 涉水的额外代价：有干路就走干路，没有才游泳（跟随主人过河仍然可以走） */
    private static final double WATER_FEET_COST = 2.0;
    private static final double WATER_HEAD_COST = 1.5;
    private static final double DEEP_WATER_COST = 1.5;

    private Pathfinder() {
    }

    private record Node(int x, int y, int z) {
    }

    private record Edge(Node to, double cost) {
    }

    private record Entry(Node node, double f) {
    }

    /**
     * 计算从 start 到 goal 的方块中心路径。
     *
     * @return 路径点（包含终点，不含起点）；无路可走时返回空列表
     */
    public static List<Location> findPath(World world, Location start, Location goal, int maxNodes, int maxRange) {
        if (world == null || start == null || goal == null) {
            return List.of();
        }
        BlockCache cache = new BlockCache(world);

        Node from = nearestWalkable(cache, start.getBlockX(), start.getBlockY(), start.getBlockZ());
        if (from == null) {
            from = new Node(start.getBlockX(), (int) Math.floor(start.getY()), start.getBlockZ());
        }
        Node to = findGoal(cache, goal.getBlockX(), goal.getBlockY(), goal.getBlockZ());
        if (to == null) {
            return List.of();
        }
        if (from.equals(to)) {
            return List.of();
        }

        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Set<Node> closed = new HashSet<>();

        gScore.put(from, 0.0);
        open.add(new Entry(from, heuristic(from, to)));

        Node closest = from;
        double closestH = heuristic(from, to);
        int explored = 0;

        while (!open.isEmpty() && explored++ < maxNodes) {
            Entry entry = open.poll();
            Node current = entry.node();
            if (!closed.add(current)) {
                continue;
            }
            double h = heuristic(current, to);
            if (h < closestH) {
                closestH = h;
                closest = current;
            }
            if (current.equals(to) || nearEnough(current, to)) {
                closest = current;
                closestH = h;
                break;
            }
            for (Edge edge : neighbors(cache, current, to, maxRange)) {
                if (closed.contains(edge.to())) {
                    continue;
                }
                double tentative = gScore.getOrDefault(current, Double.MAX_VALUE) + edge.cost();
                if (tentative < gScore.getOrDefault(edge.to(), Double.MAX_VALUE)) {
                    gScore.put(edge.to(), tentative);
                    cameFrom.put(edge.to(), current);
                    open.add(new Entry(edge.to(), tentative + heuristic(edge.to(), to)));
                }
            }
        }

        // 目标点经常是被方块占住的位置（比如树干），允许停在目标旁边 1~2 格
        if (!nearEnough(closest, to) && closestH > 3.0) {
            return List.of();
        }
        return reconstruct(world, cameFrom, closest);
    }

    private static boolean nearEnough(Node a, Node b) {
        return Math.abs(a.x() - b.x()) <= 1
                && Math.abs(a.z() - b.z()) <= 1
                && Math.abs(a.y() - b.y()) <= 2;
    }

    private static List<Edge> neighbors(BlockCache cache, Node node, Node goal, int maxRange) {
        List<Edge> edges = new ArrayList<>(4);
        for (int[] direction : HORIZONTAL) {
            int nx = node.x() + direction[0];
            int nz = node.z() + direction[1];
            if (Math.abs(nx - goal.x()) > maxRange || Math.abs(nz - goal.z()) > maxRange) {
                continue;
            }

            // 上台阶：前方高一格，且上方有空间
            if (clearance(cache, node.x(), node.y(), node.z()) && walkable(cache, nx, node.y() + 1, nz)) {
                edges.add(new Edge(new Node(nx, node.y() + 1, nz),
                        1.8 + waterCost(cache, nx, node.y() + 1, nz)));
            }

            // 平地
            if (walkable(cache, nx, node.y(), nz)) {
                edges.add(new Edge(new Node(nx, node.y(), nz),
                        1.0 + waterCost(cache, nx, node.y(), nz)));
            }

            // 下落 1~3 格
            for (int drop = 1; drop <= 3; drop++) {
                boolean columnClear = true;
                for (int y = node.y() - drop + 1; y < node.y(); y++) {
                    if (!passable(cache, nx, y, nz)) {
                        columnClear = false;
                        break;
                    }
                }
                if (columnClear && walkable(cache, nx, node.y() - drop, nz)) {
                    edges.add(new Edge(new Node(nx, node.y() - drop, nz),
                            1.0 + drop * 0.4 + waterCost(cache, nx, node.y() - drop, nz)));
                    break;
                }
            }
        }

        // 斜向移动：降低锯齿，让路线更像玩家走出来的直线
        for (int[] direction : DIAGONAL) {
            int nx = node.x() + direction[0];
            int nz = node.z() + direction[1];
            if (Math.abs(nx - goal.x()) > maxRange || Math.abs(nz - goal.z()) > maxRange) {
                continue;
            }
            // 两个正交邻居都得走得通，否则会卡在墙角
            if (!walkable(cache, node.x() + direction[0], node.y(), node.z())
                    || !walkable(cache, node.x(), node.y(), node.z() + direction[1])) {
                continue;
            }
            if (walkable(cache, nx, node.y(), nz)) {
                edges.add(new Edge(new Node(nx, node.y(), nz),
                        DIAGONAL_COST + waterCost(cache, nx, node.y(), nz)));
            }
            if (clearance(cache, node.x(), node.y(), node.z()) && walkable(cache, nx, node.y() + 1, nz)) {
                edges.add(new Edge(new Node(nx, node.y() + 1, nz),
                        DIAGONAL_COST * 1.35 + waterCost(cache, nx, node.y() + 1, nz)));
            }
        }
        return edges;
    }

    private static List<Location> reconstruct(World world, Map<Node, Node> cameFrom, Node end) {
        LinkedList<Node> nodes = new LinkedList<>();
        Node current = end;
        int guard = 0;
        while (current != null && guard++ < 6000) {
            nodes.addFirst(current);
            current = cameFrom.get(current);
        }
        List<Location> path = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            // 第一个点是起点所在格子，去掉它，否则每次重新寻路都会先掉头走回格子中心
            if (i == 0 && nodes.size() > 1) {
                continue;
            }
            Node node = nodes.get(i);
            path.add(new Location(world, node.x() + 0.5, node.y(), node.z() + 0.5));
        }
        return path;
    }

    private static Node findGoal(BlockCache cache, int x, int y, int z) {
        Node direct = nearestWalkable(cache, x, y, z);
        if (direct != null) {
            return direct;
        }
        // 目标被方块占据时（树干、矿石原位），在周围找最近的可以站立的位置
        for (int radius = 1; radius <= 4; radius++) {
            Node best = null;
            int bestScore = Integer.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 1; dy >= -2; dy--) {
                        Node node = nearestWalkable(cache, x + dx, y + dy, z + dz);
                        if (node == null) {
                            continue;
                        }
                        int score = Math.abs(node.y() - y) * 2
                                + Math.abs(node.x() - x) + Math.abs(node.z() - z);
                        if (score < bestScore) {
                            bestScore = score;
                            best = node;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static Node nearestWalkable(BlockCache cache, int x, int y, int z) {
        for (int dy = 1; dy >= -4; dy--) {
            if (walkable(cache, x, y + dy, z)) {
                return new Node(x, y + dy, z);
            }
        }
        return null;
    }

    private static boolean clearance(BlockCache cache, int x, int y, int z) {
        return passable(cache, x, y, z) && passable(cache, x, y + 1, z);
    }

    private static boolean walkable(BlockCache cache, int x, int y, int z) {
        if (y < cache.world().getMinHeight() || y > cache.world().getMaxHeight() - 2) {
            return false;
        }
        if (!clearance(cache, x, y, z)) {
            return false;
        }
        Material below = cache.at(x, y - 1, z);
        return below.isSolid() || below == Material.WATER;
    }

    private static boolean passable(BlockCache cache, int x, int y, int z) {
        if (y < cache.world().getMinHeight() || y > cache.world().getMaxHeight() - 1) {
            return false;
        }
        Material material = cache.at(x, y, z);
        if (DANGEROUS.contains(material)) {
            return false;
        }
        if (material == Material.WATER) {
            return true;
        }
        return !material.isSolid();
    }

    /**
     * 涉水额外代价：能绕开就绕开，绕不开才游过去（跟随主人过河时仍然可以走）。
     */
    private static double waterCost(BlockCache cache, int x, int y, int z) {
        if (cache.at(x, y, z) != Material.WATER) {
            return 0.0;
        }
        double cost = WATER_FEET_COST;
        if (cache.at(x, y + 1, z) == Material.WATER) {
            // 水没过头顶：游泳更慢、还会消耗氧气
            cost += WATER_HEAD_COST;
        }
        if (cache.at(x, y - 1, z) == Material.WATER) {
            cost += DEEP_WATER_COST;
        }
        return cost;
    }

    private static double heuristic(Node a, Node b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) + Math.abs(a.y() - b.y()) * 1.5;
    }

    /**
     * 一次寻路内共享的方块类型缓存，避免重复读取区块。
     */
    private static final class BlockCache {

        private final World world;
        private final Map<Long, Material> cache = new HashMap<>(8192);

        private BlockCache(World world) {
            this.world = world;
        }

        private World world() {
            return world;
        }

        private Material at(int x, int y, int z) {
            long key = ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            Material material = cache.get(key);
            if (material == null) {
                material = world.getBlockAt(x, y, z).getType();
                cache.put(key, material);
            }
            return material;
        }
    }
}
