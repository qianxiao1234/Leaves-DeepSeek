package com.leavesdeepseek.buddy.bot;

import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

/** Shared support-surface checks for full and partial block collision boxes. */
public final class BlockSupport {

    private static final double HALF_PLAYER_WIDTH = 0.3;
    private static final double MAX_SURFACE_DROP = 1.0 / 16.0 + 1.0E-3;
    private static final double HEIGHT_EPSILON = 1.0E-3;

    private BlockSupport() {
    }

    public static boolean supportsFeet(Block block, double x, double y, double z) {
        if (block == null || block.isPassable()) {
            return false;
        }
        BoundingBox box = block.getBoundingBox();
        double surfaceDrop = y - box.getMaxY();
        return surfaceDrop >= -HEIGHT_EPSILON && surfaceDrop <= MAX_SURFACE_DROP
                && box.getMaxX() > x - HALF_PLAYER_WIDTH
                && box.getMinX() < x + HALF_PLAYER_WIDTH
                && box.getMaxZ() > z - HALF_PLAYER_WIDTH
                && box.getMinZ() < z + HALF_PLAYER_WIDTH;
    }

    /** Finds a partial or full collision surface directly beneath a player's feet. */
    public static boolean supportsAt(World world, double x, double y, double z) {
        if (world == null) {
            return false;
        }
        Block feet = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return supportsFeet(feet, x, y, z)
                || supportsFeet(feet.getRelative(org.bukkit.block.BlockFace.DOWN), x, y, z);
    }
}
