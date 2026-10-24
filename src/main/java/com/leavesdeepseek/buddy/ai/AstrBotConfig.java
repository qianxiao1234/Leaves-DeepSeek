package com.leavesdeepseek.buddy.ai;

/**
 * 一次 AstrBot 调用实际使用的配置（玩家自己的，或服务器全局默认）。
 */
public record AstrBotConfig(boolean enabled,
                            String baseUrl,
                            String key,
                            String sessionMode,
                            String fixedSession,
                            int timeoutSeconds,
                            int maxReplyChars) {

    public boolean usable() {
        return enabled
                && baseUrl != null && !baseUrl.isBlank()
                && key != null && !key.isBlank();
    }

    public String maskedKey() {
        if (key == null || key.isBlank()) {
            return "未配置";
        }
        String value = key.trim();
        if (value.length() <= 10) {
            return "****";
        }
        return value.substring(0, 6) + "****" + value.substring(value.length() - 4);
    }

    public String mode() {
        return sessionMode == null || sessionMode.isBlank() ? "per-player" : sessionMode;
    }
}
