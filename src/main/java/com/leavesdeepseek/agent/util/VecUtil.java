package com.leavesdeepseek.agent.util;

import com.google.gson.JsonArray;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;

/** 坐标 / 实体查找的小工具。 */
public final class VecUtil {

    private VecUtil() {
    }

    /** 简单的三维点（参数来自 JSON，避免直接持有 Location）。 */
    public record Point(double x, double y, double z) {
        public Location toLocation(org.bukkit.World world) {
            return new Location(world, x, y, z);
        }

        public double distanceTo(Location location) {
            double dx = location.getX() - x;
            double dy = location.getY() - y;
            double dz = location.getZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static JsonArray toArray(Location location) {
        JsonArray array = new JsonArray();
        if (location == null) {
            return array;
        }
        array.add(round(location.getX()));
        array.add(round(location.getY()));
        array.add(round(location.getZ()));
        return array;
    }

    public static JsonArray toArray(Vector vector) {
        JsonArray array = new JsonArray();
        if (vector == null) {
            return array;
        }
        array.add(round(vector.getX()));
        array.add(round(vector.getY()));
        array.add(round(vector.getZ()));
        return array;
    }

    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 按网络实体 id 在躯壳附近（64 格）找实体。 */
    public static Entity entityById(Bot bot, int entityId) {
        if (bot == null || !bot.isValid()) {
            return null;
        }
        for (Entity entity : bot.getNearbyEntities(64, 64, 64)) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    /** 水平距离（跟随 / 攻击判定用）。 */
    public static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 视线朝向判定：目标是否大致看着 {@code viewer}。 */
    public static boolean lookingAt(Player viewer, Location target) {
        if (viewer == null || target == null) {
            return false;
        }
        Location eye = viewer.getEyeLocation();
        Vector direction = target.clone().add(0, 1.0, 0).toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-4) {
            return true;
        }
        Vector facing = eye.getDirection();
        return facing.angle(direction) < Math.toRadians(40.0);
    }
}
