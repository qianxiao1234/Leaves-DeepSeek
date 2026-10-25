package com.leavesdeepseek.agent;

import org.bukkit.GameMode;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;
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
        // idle-to-paused-seconds: 0 = 永不因闲置进旁观（连续保活做不到时也不会被塞进旁观）
        if (config.idleToPausedSeconds() <= 0) {
            return;
        }
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

    /**
     * 进入旁观态：取消任务、清速度、切旁观，并**校验**是否真的切成功。
     *
     * <p>施工清单 §3.1：之前 {@code setGameMode} 失败只打一条 warning，躯壳会留在生存态
     * 泡在水里被水流推着漂（y 不变、水平 0.48 格/秒）。现在失败会重试并写 ERROR；
     * 切成功后 {@code invulnerable} 保持 true 作双保险，{@code resume()} 时再关。</p>
     */
    public void pause(String reason) {
        Bot bot = body.bot();
        body.controller().stopAll(JsonRpc.nameOf(JsonRpc.SPECTATOR));
        if (bot != null && bot.isValid()) {
            try {
                // 先停住：切模式那一刻手里若握着速度向量会带着漂
                bot.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                bot.setFallDistance(0.0f);
                bot.setSprinting(false);
                bot.setSneaking(false);
                bot.setFireTicks(0);
                bot.setInvulnerable(true);
                boolean switched = switchToSpectator(bot);
                GameMode actual = bot.getGameMode();
                if (switched && actual == GameMode.SPECTATOR) {
                    body.degraded(false);
                    body.manager().plugin().log().info("躯壳",
                            "[GUARD] paused ok | " + body.name() + " gamemode=" + actual
                                    + " reason=" + reason);
                } else {
                    body.degraded(true);
                    body.manager().plugin().log().error("躯壳",
                            "[GUARD] 切旁观失败！" + body.name() + " gamemode=" + actual
                                    + "（躯壳仍在世界内活动，可能被水流推动）", null);
                    body.sensors().event("ev.state", JsonRpc.object(
                            "state", "paused", "reason", "paused-degraded",
                            "gamemode", actual == null ? "" : actual.name(),
                            "degraded", true));
                }
                if (bot.isInWater()) {
                    body.manager().plugin().log().warn("躯壳",
                            "[GUARD] " + body.name() + " 进旁观时泡在水里（施工清单 §3.1 怀疑点）");
                }
            } catch (Throwable throwable) {
                body.manager().plugin().log().error("躯壳",
                        "切旁观异常：" + throwable.getMessage(), throwable);
            }
        }
        body.state(AIBody.State.PAUSED);
        body.sensors().stateEvent(AIBody.State.PAUSED, reason);
        body.manager().auditEvent(body.name(), "ev.state", JsonRpc.object("state", "paused", "reason", reason));
    }

    /** 切旁观，失败重试一次。 */
    private boolean switchToSpectator(Bot bot) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                bot.setGameMode(GameMode.SPECTATOR);
            } catch (Throwable throwable) {
                body.manager().plugin().log().warn("躯壳",
                        "[GUARD] setGameMode 第 " + attempt + " 次失败：" + throwable.getMessage());
            }
            if (bot.getGameMode() == GameMode.SPECTATOR) {
                return true;
            }
        }
        return false;
    }

    /** 回到可操作状态（旁观 → 生存）。 */
    public void resume(String reason) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return;
        }
        try {
            bot.setGameMode(GameMode.SURVIVAL);
            GameMode actual = bot.getGameMode();
            if (actual != GameMode.SURVIVAL) {
                body.manager().plugin().log().warn("躯壳",
                        "[GUARD] resume 后 gamemode=" + actual + "（期望 SURVIVAL）| " + body.name());
            }
            // AuthMe 靠这个 metadata 判断 NPC，切回来必须重挂
            bot.setMetadata("NPC", new FixedMetadataValue(body.manager().plugin(), true));
            bot.setInvulnerable(false);
            bot.setFireTicks(0);
            bot.setFallDistance(0.0f);
            bot.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        } catch (Throwable throwable) {
            body.manager().plugin().getLogger().warning("恢复躯壳失败：" + throwable.getMessage());
        }
        body.state(AIBody.State.REMOTE);
        body.sensors().stateEvent(AIBody.State.REMOTE, reason);
    }
}
