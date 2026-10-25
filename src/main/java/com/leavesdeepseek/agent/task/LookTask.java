package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import com.leavesdeepseek.agent.JsonRpc;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.leavesmc.leaves.entity.bot.Bot;

/**
 * {@code act.look}：平滑转向（每 tick 最多 30°），支持 yaw/pitch、坐标、实体三种目标。
 */
public final class LookTask extends AbstractTask {

    private static final float MAX_STEP = 30.0f;

    private final Float yaw;
    private final Float pitch;
    private final VecUtil.Point point;
    private final Integer entityId;

    public LookTask(Float yaw, Float pitch, VecUtil.Point point, Integer entityId) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.point = point;
        this.entityId = entityId;
        this.deadline = startedAt + 5_000L;
    }

    @Override
    public String type() {
        return "look";
    }

    @Override
    public void start(AIBody body) {
        if (yaw == null && point == null && entityId == null) {
            fail(JsonRpc.BAD_REQUEST, "look 需要 yaw/pitch、坐标或 entityId");
        }
    }

    @Override
    public void tick(AIBody body) {
        if (done() || failed()) {
            return;
        }
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            fail(JsonRpc.BODY_OFFLINE, "躯壳已离线");
            return;
        }
        float targetYaw = bot.getLocation().getYaw();
        float targetPitch = bot.getLocation().getPitch();
        if (yaw != null) {
            targetYaw = yaw;
            targetPitch = pitch == null ? 0.0f : pitch;
        } else if (point != null) {
            Vector direction = new Vector(point.x() - bot.getLocation().getX(),
                    point.y() - (bot.getLocation().getY() + 1.62),
                    point.z() - bot.getLocation().getZ());
            if (direction.lengthSquared() < 1.0E-6) {
                finish();
                return;
            }
            Location temp = new Location(bot.getWorld(), 0, 0, 0).setDirection(direction);
            targetYaw = temp.getYaw();
            targetPitch = temp.getPitch();
        } else if (entityId != null) {
            Entity entity = VecUtil.entityById(bot, entityId);
            if (entity == null) {
                fail(JsonRpc.NOT_FOUND, "实体 " + entityId + " 不存在");
                return;
            }
            Vector direction = entity.getLocation().add(0, entity.getHeight() * 0.5, 0)
                    .toVector().subtract(bot.getEyeLocation().toVector());
            if (direction.lengthSquared() < 1.0E-6) {
                finish();
                return;
            }
            Location temp = new Location(bot.getWorld(), 0, 0, 0).setDirection(direction);
            targetYaw = temp.getYaw();
            targetPitch = temp.getPitch();
        }

        float currentYaw = bot.getLocation().getYaw();
        float currentPitch = bot.getLocation().getPitch();
        float nextYaw = step(currentYaw, targetYaw);
        float nextPitch = step(currentPitch, targetPitch);
        bot.setRotation(nextYaw, nextPitch);
        if (Math.abs(wrap(nextYaw - targetYaw)) < 1.0f && Math.abs(nextPitch - targetPitch) < 1.0f) {
            finish();
        }
    }

    private static float step(float current, float target) {
        float delta = wrap(target - current);
        if (Math.abs(delta) <= MAX_STEP) {
            return target;
        }
        return current + Math.signum(delta) * MAX_STEP;
    }

    private static float wrap(float angle) {
        float value = angle % 360.0f;
        if (value >= 180.0f) {
            value -= 360.0f;
        }
        if (value < -180.0f) {
            value += 360.0f;
        }
        return value;
    }

    @Override
    public void cancel() {
        fail(JsonRpc.TIMEOUT, "CANCELLED");
    }
}
