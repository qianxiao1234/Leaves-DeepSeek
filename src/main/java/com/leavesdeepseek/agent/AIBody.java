package com.leavesdeepseek.agent;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.Map;
import java.util.UUID;

/**
 * 一具「躯壳」：真实体 + 控制器 + 感知 + 三态。
 *
 * <p>躯壳不绑定主人、不注册任何自主行为服务；所有动作只来自外部 AI 的指令或内环（{@link BodyController}）。</p>
 */
public final class AIBody {

    public enum State {
        /** 正常在线，可接受动作 */
        REMOTE,
        /** 旁观态：不受伤、不能动作 */
        PAUSED,
        /** 已下线：实体不在世界，只剩存档 */
        OFFLINE
    }

    private final BodyManager manager;
    private final String name;
    private final UUID uuid;
    private final long createdAt;
    private final BodyController controller;
    private final SensorHub sensors;
    private final SpectatorGuard guard;

    private volatile Bot bot;
    private volatile State state = State.PAUSED;
    private volatile Location anchor;
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile long pausedSince = System.currentTimeMillis();
    private volatile AgentSession session;
    private volatile boolean dead;
    /** 旁观没切成功（游戏模式还是生存态）：给客户端一个可见的降级标记。 */
    private volatile boolean degraded;
    /** 归属指纹（token 的 sha256 前 16 位）；空 = 老躯壳/命令行创建。 */
    private volatile String ownerKey = "";

    public AIBody(BodyManager manager, String name, UUID uuid, long createdAt) {
        this.manager = manager;
        this.name = name;
        this.uuid = uuid;
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.controller = new BodyController(this);
        this.sensors = new SensorHub(this);
        this.guard = new SpectatorGuard(this);
    }

    public BodyManager manager() {
        return manager;
    }

    private final Map<String, String> overrides = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code /ai body config} 写入的单躯壳覆盖项。 */
    public void override(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, value);
        }
    }

    public Map<String, String> overrides() {
        return java.util.Collections.unmodifiableMap(overrides);
    }

    public boolean configBoolean(String key, boolean fallback) {
        String value = overrides.get(key);
        if (value == null) {
            return fallback;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equals("1");
    }

    public int configInt(String key, int fallback) {
        String value = overrides.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    public long createdAt() {
        return createdAt;
    }

    public Bot bot() {
        return bot;
    }

    public void bot(Bot bot) {
        this.bot = bot;
    }

    public boolean online() {
        Bot current = bot;
        return current != null && current.isValid();
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
        this.pausedSince = System.currentTimeMillis();
    }

    public Location anchor() {
        return anchor;
    }

    public void anchor(Location anchor) {
        this.anchor = anchor;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void touch() {
        lastSeen = System.currentTimeMillis();
    }

    public long pausedSince() {
        return pausedSince;
    }

    public AgentSession session() {
        return session;
    }

    public void session(AgentSession session) {
        this.session = session;
    }

    public boolean dead() {
        return dead;
    }

    public void dead(boolean dead) {
        this.dead = dead;
    }

    /** 归属指纹（空 = 老躯壳，谁都不能通过 RPC 删）。 */
    public String ownerKey() {
        return ownerKey;
    }

    /** 旁观模式没切成功（身体还是生存态）—— 给客户端与 /ai body info 一个可见标记。 */
    public boolean degraded() {
        return degraded;
    }

    public void degraded(boolean degraded) {
        this.degraded = degraded;
    }

    public void ownerKey(String ownerKey) {
        this.ownerKey = ownerKey == null ? "" : ownerKey;
    }

    public BodyController controller() {
        return controller;
    }

    public SensorHub sensors() {
        return sensors;
    }

    public SpectatorGuard guard() {
        return guard;
    }

    /** 供感知/动作使用的当前位置（离线时返回 null）。 */
    public Location location() {
        Bot current = bot;
        return current == null || !current.isValid() ? null : current.getLocation();
    }

    public World world() {
        Location location = location();
        return location == null ? null : location.getWorld();
    }

    public String dimension() {
        World world = world();
        return world == null ? "" : world.getName();
    }

    public boolean spectator() {
        Bot current = bot;
        return current != null && current.getGameMode() == GameMode.SPECTATOR;
    }
}
