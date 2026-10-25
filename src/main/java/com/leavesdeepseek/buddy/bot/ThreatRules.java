package com.leavesdeepseek.buddy.bot;

import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.Set;

/**
 * 战斗策略常量与生物分级。
 */
public final class ThreatRules {

    /** 两次攻击之间的冷却（tick），12 tick = 0.6 秒 */
    public static final int ATTACK_COOLDOWN_TICKS = 12;
    /** 停止靠近的距离 */
    public static final double ENGAGE_DISTANCE = 3.0;
    /** 血量低于该比例时撤退吃金苹果 */
    public static final double HEALTH_RETREAT_RATIO = 0.30;
    /** 血量低于该比例时边打边吃 */
    public static final double HEALTH_EAT_RATIO = 0.60;
    /** 战斗前饥饿值低于该值先吃 */
    public static final int HUNGER_EAT_LEVEL = 6;
    /** 被这么多只敌对生物围攻时撤退（当前策略：不按数量撤，只看血量，保留常量备用） */
    public static final int GROUP_RETREAT_COUNT = 4;
    /** 统计围攻数量的半径 */
    public static final double GROUP_RADIUS = 8.0;
    /** 苦力怕危险距离：进入后立刻后退 */
    public static final double CREEPER_DANGER_DISTANCE = 5.0;
    /** 苦力怕后退到的安全距离 */
    public static final double CREEPER_SAFE_DISTANCE = 6.5;
    /** 发现苦力怕就主动躲开的半径（苦力怕会爆炸，不交战） */
    public static final double CREEPER_DETECT_RADIUS = 10.0;
    /** 躲苦力怕时要跑开的距离 */
    public static final double CREEPER_FLEE_DISTANCE = 16.0;

    /** 不主动攻击（会引发群体仇恨 / 中立生物） */
    private static final Set<EntityType> PASSIVE = EnumSet.of(
            EntityType.ENDERMAN,
            EntityType.ZOMBIFIED_PIGLIN,
            EntityType.WOLF,
            EntityType.IRON_GOLEM,
            EntityType.PIGLIN);

    /** 坚守者：不交战，潜行离开 */
    public static final Set<EntityType> SNEAK_AVOID = EnumSet.of(EntityType.WARDEN);

    private ThreatRules() {
    }

    public static boolean isPassive(EntityType type) {
        return type != null && PASSIVE.contains(type);
    }

    public static boolean isSneakAvoid(EntityType type) {
        return type != null && SNEAK_AVOID.contains(type);
    }

    public static boolean isCreeper(EntityType type) {
        return type == EntityType.CREEPER;
    }

    /**
     * 远程 / 会击退的生物：打它们要用「跑打」（冲上去砍一刀、立刻退半步再冲），
     * 站着不动会被放风筝、还会被箭的击退顶退。
     */
    public static boolean isRanged(EntityType type) {
        return type == EntityType.SKELETON
                || type == EntityType.STRAY
                || type == EntityType.BOGGED
                || type == EntityType.PILLAGER
                || type == EntityType.BLAZE
                || type == EntityType.WITCH;
    }
}
