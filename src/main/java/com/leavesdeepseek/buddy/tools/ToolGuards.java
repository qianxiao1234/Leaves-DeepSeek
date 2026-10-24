package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.config.PluginSettings;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 工具共用的安全与格式化辅助。
 */
public final class ToolGuards {

    private ToolGuards() {
    }

    /**
     * 检查目标是否在主人附近。返回 null 表示允许。
     */
    public static String checkRange(PluginSettings settings, Player owner, Location target) {
        if (owner == null || !owner.isOnline() || target == null) {
            return null;
        }
        World ownerWorld = owner.getWorld();
        World targetWorld = target.getWorld();
        if (ownerWorld == null || targetWorld == null) {
            return null;
        }
        if (!ownerWorld.equals(targetWorld)) {
            return "目标在另一个维度（" + targetWorld.getName() + "），我现在过不去。";
        }
        double distance = target.distance(owner.getLocation());
        if (distance > settings.operateRadius()) {
            return "目标距离主人 " + Math.round(distance) + " 格，超出了我能活动的范围（"
                    + settings.operateRadius() + " 格）。";
        }
        return null;
    }

    public static String fmt(Location location) {
        if (location == null) {
            return "?";
        }
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    public static boolean sameWorld(Location first, Location second) {
        return first != null && second != null
                && first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }
}
