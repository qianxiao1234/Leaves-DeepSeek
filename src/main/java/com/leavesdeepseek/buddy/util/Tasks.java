package com.leavesdeepseek.buddy.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 主线程调度辅助方法。
 */
public final class Tasks {

    private Tasks() {
    }

    /**
     * 在主线程执行 supplier，并把结果作为 future 返回。
     */
    public static <T> CompletableFuture<T> onMain(Plugin plugin, java.util.function.Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    /**
     * 轮询条件，直到满足或超时。必须在主线程调用。
     */
    public static CompletableFuture<Boolean> poll(Plugin plugin, int maxTicks, int intervalTicks, BooleanSupplier condition) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (maxTicks <= 0) {
            future.complete(false);
            return future;
        }
        int interval = Math.max(1, intervalTicks);
        AtomicReference<BukkitTask> taskRef = new AtomicReference<>();
        int[] elapsed = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean done;
            try {
                done = condition.getAsBoolean();
            } catch (Throwable throwable) {
                done = false;
            }
            elapsed[0] += interval;
            if (done || elapsed[0] >= maxTicks || !plugin.isEnabled()) {
                BukkitTask taskNow = taskRef.get();
                if (taskNow != null) {
                    taskNow.cancel();
                }
                future.complete(done);
            }
        }, interval, interval);
        taskRef.set(task);
        return future;
    }

    /**
     * 延迟指定 tick 后完成。必须在主线程调用。
     */
    public static CompletableFuture<Void> delay(Plugin plugin, int ticks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        int delay = Math.max(1, ticks);
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> future.complete(null), delay);
        } catch (Throwable throwable) {
            future.completeExceptionally(throwable);
        }
        return future;
    }

    public static void cancel(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件关闭时忽略
            }
        }
    }
}
