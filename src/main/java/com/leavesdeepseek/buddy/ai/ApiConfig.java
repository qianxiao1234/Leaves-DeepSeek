package com.leavesdeepseek.buddy.ai;

/**
 * 一次请求使用的 API 配置（玩家私有或全局回退）。
 */
public record ApiConfig(String apiKey, String model, String baseUrl) {

    public boolean usable() {
        return apiKey != null && !apiKey.isBlank()
                && model != null && !model.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    public String maskedKey() {
        if (apiKey == null || apiKey.length() < 8) {
            return "已配置";
        }
        return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
    }
}
