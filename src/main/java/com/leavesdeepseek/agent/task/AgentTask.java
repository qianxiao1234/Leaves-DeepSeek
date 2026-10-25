package com.leavesdeepseek.agent.task;

import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.AIBody;
import org.bukkit.Location;

/**
 * 躯壳任务（规格 §7.3 / §3）。
 *
 * <p>任务只描述「意图」，具体每一步由 {@code BodyController} 每 tick 推进。</p>
 */
public interface AgentTask {

    /** 任务 id（由控制器分配）。 */
    String id();

    void id(String id);

    /** 任务类型，用于 task.* 事件的 {@code type} 字段。 */
    String type();

    /** 开始执行（在主线程调用一次）。 */
    void start(AIBody body);

    /** 每 tick 推进（20 Hz，主线程）。 */
    void tick(AIBody body);

    boolean done();

    boolean failed();

    /** 失败的错误码（{@link com.leavesdeepseek.agent.JsonRpc} 常量）与简短说明。 */
    int failCode();

    String failDetail();

    /** 0.0 ~ 1.0 */
    double progress();

    /** 当前目标位置（用于 K 帧与错误 data）。 */
    Location target();

    /** 卡住计数（内环维护）。 */
    int stuck();

    void stuck(int value);

    /** 预计剩余毫秒（0 表示未知）。 */
    long etaMs();

    /** 被外部取消/换任务打断。 */
    void cancel();

    /** 由控制器（或任务自己）强制置为失败态。 */
    void fail(int code, String message);

    /** 任务已运行多久（毫秒），用于 {@code act.queue} 的 elapsedMs。 */
    long elapsedMs();

    /** 失败时附带进 error.data 的额外字段。 */
    default JsonObject extraData() {
        return null;
    }
}
