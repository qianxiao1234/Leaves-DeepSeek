package com.leavesdeepseek.buddy.ai;

/**
 * DeepSeek 配置的默认值。
 * <p>「清空配置」和「配置文件里没有这一项」都必须落回这里，避免出现
 * 空 model / 空 base-url 导致请求地址异常。</p>
 */
public final class AiDefaults {

    /** 默认 API Key：空，表示还没有配置。 */
    public static final String API_KEY = "";

    /** 默认接口地址。 */
    public static final String BASE_URL = "https://api.deepseek.com";

    /** 默认模型（支持 Function Calling）。 */
    public static final String MODEL = "deepseek-chat";

    private AiDefaults() {
    }

    /**
     * 空值 / {@code default} 都视为「使用默认模型」。
     */
    public static String modelOrDefault(String value) {
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value.trim())) {
            return MODEL;
        }
        return value.trim();
    }

    /**
     * 空值 / {@code default} 都视为「使用默认地址」，并去掉结尾的斜杠。
     */
    public static String baseUrlOrDefault(String value) {
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value.trim())) {
            return BASE_URL;
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? BASE_URL : result;
    }
}
