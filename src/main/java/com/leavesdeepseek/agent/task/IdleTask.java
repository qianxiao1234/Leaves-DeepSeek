package com.leavesdeepseek.agent.task;

import com.leavesdeepseek.agent.AIBody;
import org.bukkit.Location;

/** 空任务：让躯壳什么都不做（外部 AI 用来「原地待命」）。 */
public final class IdleTask extends AbstractTask {

    @Override
    public String type() {
        return "idle";
    }

    @Override
    public void start(AIBody body) {
        // 纯等待，不做任何事
    }

    @Override
    public void tick(AIBody body) {
        // 由外部用 act.cancel 结束
    }

    @Override
    public double progress() {
        return 0.0;
    }

    @Override
    public Location target() {
        return null;
    }

    @Override
    public long etaMs() {
        return 0L;
    }

    @Override
    public void cancel() {
        fail(com.leavesdeepseek.agent.JsonRpc.TIMEOUT, "CANCELLED");
    }
}
