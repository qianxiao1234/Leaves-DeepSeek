package com.leavesdeepseek.agent;

import org.bukkit.GameMode;
import org.bukkit.metadata.FixedMetadataValue;
import org.leavesmc.leaves.entity.bot.Bot;

/**
 * 心跳三态机（规格 §9）：{@code remote → 90s 无请求 → paused(旁观) → 再 300s → offline}。
 *
 * <p>切旁观**只能**由服务端判断触发，AI 不能主动要求。</p>
 */
public final class SpectatorGuard {

    private final AIBody body;

    public SpectatorGuard(AIBody body) {
        this.body = body;
    }

    public void tick() {
        if (body.state() == AIBody.State.OFFLINE) {
            return;
        }
        AgentConfig config = body.manager().config();
        long idle = System.currentTimeMillis() - body.lastSeen();
        long toPaused = config.idleToPausedSeconds() * 1000L;
        long toOffline = toPaused + config.pausedToOfflineSeconds() * 1000L;
        if (body.state() == AIBody.State.REMOTE && idle >= toPaused) {
            pause("idle-" + config.idleToPausedSeconds() + "s");
            return;
        }
        if (body.state() == AIBody.State.PAUSED && idle >= toOffline) {
            body.manager().offline(body, "idle-" + (toPaused / 1000 + config.pausedToOfflineSeconds()) + "s");
        }
    }

    /** 进入旁观态（不受伤、不能动作、任务全部取消）。 */
    public void pause(String reason) {
        Bot bot = body.bot();
        body.controller().stopAll(JsonRpc.nameOf(JsonRpc.SPECTATOR));
        if (bot != null && bot.isValid()) {
            try {
                bot.setInvulnerable(false);
                bot.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable throwable) {
                body.manager().plugin().getLogger().warning("切旁观失败：" + throwable.getMessage());
            }
        }
        body.state(AIBody.State.PAUSED);
        body.sensors().stateEvent(AIBody.State.PAUSED, reason);
        body.manager().auditEvent(body.name(), "ev.state", JsonRpc.object("state", "paused", "reason", reason));
    }

    /** 回到可操作状态（旁观 → 生存）。 */
    public void resume(String reason) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return;
        }
        try {
            bot.setGameMode(GameMode.SURVIVAL);
            // AuthMe 靠这个 metadata 判断 NPC，切回来必须重挂
            bot.setMetadata("NPC", new FixedMetadataValue(body.manager().plugin(), true));
            bot.setInvulnerable(false);
            bot.setFireTicks(0);
            bot.setFallDistance(0.0f);
        } catch (Throwable throwable) {
            body.manager().plugin().getLogger().warning("恢复躯壳失败：" + throwable.getMessage());
        }
        body.state(AIBody.State.REMOTE);
        body.sensors().stateEvent(AIBody.State.REMOTE, reason);
    }
}
