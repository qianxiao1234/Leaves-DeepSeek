package com.leavesdeepseek.buddy.ai;

import org.bukkit.entity.Player;
import org.leavesmc.leaves.entity.bot.Bot;

/**
 * 工具执行上下文。
 *
 * @param bot         执行动作的假人
 * @param owner       主人（可能已离线，使用前需判空）
 * @param requestId   本次请求的短 ID，便于日志排查
 * @param userMessage 本次触发请求的原始玩家消息（用于判断主人是否已同意某些操作）
 */
public record ToolContext(Bot bot, Player owner, String requestId, String userMessage) {
}
