package com.leavesdeepseek.agent;

import org.java_websocket.WebSocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条外部连接的状态：是否鉴权、attach 了哪些躯壳、限速计数。
 *
 * <p>只在 WS 线程里读写，不需要额外加锁（限速计数用原子量，主线程只读）。</p>
 */
public final class AgentSession {

    private final WebSocket connection;
    private final AtomicLong lastSeen = new AtomicLong(System.currentTimeMillis());
    private final Set<String> bodies = ConcurrentHashMap.newKeySet();

    private volatile boolean authenticated;
    private volatile long connectAt = System.currentTimeMillis();

    private int actionWindowSecond = -1;
    private int actionCount;
    private int senseWindowSecond = -1;
    private int senseCount;
    private long lastChatAt;

    public AgentSession(WebSocket connection) {
        this.connection = connection;
    }

    public WebSocket connection() {
        return connection;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void authenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public long connectAt() {
        return connectAt;
    }

    public void touch() {
        lastSeen.set(System.currentTimeMillis());
    }

    public long lastSeen() {
        return lastSeen.get();
    }

    public Set<String> bodies() {
        return bodies;
    }

    public boolean canAttach(int limit) {
        return bodies.size() < limit;
    }

    /**
     * 动作类限速。
     *
     * @return true 表示放行
     */
    public boolean allowAction(int perSecond) {
        return allow(perSecond, true);
    }

    /**
     * 感知类限速。
     *
     * @return true 表示放行
     */
    public boolean allowSense(int perSecond) {
        return allow(perSecond, false);
    }

    private boolean allow(int perSecond, boolean action) {
        int second = (int) (System.currentTimeMillis() / 1000L);
        if (action) {
            if (second != actionWindowSecond) {
                actionWindowSecond = second;
                actionCount = 0;
            }
            return ++actionCount <= perSecond;
        }
        if (second != senseWindowSecond) {
            senseWindowSecond = second;
            senseCount = 0;
        }
        return ++senseCount <= perSecond;
    }

    public boolean allowChat(int cooldownMs) {
        long now = System.currentTimeMillis();
        if (now - lastChatAt < cooldownMs) {
            return false;
        }
        lastChatAt = now;
        return true;
    }

    public String remoteAddress() {
        try {
            return connection == null || connection.getRemoteSocketAddress() == null
                    ? "?" : connection.getRemoteSocketAddress().toString();
        } catch (RuntimeException exception) {
            return "?";
        }
    }
}
