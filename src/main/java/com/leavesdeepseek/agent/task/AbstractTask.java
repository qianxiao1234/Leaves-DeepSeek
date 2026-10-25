package com.leavesdeepseek.agent.task;

import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.JsonRpc;
import org.bukkit.Location;

/**
 * 任务基类：统一管理 id / 状态 / 起止时间 / 卡住计数。
 */
public abstract class AbstractTask implements AgentTask {

    protected final long startedAt = System.currentTimeMillis();
    protected long deadline = startedAt + 30_000L;

    private String id = "t-0";
    private boolean done;
    private boolean failed;
    private int failCode = JsonRpc.INTERNAL;
    private String failDetail = "";
    private int stuck;

    @Override
    public String id() {
        return id;
    }

    @Override
    public void id(String id) {
        this.id = id;
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public boolean failed() {
        return failed;
    }

    protected void finish() {
        this.done = true;
    }

    /** 由控制器强制判失败（规格没定义，但内环 catch 到异常时必须能置失败）。 */
    @Override
    public void fail(int code, String detail) {
        if (done || failed) {
            return;
        }
        this.failed = true;
        this.failCode = code;
        this.failDetail = detail == null ? "" : detail;
    }

    @Override
    public long elapsedMs() {
        return System.currentTimeMillis() - startedAt;
    }

    @Override
    public int failCode() {
        return failCode;
    }

    @Override
    public String failDetail() {
        return failDetail;
    }

    @Override
    public double progress() {
        if (done) {
            return 1.0;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        long total = Math.max(1L, deadline - startedAt);
        return Math.max(0.0, Math.min(0.99, (double) elapsed / (double) total));
    }

    @Override
    public Location target() {
        return null;
    }

    @Override
    public int stuck() {
        return stuck;
    }

    @Override
    public void stuck(int value) {
        this.stuck = value;
    }

    /** 估计剩余毫秒；-1 表示「算不出来」（不要给假数字）。 */
    @Override
    public long etaMs() {
        if (done || failed) {
            return 0L;
        }
        return -1L;
    }

    @Override
    public void cancel() {
        fail(JsonRpc.INTERNAL, "CANCELLED");
    }

    @Override
    public JsonObject extraData() {
        return null;
    }

    /** 超时判定（默认 30 秒，子类可改）。 */
    protected boolean timeout() {
        return System.currentTimeMillis() > deadline;
    }
}
