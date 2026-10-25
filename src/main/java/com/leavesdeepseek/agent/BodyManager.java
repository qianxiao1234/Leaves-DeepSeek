package com.leavesdeepseek.agent;

import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.BotCreator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 躯壳注册表与主线程调度（规格 §2 / §4）。
 */
public final class BodyManager {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final LeavesDeepSeekPlugin plugin;
    private final AgentConfig config;
    private final BodyStore store;
    private final AuditLog audit;
    private final Map<String, AIBody> bodies = new LinkedHashMap<>();

    private AgentServer server;
    private int ticks;
    private int saveCounter;

    public BodyManager(LeavesDeepSeekPlugin plugin, AgentConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.store = new BodyStore(plugin);
        this.audit = new AuditLog(plugin);
    }

    public LeavesDeepSeekPlugin plugin() {
        return plugin;
    }

    public AgentConfig config() {
        return config;
    }

    public BodyStore store() {
        return store;
    }

    public AgentServer server() {
        return server;
    }

    public void server(AgentServer server) {
        this.server = server;
    }

    public int currentTick() {
        return ticks;
    }

    public Collection<AIBody> bodies() {
        return new ArrayList<>(bodies.values());
    }

    public AIBody byName(String name) {
        return name == null ? null : bodies.get(name);
    }

    public boolean isBody(Bot bot) {
        if (bot == null) {
            return false;
        }
        for (AIBody body : bodies.values()) {
            Bot current = body.bot();
            if (current != null && current.getUniqueId().equals(bot.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public AIBody byBot(Bot bot) {
        if (bot == null) {
            return null;
        }
        for (AIBody body : bodies.values()) {
            Bot current = body.bot();
            if (current != null && current.getUniqueId().equals(bot.getUniqueId())) {
                return body;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 造一具躯壳。
     */
    public AIBody create(String name, Location location) {
        String clean = name == null ? "" : name.trim();
        if (!NAME.matcher(clean).matches()) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST,
                    "名字必须是 3~16 个字母/数字/下划线");
        }
        if (bodies.containsKey(clean) || plugin.bots().byName(clean) != null
                || Bukkit.getPlayerExact(clean) != null) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "名字已被占用：" + clean);
        }
        if (bodies.size() >= config.maxBodies()) {
            throw new JsonRpc.RpcException(JsonRpc.BUSY, "躯壳数量已达上限 " + config.maxBodies());
        }
        Location spawn = location;
        if (spawn == null || spawn.getWorld() == null) {
            World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null) {
                throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "没有可用世界");
            }
            spawn = world.getSpawnLocation();
        }
        Bot bot = spawnEntity(clean, spawn);
        if (bot == null) {
            throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "服务端创建假人失败（检查 fakeplayer 配置）");
        }
        AIBody body = new AIBody(this, clean, bot.getUniqueId(), System.currentTimeMillis());
        body.bot(bot);
        body.state(AIBody.State.PAUSED);
        bodies.put(clean, body);
        if (store.exists(clean)) {
            YamlConfiguration yaml = store.read(clean);
            store.apply(bot, yaml);
        }
        store.save(clean, bot, body.anchor(), body.createdAt(), System.currentTimeMillis());
        auditEvent(clean, "body.create", JsonRpc.object("pos", com.leavesdeepseek.agent.util.VecUtil
                .toArray(bot.getLocation())));
        plugin.log().info("躯壳", "已创建躯壳 " + clean + " @ " + bot.getWorld().getName()
                + " " + Math.round(bot.getLocation().getX()) + " "
                + Math.round(bot.getLocation().getY()) + " "
                + Math.round(bot.getLocation().getZ()));
        return body;
    }

    private Bot spawnEntity(String name, Location location) {
        try {
            BotCreator creator = Bukkit.getBotManager().botCreator(name, location)
                    .creator(Bukkit.getConsoleSender());
            Bot bot = creator.spawn();
            if (bot == null) {
                return null;
            }
            bot.setMetadata("NPC", new FixedMetadataValue(plugin, true));
            bot.setGameMode(GameMode.SURVIVAL);
            bot.setInvulnerable(false);
            return bot;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("创建躯壳实体失败：" + throwable.getMessage());
            throwable.printStackTrace();
            return null;
        }
    }

    public boolean remove(String name, boolean save) {
        AIBody body = bodies.get(name);
        if (body == null) {
            return false;
        }
        body.controller().stopAll("REMOVED");
        if (body.session() != null) {
            body.session().bodies().remove(name);
            body.session(null);
        }
        Bot bot = body.bot();
        if (bot != null && bot.isValid()) {
            if (save) {
                store.save(name, bot, body.anchor(), body.createdAt(), System.currentTimeMillis());
            }
            clearInventory(bot);
            bot.remove(false);
        }
        body.bot(null);
        body.state(AIBody.State.OFFLINE);
        bodies.remove(name);
        if (!save) {
            store.delete(name);
        }
        auditEvent(name, "body.remove", JsonRpc.object("save", save));
        plugin.log().info("躯壳", "已移除躯壳 " + name + (save ? "（已存档）" : "（未存档）"));
        return true;
    }

