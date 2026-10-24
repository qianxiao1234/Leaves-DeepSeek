package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonObject;

import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;

/**
 * AgentTool 的便捷工厂。
 */
public final class Tools {

    private Tools() {
    }

    public static AgentTool of(String name,
                               String description,
                               Schema schema,
                               BiFunction<ToolContext, JsonObject, CompletableFuture<String>> action) {
        JsonObject parameters = schema.build();
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject parameters() {
                return parameters.deepCopy();
            }

            @Override
            public CompletableFuture<String> execute(ToolContext context, JsonObject arguments) {
                return action.apply(context, arguments);
            }
        };
    }

    /**
     * 给工具设置独立超时（秒），用于网络类等耗时可能超过全局超时的工具。
     */
    public static AgentTool withTimeout(AgentTool tool, int timeoutSeconds) {
        int timeout = Math.max(1, timeoutSeconds);
        return new AgentTool() {
            @Override
            public String name() {
                return tool.name();
            }

            @Override
            public String description() {
                return tool.description();
            }

            @Override
            public JsonObject parameters() {
                return tool.parameters();
            }

            @Override
            public CompletableFuture<String> execute(ToolContext context, JsonObject arguments) {
                return tool.execute(context, arguments);
            }

            @Override
            public int timeoutSeconds() {
                return timeout;
            }
        };
    }
}
