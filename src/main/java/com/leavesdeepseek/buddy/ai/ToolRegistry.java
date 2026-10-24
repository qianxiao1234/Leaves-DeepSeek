package com.leavesdeepseek.buddy.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工具注册表，同时负责工具的主线程调度与超时保护。
 */
public final class ToolRegistry {

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final JsonArray schemas = new JsonArray();

    public ToolRegistry(JavaPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void register(AgentTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            plugin.getLogger().warning("工具重复注册，已忽略: " + tool.name());
            return;
        }
        JsonObject function = new JsonObject();
        function.addProperty("name", tool.name());
        function.addProperty("description", tool.description());
        function.add("parameters", tool.parameters());

        JsonObject entry = new JsonObject();
        entry.addProperty("type", "function");
        entry.add("function", function);
        schemas.add(entry);
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    public JsonArray schemas() {
        return schemas.deepCopy();
    }

    /**
     * 在主线程执行工具，并给工具返回的 future 加超时。
     */
    public CompletableFuture<String> execute(AgentTool tool, ToolContext context, JsonObject arguments) {
        CompletableFuture<CompletableFuture<String>> outer = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!context.bot().isValid()) {
                        outer.complete(CompletableFuture.completedFuture(Json.error("假人已经不存在了")));
                        return;
                    }
                    outer.complete(tool.execute(context, arguments));
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("工具 " + tool.name() + " 执行异常: " + Json.rootMessage(throwable));
                    outer.complete(CompletableFuture.completedFuture(Json.error("工具执行失败：" + Json.rootMessage(throwable))));
                }
            });
        } catch (Throwable throwable) {
            outer.complete(CompletableFuture.completedFuture(Json.error("无法调度工具：" + Json.rootMessage(throwable))));
        }

        return outer.thenCompose(future -> future)
                .orTimeout(tool.timeoutSeconds() > 0 ? tool.timeoutSeconds() : settings.toolTimeoutSeconds(),
                        TimeUnit.SECONDS)
                .exceptionally(throwable -> Json.error("操作超时或出错：" + Json.rootMessage(throwable)));
    }
}
