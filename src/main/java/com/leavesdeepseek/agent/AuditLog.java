package com.leavesdeepseek.agent;

import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 动作审计：{@code bodies/audit-YYYY-MM-DD.jsonl}，每行一条。
 *
 * <p>写入是异步的（不阻塞主线程），失败只打一条告警，绝不抛出。</p>
 */
public final class AuditLog {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JavaPlugin plugin;
    private final Path directory;
    private final Object lock = new Object();

    private BufferedWriter writer;
    private String openedDay;

    public AuditLog(JavaPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "bodies").toPath();
    }

    public void write(String body, String method, JsonObject params, String result, long elapsedMs) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", System.currentTimeMillis());
        line.addProperty("body", body);
        line.addProperty("method", method);
        if (params != null) {
            line.add("params", params);
        }
        line.addProperty("result", result);
        line.addProperty("ms", elapsedMs);
        writeLine(line.toString());
    }

    public void writeEvent(String body, String event, JsonObject params) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", System.currentTimeMillis());
        line.addProperty("body", body);
        line.addProperty("event", event);
        if (params != null) {
            line.add("params", params);
        }
        writeLine(line.toString());
    }

    private void writeLine(String json) {
        synchronized (lock) {
            try {
                String today = LocalDate.now().format(DATE);
                if (writer == null || !today.equals(openedDay)) {
                    closeQuietly();
                    Files.createDirectories(directory);
                    Path file = directory.resolve("audit-" + today + ".jsonl");
                    writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    openedDay = today;
                }
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                plugin.getLogger().warning("写审计日志失败：" + exception.getMessage());
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓
        }
        writer = null;
        openedDay = null;
    }
}
