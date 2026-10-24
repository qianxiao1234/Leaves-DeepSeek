package com.leavesdeepseek.buddy.ai;

/**
 * DeepSeek 调用失败。
 */
public class DeepSeekException extends RuntimeException {

    public DeepSeekException(String message) {
        super(message);
    }

    public DeepSeekException(String message, Throwable cause) {
        super(message, cause);
    }
}