    /** 下线：存档 + 实体移出世界。 */
    public void offline(AIBody body, String reason) {
        Bot bot = body.bot();
        body.controller().stopAll(JsonRpc.nameOf(JsonRpc.BODY_OFFLINE));
        if (bot != null && bot.isValid()) {
            store.save(body.name(), bot, body.anchor(), body.createdAt(), System.currentTimeMillis());
            clearInventory(bot);
            bot.remove(false);
        }
        body.bot(null);
        body.state(AIBody.State.OFFLINE);
        body.sensors().stateEvent(AIBody.State.OFFLINE, reason);
        auditEvent(body.name(), "ev.state", JsonRpc.object("state", "offline", "reason", reason));
    }

    /** 上线（paused/offline → remote）。 */
    public AIBody bringOnline(AIBody body, String reason) {
        if (body.online()) {
            body.guard().resume(reason);
            return body;
        }
        YamlConfiguration yaml = store.read(body.name());
        Location location = resolveLocation(yaml);
        Bot bot = spawnEntity(body.name(), location);
        if (bot == null) {
            throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "无法重新生成躯壳实体");
        }
        body.bot(bot);
        if (yaml != null) {
            store.apply(bot, yaml);
        }
        body.state(AIBody.State.PAUSED);
        body.guard().resume(reason);
        return body;
    }

    private Location resolveLocation(YamlConfiguration yaml) {
        if (yaml != null) {
            World world = Bukkit.getWorld(yaml.getString("world", ""));
            if (world != null) {
                return new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                        (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
            }
        }
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return world == null ? null : world.getSpawnLocation();
    }

    public boolean save(String name) {
        AIBody body = bodies.get(name);
        if (body == null || !body.online()) {
            return false;
        }
        return store.save(name, body.bot(), body.anchor(), body.createdAt(), body.lastSeen());
    }

    private void clearInventory(Bot bot) {
        try {
            bot.getInventory().clear();
            bot.getInventory().setArmorContents(new ItemStack[]{null, null, null, null});
            bot.getInventory().setItemInOffHand(null);
        } catch (Throwable ignored) {
            // 清背包失败不影响移除
        }
    }

    // ------------------------------------------------------------------ 调度

    /** 20 Hz（主线程）。 */
    public void tick() {
        ticks++;
        double[] tps = Bukkit.getTPS();
        double current = tps.length == 0 ? 20.0 : tps[0];
        for (AIBody body : bodies()) {
            try {
                if (current < config.tpsOfflineThreshold()) {
                    offline(body, "tps");
                    continue;
                }
                if (current < config.tpsPauseThreshold() && body.state() == AIBody.State.REMOTE) {
                    body.guard().pause("tps");
                }
                if (body.online()) {
                    body.controller().tick();
                    body.sensors().tick();
                }
                body.guard().tick();
            } catch (Throwable throwable) {
                plugin.log().error("躯壳", "躯壳 tick 异常（" + body.name() + "）：" + throwable.getMessage(),
                        throwable);
            }
        }
        if (++saveCounter >= 600) {
            saveCounter = 0;
            for (AIBody body : bodies()) {
                if (body.online()) {
                    store.save(body.name(), body.bot(), body.anchor(), body.createdAt(), body.lastSeen());
                }
            }
        }
    }

    public void push(AIBody body, String frame) {
        AgentSession session = body.session();
        if (session == null) {
            return;
        }
        serverSend(session, frame);
    }

    public void emitEvent(AIBody body, String frame) {
        AgentSession session = body.session();
        if (session != null) {
            serverSend(session, frame);
        }
        auditEvent(body.name(), "event", null);
    }

    private void serverSend(AgentSession session, String frame) {
        AgentServer current = server;
        if (current != null) {
            current.send(session, frame);
        }
    }

    public void audit(String body, String method, JsonObject params, String result, long elapsedMs) {
        if (config.audit()) {
            audit.write(body, method, params, result, elapsedMs);
        }
    }

    public void auditEvent(String body, String event, JsonObject params) {
        if (config.audit()) {
            audit.writeEvent(body, event, params);
        }
    }

    public List<String> names() {
        return new ArrayList<>(bodies.keySet());
    }

    public void shutdown() {
        for (AIBody body : bodies()) {
            body.controller().stopAll("SHUTDOWN");
            if (body.online()) {
                store.save(body.name(), body.bot(), body.anchor(), body.createdAt(), body.lastSeen());
            }
        }
        AgentServer current = server;
        if (current != null) {
            current.shutdown();
        }
        audit.close();
    }

    public String describe(AIBody body) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return body.name() + " [" + body.state().name().toLowerCase(Locale.ROOT) + "]";
        }
        Location location = bot.getLocation();
        return body.name() + " [" + body.state().name().toLowerCase(Locale.ROOT) + "] @ "
                + location.getWorld().getName() + " " + Math.round(location.getX()) + " "
                + Math.round(location.getY()) + " " + Math.round(location.getZ())
                + " 血=" + Math.round(bot.getHealth());
    }

    /** 供 /ai 命令使用的简易解析。 */
    public Location parseLocation(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    public AIBody require(String name) {
        AIBody body = bodies.get(name);
        if (body == null) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "没有这个躯壳：" + name);
        }
        return body;
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** 供 Player 检查（命令里用）。 */
    public boolean isBodyPlayer(Player player) {
        return player != null && byBotSafe(player) != null;
    }

    private AIBody byBotSafe(Player player) {
        if (player instanceof Bot bot) {
            return byBot(bot);
        }
        return null;
    }
}
