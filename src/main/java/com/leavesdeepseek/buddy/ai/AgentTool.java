package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * 一个可以被 AI 调用的工具。
 * <p>execute 会在主线程被调用；耗时操作应返回未完成的 future，而不是阻塞主线程。</p>
 */
public interface AgentTool {

    String name();

    String description();

    JsonObject parameters();

    CompletableFuture<String> execute(ToolContext context, JsonObject arguments);

    /**
     * 工具自身的超时（秒）；0 表示使用全局 deepseek.tool-timeout-seconds。
     */
    default int timeoutSeconds() {
        return 0;
    }
}
