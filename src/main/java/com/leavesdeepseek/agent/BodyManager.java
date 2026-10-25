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
        applyNameRegex(config.bodyNameRegex());
    }

    /** 配置重载后同步名字正则。 */
    public void applyConfig() {
        applyNameRegex(config.bodyNameRegex());
    }

    /**
     * 启动时扫一遍 {@code bodies/*}{@code /state.yml}，把每具躯壳登记进内存（默认 OFFLINE 占位）。
     *
     * <p>之前构造时不扫盘，重启后 {@code body.list} 是空的、{@code attach} 报 no_such_body，
     * 但存档文件明明还在 → 「躯壳丢了」的错觉（V3 §2 P2）。
     * {@code body.auto-load: true} 时会顺便把实体生成出来（paused，等 AI 连上）。</p>
     */
    public void restoreAll() {
        List<String> stored = store.listStored();
        int registered = 0;
        int spawned = 0;
        for (String name : stored) {
            if (bodies.containsKey(name)) {
                continue;
            }
            AIBody body = new AIBody(this, name, null, System.currentTimeMillis());
            body.ownerKey(store.ownerOf(name));
            YamlConfiguration yaml = store.read(name);
            if (yaml != null) {
                World world = Bukkit.getWorld(yaml.getString("world", ""));
                if (world != null) {
                    body.anchor(new Location(world,
                            yaml.getDouble("anchor.x", yaml.getDouble("x")),
                            yaml.getDouble("anchor.y", yaml.getDouble("y")),
                            yaml.getDouble("anchor.z", yaml.getDouble("z"))));
                }
            }
            body.state(AIBody.State.OFFLINE);
            bodies.put(name, body);
            registered++;
            boolean wasDowned = yaml != null && yaml.getBoolean("downed", false);
            if (wasDowned) {
                body.dead(true);
            }
            if (config.autoLoad()) {
                try {
                    bringOnline(body, "auto-load");
                    if (wasDowned) {
                        // 重启前是倒地状态：恢复成「倒地」（无敌 + 1 血），等 owner revive
                        markDownedAfterLoad(body);
                    }
                    spawned++;
                } catch (Throwable throwable) {
                    plugin.log().warn("躯壳", "自动加载失败（" + name + "）：" + throwable.getMessage());
                }
            }
        }
        if (registered > 0) {
            plugin.log().info("躯壳", "启动登记 " + registered + " 具躯壳，其中自动上线 " + spawned
                    + " 具（body.auto-load=" + config.autoLoad() + "）");
        }
    }

    /** 读档时恢复「倒地」外观：无敌 + 低血量，等 revive。 */
    private void markDownedAfterLoad(AIBody body) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return;
        }
        try {
            bot.setInvulnerable(true);
            bot.setHealth(Math.max(0.1, Math.min(1.0, bot.getMaxHealth())));
            body.dead(true);
            plugin.log().info("躯壳", "躯壳 " + body.name() + " 恢复为倒地状态（物品已保留，等 revive）");
        } catch (RuntimeException exception) {
            plugin.log().warn("躯壳", "恢复倒地状态失败：" + exception.getMessage());
        }
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
     * 造一具躯壳（命令行用，不带归属）。
     */
    public AIBody create(String name, Location location) {
        return create(name, location, "");
    }

    /**
     * 造一具躯壳。
     *
     * @param ownerKey 归属指纹（RPC 造的时候传；命令行传空串 = 无归属，只能 OP 删）
     */
    public AIBody create(String name, Location location, String ownerKey) {
        String clean = name == null ? "" : name.trim();
        if (!validName(clean)) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST,
                    "名字必须是 3~16 个字母/数字/下划线",
                    JsonRpc.object("reason", "bad_name", "name", clean));
        }
        if (bodies.containsKey(clean) || plugin.bots().byName(clean) != null
                || Bukkit.getPlayerExact(clean) != null || store.exists(clean)) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "名字已被占用：" + clean,
                    JsonRpc.object("reason", "name_taken", "name", clean));
        }
        if (bodies.size() >= config.maxBodies()) {
            throw new JsonRpc.RpcException(JsonRpc.BUSY, "躯壳数量已达上限 " + config.maxBodies(),
                    JsonRpc.object("reason", "too_many_bodies", "max", config.maxBodies()));
        }
        Location spawn = resolveSpawn(location);
        Bot bot = spawnEntity(clean, spawn);
        if (bot == null) {
            throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "服务端创建假人失败（检查 fakeplayer 配置）",
                    JsonRpc.object("reason", "spawn_failed"));
        }
        AIBody body = new AIBody(this, clean, bot.getUniqueId(), System.currentTimeMillis());
        body.bot(bot);
        body.ownerKey(ownerKey);
        body.state(AIBody.State.PAUSED);
        bodies.put(clean, body);
        if (store.exists(clean)) {
            YamlConfiguration yaml = store.read(clean);
            store.apply(bot, yaml);
            String stored = store.ownerOf(clean);
            if (!stored.isBlank()) {
                body.ownerKey(stored);
            }
        }
        store.save(clean, bot, body.anchor(), body.createdAt(), System.currentTimeMillis(),
                body.ownerKey());
        if (config.auditCreate()) {
            auditEvent(clean, "body.create", JsonRpc.object(
                    "pos", com.leavesdeepseek.agent.util.VecUtil.toArray(bot.getLocation()),
                    "owner", body.ownerKey(),
                    "source", ownerKey.isBlank() ? "command" : "rpc"));
        }
        plugin.log().info("躯壳", "已创建躯壳 " + clean + " @ " + bot.getWorld().getName()
                + " " + Math.round(bot.getLocation().getX()) + " "
                + Math.round(bot.getLocation().getY()) + " "
                + Math.round(bot.getLocation().getZ())
                + (ownerKey.isBlank() ? "" : " | owner=" + ownerKey));
        return body;
    }

    /** create 的落点：显式坐标 > create-default-world 的重生点 > 主世界重生点，并抬高 y-offset。 */
    private Location resolveSpawn(Location location) {
        Location spawn = location;
        if (spawn == null || spawn.getWorld() == null) {
            World world = null;
            String configured = config.createDefaultWorld();
            if (!configured.isBlank()) {
                world = Bukkit.getWorld(configured);
            }
            if (world == null) {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            }
            if (world == null) {
                throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "没有可用世界",
                        JsonRpc.object("reason", "no_world"));
            }
            spawn = world.getSpawnLocation();
        }
        int offset = config.createDefaultYOffset();
        if (offset > 0) {
            spawn = spawn.clone().add(0, offset, 0);
        }
        return spawn;
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
        return remove(name, save, null);
    }

    /**
     * 删除躯壳。
     *
     * @param requesterKey 请求者指纹（null = 命令行/OP，跳过归属校验）
     */
    public boolean remove(String name, boolean save, String requesterKey) {
        AIBody body = bodies.get(name);
        if (body == null) {
            return false;
        }
        if (requesterKey != null) {
            checkDeletable(body, requesterKey);
        }
        body.controller().stopAll("REMOVED");
        if (body.session() != null) {
            body.session().bodies().remove(name);
            body.session(null);
        }
        Bot bot = body.bot();
        if (bot != null && bot.isValid()) {
            if (save) {
                store.save(name, bot, body.anchor(), body.createdAt(), System.currentTimeMillis(), body.ownerKey(), body.dead());
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
        auditEvent(name, "body.remove", JsonRpc.object("save", save,
                "source", requesterKey == null ? "command" : "rpc",
                "owner", body.ownerKey()));
        plugin.log().info("躯壳", "已移除躯壳 " + name + (save ? "（已存档）" : "（未存档）"));
        return true;
    }

    /**
     * 归属校验（验收 07/08/11）：
     * <ul>
     *   <li>{@code agent.allow-delete: false}（默认）→ 一律拒绝 {@code delete_disabled}；</li>
     *   <li>没有 owner 字段的老躯壳 → 拒绝 {@code legacy_body}；</li>
     *   <li>指纹不一致 → 拒绝 {@code not_owner}。</li>
     * </ul>
     */
    private void checkDeletable(AIBody body, String requesterKey) {
        String mode = config.allowDeleteMode();
        if ("false".equals(mode)) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "服务器禁止 AI 删除躯壳（agent.allow-delete: false）",
                    JsonRpc.object("reason", "delete_disabled", "body", body.name()));
        }
        String owner = body.ownerKey();
        if (owner == null || owner.isBlank()) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "这是老躯壳（没有归属记录），只能由管理员用 /ai body remove 删除",
                    JsonRpc.object("reason", "legacy_body", "body", body.name()));
        }
        if ("all".equals(mode)) {
            return;
        }
        if (!owner.equals(requesterKey)) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED, "这不是你造的躯壳",
                    JsonRpc.object("reason", "not_owner", "body", body.name()));
        }
    }

    /** 下线：存档 + 实体移出世界。 */
    public void offline(AIBody body, String reason) {
        Bot bot = body.bot();
        body.controller().stopAll(JsonRpc.nameOf(JsonRpc.BODY_OFFLINE));
        if (bot != null && bot.isValid()) {
            store.save(body.name(), bot, body.anchor(), body.createdAt(), System.currentTimeMillis(), body.ownerKey(), body.dead());
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
        return store.save(name, body.bot(), body.anchor(), body.createdAt(), body.lastSeen(), body.ownerKey(), body.dead());
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
                    store.save(body.name(), body.bot(), body.anchor(), body.createdAt(), body.lastSeen(), body.ownerKey(), body.dead());
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

    /** {@code /ai body set <token> [name]}：给躯壳设一个独立 token（空 = 清除）。 */
    public boolean setToken(String name, String token) {
        AIBody body = bodies.get(name);
        if (body == null && !store.exists(name)) {
            return false;
        }
        if (!store.setToken(name, token)) {
            return false;
        }
        // token 换了 = 归属指纹也换，避免旧 token 还能删
        if (body != null && token != null && !token.isBlank()) {
            body.ownerKey(fingerprintOf(token.trim()));
        }
        if (config.auditCreate()) {
            auditEvent(name, "body.set-token", JsonRpc.object(
                    "set", token != null && !token.isBlank(),
                    "fingerprint", token == null || token.isBlank() ? "" : fingerprintOf(token.trim())));
        }
        return true;
    }

    /**
     * 按 token 找躯壳（auth 时判断「这是哪具躯壳的专属 token」）。
     *
     * @return 躯壳名；不是专属 token 则返回 null
     */
    public String bodyOfToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String clean = token.trim();
        for (AIBody body : bodies()) {
            String own = store.tokenOf(body.name());
            if (!own.isBlank() && own.equals(clean)) {
                return body.name();
            }
        }
        // 内存里没登记但存档里有（例如刚被手动删掉内存记录）
        for (String name : store.listStored()) {
            String own = store.tokenOf(name);
            if (!own.isBlank() && own.equals(clean)) {
                return name;
            }
        }
        return null;
    }

    /**
     * 复活躯壳：不在线就先上线，然后回满血、传送到 anchor（或原地起来）。
     */
    public void revive(AIBody body) {
        if (body == null) {
            return;
        }
        if (!body.online()) {
            bringOnline(body, "revive");
        }
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "无法生成躯壳实体",
                    JsonRpc.object("reason", "spawn_failed"));
        }
        if (bot.isDead()) {
            try {
                bot.spigot().respawn();
            } catch (Throwable throwable) {
                plugin.log().warn("躯壳", "respawn 调用失败：" + throwable.getMessage());
            }
        }
        Location anchor = body.anchor();
        if (anchor != null && anchor.getWorld() != null) {
            bot.teleport(anchor);
        }
        bot.setHealth(bot.getMaxHealth());
        bot.setFoodLevel(20);
        bot.setSaturation(20.0f);
        bot.setFireTicks(0);
        bot.setFallDistance(0.0f);
        bot.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        body.dead(false);
        body.guard().resume("revive");
        store.save(body.name(), bot, anchor, body.createdAt(), System.currentTimeMillis(), body.ownerKey(), false);
        plugin.log().info("躯壳", "已复活躯壳 " + body.name() + "（物品保留）");
    }

    /** 供命令/感知用：存档里该躯壳的 token 指纹。 */
    public String tokenFingerprintOf(String name) {
        String token = store.tokenOf(name);
        return token.isBlank() ? "" : fingerprintOf(token);
    }

    public List<String> names() {
        return new ArrayList<>(bodies.keySet());
    }

    /**
     * {@code body.list}：列出全部躯壳（不需要 attach）。
     *
     * @param requesterKey 请求者指纹，用来填 {@code mine}
     */
    public com.google.gson.JsonArray list(String requesterKey) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (AIBody body : bodies()) {
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("name", body.name());
            entry.addProperty("state", body.state().name().toLowerCase(Locale.ROOT));
            entry.addProperty("online", body.online());
            entry.addProperty("attached", body.session() != null);
            Location location = body.location();
            if (location != null) {
                entry.add("pos", com.leavesdeepseek.agent.util.VecUtil.toArray(location));
                entry.addProperty("health", Math.round(body.bot().getHealth() * 10) / 10.0);
                entry.addProperty("food", body.bot().getFoodLevel());
            }
            String owner = body.ownerKey();
            entry.addProperty("owner", owner == null || owner.isBlank() ? "" : owner);
            entry.addProperty("mine", requesterKey != null && !owner.isBlank() && owner.equals(requesterKey));
            entry.addProperty("downed", body.dead());
            String token = store.tokenOf(body.name());
            entry.addProperty("hasToken", !token.isBlank());
            array.add(entry);
        }
        return array;
    }

    public void shutdown() {
        for (AIBody body : bodies()) {
            body.controller().stopAll("SHUTDOWN");
            if (body.online()) {
                store.save(body.name(), body.bot(), body.anchor(), body.createdAt(), body.lastSeen(), body.ownerKey(), body.dead());
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
        if (name == null) {
            return false;
        }
        String custom = configRegex;
        if (custom != null && !custom.isBlank()) {
            try {
                return java.util.regex.Pattern.compile(custom).matcher(name).matches();
            } catch (java.util.regex.PatternSyntaxException exception) {
                // 配错了就退回内置规则
                return NAME.matcher(name).matches();
            }
        }
        return NAME.matcher(name).matches();
    }

    /** 由 AgentConfig 在 reload 时写入的自定义名字正则（静态是为了让 validName 能被静态调用）。 */
    private static volatile String configRegex = "";

    public static void applyNameRegex(String regex) {
        configRegex = regex == null ? "" : regex.trim();
    }

    /** token 的指纹（sha256 前 16 位，不存明文）。 */
    public static String fingerprintOf(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            return Integer.toHexString(token.hashCode());
        }
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
