package com.leavesdeepseek.buddy.ai;

/**
 * AI 后端选择结果。
 */
public enum AiBackend {

    /** DeepSeek Function Calling：可以操作假人 */
    DEEPSEEK("DeepSeek"),
    /** 已接入的 AstrBot：纯对话 */
    ASTRBOT("AstrBot"),
    /** 两个后端都可用，但玩家还没选择默认值 */
    NEED_CHOICE("需要选择"),
    /** 一个都没有配置 */
    NONE("未配置");

    private final String label;

    AiBackend(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
