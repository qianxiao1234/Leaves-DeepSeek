package com.leavesdeepseek.buddy.ai;

/**
 * 一次对话实际要用的 AI 配置（解析结果）。
 *
 * <p>这里把「偏好、可用性、Key 来源、验证状态」一次性算清，
 * 上层（AgentService / BuddyCommand）只看这个结果，不再各自判断，
 * 避免出现「明明有 Key 却提示未配置」这类分叉。</p>
 *
 * @param backend    最终后端：DEEPSEEK / ASTRBOT / NEED_CHOICE / NONE
 * @param api        使用 DeepSeek 时的 API 配置，其他情况为 null
 * @param astrBotConfig 使用 AstrBot 时的配置，其他情况为 null
 * @param keySource  DeepSeek Key 的来源（玩家自己的 / 服务器全局的）
 * @param preference 生效的偏好值（deepseek / astrbot / auto）
 * @param validated  DeepSeek 配置是否已通过验证（AstrBot 恒为 true）
 * @param reason     NEED_CHOICE / NONE 时给玩家看的中文原因
 */
public record ResolvedAi(AiBackend backend,
                         ApiConfig api,
                         AstrBotConfig astrBotConfig,
                         KeySource keySource,
                         String preference,
                         boolean validated,
                         String reason) {

    /** DeepSeek Key 的来源。 */
    public enum KeySource {
        /** 玩家用 /buddy set 配置的私有 Key */
        OWN("个人 Key"),
        /** 服主写在 config.yml 里的全局 Key */
        GLOBAL("服务器全局 Key"),
        /** 走 AstrBot，不使用 DeepSeek Key */
        ASTRBOT("AstrBot"),
        /** 一个都没有 */
        NONE("未配置");

        private final String label;

        KeySource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 是否能真正发起这次对话。 */
    public boolean usable() {
        return backend == AiBackend.DEEPSEEK || backend == AiBackend.ASTRBOT;
    }

    public boolean needsChoice() {
        return backend == AiBackend.NEED_CHOICE;
    }

    public boolean deepSeek() {
        return backend == AiBackend.DEEPSEEK && api != null && api.usable();
    }

    /** 这次对话是否走 AstrBot（纯对话，不能操作假人）。 */
    public boolean astrBot() {
        return backend == AiBackend.ASTRBOT && astrBotConfig != null && astrBotConfig.usable();
    }

    /** 使用全局 Key 时不需要玩家自己验证（服主已配置即视为可信）。 */
    public static ResolvedAi none(String reason) {
        return new ResolvedAi(AiBackend.NONE, null, null, KeySource.NONE, "auto", false,
                reason == null ? "" : reason);
    }

    public static ResolvedAi needChoice(String preference) {
        return new ResolvedAi(AiBackend.NEED_CHOICE, null, null, KeySource.NONE,
                preference == null ? "auto" : preference, false, "");
    }
}
