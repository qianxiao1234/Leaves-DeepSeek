package com.leavesdeepseek.buddy.ai;

/**
 * 服务端明确拒绝 Function Calling 参数（tools / tool_choice）时抛出。
 * <p>只有这种情况才值得去掉 tools 重试；普通的 400（例如上游连接被 terminated）
 * 不能误判成「模型不支持工具」，否则会平白多打一次注定失败的请求。</p>
 */
public final class ToolUnsupportedException extends DeepSeekException {

    public ToolUnsupportedException(String message) {
        super(message);
    }
}
