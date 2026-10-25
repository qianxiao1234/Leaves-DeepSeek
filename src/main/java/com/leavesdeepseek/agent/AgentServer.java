package com.leavesdeepseek.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.task.AgentTask;
import com.leavesdeepseek.agent.task.AttackTask;
import com.leavesdeepseek.agent.task.ContainerTask;
import com.leavesdeepseek.agent.task.CraftTask;
import com.leavesdeepseek.agent.task.DigAreaTask;
import com.leavesdeepseek.agent.task.DigTask;
import com.leavesdeepseek.agent.task.FollowTask;
import com.leavesdeepseek.agent.task.GotoTask;
import com.leavesdeepseek.agent.task.IdleTask;
import com.leavesdeepseek.agent.task.LookTask;
import com.leavesdeepseek.agent.task.PlaceTask;
import com.leavesdeepseek.agent.util.ItemUtil;
import com.leavesdeepseek.agent.util.VecUtil;
import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.action.JumpAction;
import org.leavesmc.leaves.entity.bot.action.UseItemAction;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket + JSON-RPC 服务（规格 §6 / §7）。
 *
 * <p>线程模型：解析/鉴权/限速在 WS 线程；一切世界读写都 {@code runTask} 回主线程。</p>
 */
public final class AgentServer extends WebSocketServer {

    private final LeavesDeepSeekPlugin plugin;
    private final AgentConfig config;
    private final BodyManager manager;
    /** 未鉴权时用的自定义关闭码（JSON-RPC 负数不能当 WS close code） */
    private static final int CLOSE_UNAUTHORIZED = 4001;

    private final Map<WebSocket, AgentSession> sessions = new ConcurrentHashMap<>();

    public AgentServer(LeavesDeepSeekPlugin plugin, AgentConfig config, BodyManager manager) {
        super(new InetSocketAddress(config.bind(), config.port()));
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    public void boot() {
        start();
    }

    @Override
    public void onStart() {
        plugin.log().info("躯壳", "Agent WebSocket 已启动：ws://" + config.bind() + ":" + config.port() + "/agent"
                + "  token=" + config.token()
                + (config.usingDefaultToken() ? "（默认值，可在 config.yml 的 agent.token 改）" : ""));
        if (!"127.0.0.1".equals(config.bind())) {
            plugin.getLogger().warning("=================================================");
            plugin.getLogger().warning(" agent.bind = " + config.bind() + "（非本机回环）！");
            if (config.usingDefaultToken()) {
                plugin.getLogger().warning(" 并且 agent.token 还在用默认值 " + config.token() + "，"
                        + "任何知道它的人都能接管躯壳，强烈建议马上改成随机串！");
            } else {
                plugin.getLogger().warning(" 躯壳控制端口暴露到了局域网/公网，请确认 agent.token 足够复杂。");
            }
            plugin.getLogger().warning("=================================================");
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        AgentSession session = new AgentSession(conn);
        sessions.put(conn, session);
        plugin.log().info("躯壳", "外部连接接入：" + session.remoteAddress());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            AgentSession current = sessions.get(conn);
            if (current != null && !current.authenticated()) {
                close(conn, CLOSE_UNAUTHORIZED, "5 秒内没有鉴权");
            }
        }, config.authTimeoutSeconds() * 20L);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        AgentSession session = sessions.remove(conn);
        if (session == null) {
            return;
        }
        for (String name : session.bodies()) {
            AIBody body = manager.byName(name);
            if (body != null && body.session() == session) {
                body.session(null);
                body.touch();
            }
        }
        session.bodies().clear();
        plugin.log().info("躯壳", "外部连接断开：" + session.remoteAddress()
                + (reason == null || reason.isBlank() ? "" : "（" + reason + "）"));
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            plugin.log().error("躯壳", "Agent 服务异常：" + ex.getMessage(), ex);
            return;
        }
        plugin.log().warn("躯壳", "Agent 连接异常：" + ex.getMessage());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        AgentSession session = sessions.get(conn);
        if (session == null) {
            return;
        }
        if (message == null || message.length() > config.maxFrameBytes()) {
            close(conn, JsonRpc.BAD_REQUEST, "帧过大");
            return;
        }
        long started = System.currentTimeMillis();
        JsonObject frame;
        try {
            frame = JsonRpc.parse(message);
        } catch (RuntimeException exception) {
            send(session, JsonRpc.error(null, JsonRpc.BAD_REQUEST, exception.getMessage(), null));
            return;
        }
        JsonElement id = frame.get("id");
        String method = frame.has("method") ? frame.get("method").getAsString() : "";
        JsonObject params = frame.has("params") && frame.get("params").isJsonObject()
                ? frame.getAsJsonObject("params") : new JsonObject();

        if (method.equals("auth")) {
            handleAuth(session, id, params);
            return;
        }
        if (!session.authenticated()) {
            send(session, JsonRpc.error(id, JsonRpc.AUTH_FAILED, null, null));
            close(conn, CLOSE_UNAUTHORIZED, "未鉴权");
            return;
        }
        if (method.startsWith("sense.")) {
            if (!session.allowSense(config.maxSensePerSecond())) {
                send(session, JsonRpc.error(id, JsonRpc.RATE_LIMITED, "感知请求过快", null));
                manager.audit("-", method, params, "RATE_LIMITED", System.currentTimeMillis() - started);
                return;
            }
        } else if (method.startsWith("act.")) {
            if (!session.allowAction(config.maxActionsPerSecond())) {
                send(session, JsonRpc.error(id, JsonRpc.RATE_LIMITED, "动作请求过快", null));
                manager.audit("-", method, params, "RATE_LIMITED", System.currentTimeMillis() - started);
                return;
            }
        }
        session.touch();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String auditBody = "-";
            try {
                JsonElement result = dispatch(session, method, params);
                if (result != null) {
                    send(session, JsonRpc.response(id, result));
                }
                if (!method.startsWith("sense.")) {
                    AIBody body = attached(session);
                    auditBody = body == null ? "-" : body.name();
                }
                manager.audit(auditBody, method, params, "ok", System.currentTimeMillis() - started);
            } catch (JsonRpc.RpcException exception) {
                JsonObject data = exception.data();
                if (data == null) {
                    data = new JsonObject();
                }
                send(session, JsonRpc.error(id, exception.code(), exception.getMessage(), data));
                manager.audit(auditBody, method, params, JsonRpc.nameOf(exception.code()),
                        System.currentTimeMillis() - started);
            } catch (Throwable throwable) {
                JsonObject data = new JsonObject();
                data.addProperty("detail", String.valueOf(throwable.getMessage()));
                send(session, JsonRpc.error(id, JsonRpc.INTERNAL, throwable.getMessage(), data));
                plugin.log().error("躯壳", method + " 执行失败：" + throwable.getMessage(), throwable);
                manager.audit(auditBody, method, params, "INTERNAL", System.currentTimeMillis() - started);
            }
        });
    }

    // ------------------------------------------------------------------ 鉴权

    private void handleAuth(AgentSession session, JsonElement id, JsonObject params) {
        String token = Json.getString(params, "token", "");
        if (token == null || token.isBlank()) {
            send(session, JsonRpc.error(id, JsonRpc.AUTH_FAILED, null, null));
            close(session.connection(), CLOSE_UNAUTHORIZED, "token 为空");
            return;
        }
        String clean = token.trim();
        boolean global = config.token().equals(clean);
        String boundBody = global ? null : manager.bodyOfToken(clean);
        if (!global && boundBody == null) {
            send(session, JsonRpc.error(id, JsonRpc.AUTH_FAILED, null, null));
            close(session.connection(), CLOSE_UNAUTHORIZED, "token 错误");
            return;
        }
        session.authenticated(true);
        session.fingerprint(BodyManager.fingerprintOf(clean));
        session.boundBody(boundBody);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("server", "LeavesDeepSeek");
        result.addProperty("version", "1");
        result.addProperty("scope", boundBody == null ? "global" : "body");
        if (boundBody != null) {
            result.addProperty("body", boundBody);
        }
        send(session, JsonRpc.response(id, result));
        // 躯壳专属 token：auth 完直接 attach，客户端不用再发一次 body.attach
        if (boundBody != null) {
            String target = boundBody;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    JsonObject attachParams = new JsonObject();
                    attachParams.addProperty("name", target);
                    attach(session, attachParams);
                } catch (JsonRpc.RpcException exception) {
                    send(session, JsonRpc.notify("ev.state", JsonRpc.object(
                            "state", "error", "reason", exception.getMessage())));
                }
            });
        }
    }

    // ------------------------------------------------------------------ 分发

    private JsonElement dispatch(AgentSession session, String method, JsonObject params) {
        // 这三条必须放在 attached() 之前：客户端还没 attach 时也要能调（施工清单 §2.1）
        switch (method) {
            case "body.help":
                return help();
            case "body.list":
                return manager.list(session.fingerprint());
            case "body.create":
                return createBody(session, params);
            case "body.delete":
                return deleteBody(session, params);
            default:
                break;
        }
        AIBody body = attached(session);
        switch (method) {
            case "body.attach":
                return attach(session, params);
            case "body.detach":
                if (body != null) {
                    body.session(null);
                    session.bodies().remove(body.name());
                    // P2-2：detach 之后立刻切旁观并推 ev.state，别让客户端以为还能动
                    body.guard().pause("detach");
                }
                return JsonRpc.object("ok", true, "state", "paused");
            case "body.info":
                return requireBody(body).sensors().snapshot();
            case "body.spawn", "body.load":
                return spawn(requireBody(body), params);
            case "body.despawn":
                return despawn(requireBody(body), params);
            case "body.respawn":
                return respawn(requireBody(body));
            case "body.anchor":
                return anchor(requireBody(body), params);
            case "sense.snapshot":
                return requireBody(body).sensors().snapshot();
            case "sense.terrain":
                return requireBody(body).sensors().terrainJson();
            case "sense.entity":
                return requireBody(body).sensors().entities(
                        Json.getDouble(params, "radius", 16.0), Json.getString(params, "kind", "all"));
            case "sense.item":
                return requireBody(body).sensors().items(Json.getDouble(params, "radius", 8.0));
            case "sense.blockAt":
                return blockAt(requireBody(body), params);
            case "sense.blocks":
                return blocks(requireBody(body), params);
            case "sense.findBlock":
                return findBlocks(requireBody(body), params);
            case "sense.player":
                return players(requireBody(body), params);
            case "sense.canReach":
                return canReach(requireBody(body), params);
            default:
                break;
        }
        if (method.startsWith("act.")) {
            return act(requireBody(body), method, params);
        }
        throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "未知方法：" + method);
    }

    private JsonElement attach(AgentSession session, JsonObject params) {
        String name = Json.getString(params, "name", "");
        if (session.boundBody() != null && !session.boundBody().equals(name)) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "这个连接只能用躯壳 " + session.boundBody(),
                    JsonRpc.object("reason", "body_scoped_token", "body", session.boundBody()));
        }
        AIBody body = manager.byName(name);
        if (body == null) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "没有这个躯壳：" + name,
                    JsonRpc.object("reason", "no_such_body", "body", name));
        }
        if (body.session() != null && body.session() != session) {
            throw new JsonRpc.RpcException(JsonRpc.BUSY, "躯壳已被其他会话占用",
                    JsonRpc.object("reason", "body_busy", "body", name));
        }
        if (!session.canAttach(config.maxBodiesPerSession()) && !session.bodies().contains(name)) {
            throw new JsonRpc.RpcException(JsonRpc.BUSY,
                    "单个会话最多 attach " + config.maxBodiesPerSession() + " 具躯壳");
        }
        if (!body.online()) {
            manager.bringOnline(body, "attach");
        } else {
            body.guard().resume("attach");
        }
        body.session(session);
        session.bodies().add(name);
        body.touch();
        // 新连接：清基线 → 强制推一帧 full:true 背包 → 补发断连期间的聊天
        body.sensors().resetBaselines();
        body.sensors().pushFullInventory();
        body.sensors().tick();
        body.sensors().replayChat();
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("state", body.state().name().toLowerCase(Locale.ROOT));
        result.addProperty("tick", manager.currentTick());
        Location location = body.location();
        if (location != null) {
            result.add("pos", VecUtil.toArray(location));
        }
        body.sensors().stateEvent(body.state(), "attach");
        return result;
    }

    /** {@code body.create}：AI 自己造躯壳（施工清单 §2.1）。 */
    private JsonElement createBody(AgentSession session, JsonObject params) {
        if (session.boundBody() != null) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "这个连接用的是躯壳 " + session.boundBody() + " 的专属 token，不能再造新躯壳",
                    JsonRpc.object("reason", "body_scoped_token", "body", session.boundBody()));
        }
        if (!config.allowCreate()) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "服务器禁止 AI 造躯壳（agent.allow-create: false）",
                    JsonRpc.object("reason", "create_disabled"));
        }
        String name = Json.getString(params, "name", "");
        Location location = null;
        boolean hasCoords = params.has("x") || params.has("y") || params.has("z");
        World world = null;
        if (params.has("world") && !params.get("world").getAsString().isBlank()) {
            world = Bukkit.getWorld(params.get("world").getAsString());
            if (world == null) {
                throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND,
                        "世界不存在：" + params.get("world").getAsString(),
                        JsonRpc.object("reason", "no_such_world"));
            }
        }
        if (hasCoords) {
            if (world == null) {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            }
            if (world == null) {
                throw new JsonRpc.RpcException(JsonRpc.INTERNAL, "没有可用世界",
                        JsonRpc.object("reason", "no_world"));
            }
            location = new Location(world,
                    Json.getDouble(params, "x", 0),
                    Json.getDouble(params, "y", 64),
                    Json.getDouble(params, "z", 0));
        } else if (world != null) {
            location = world.getSpawnLocation();
        }
        AIBody body = manager.create(name, location, session.fingerprint());
        boolean autoAttach = Json.getBoolean(params, "autoAttach", true);
        if (autoAttach) {
            if (!session.canAttach(config.maxBodiesPerSession())
                    && !session.bodies().contains(body.name())) {
                // 造成功了但本会话已挂满：不进 attach，客户端可以换连接再 attach
                autoAttach = false;
            }
        }
        if (autoAttach) {
            body.session(session);
            session.bodies().add(body.name());
            body.touch();
            body.sensors().resetBaselines();
            body.sensors().pushFullInventory();
            body.sensors().tick();
            body.guard().resume("create");
        }
        JsonObject result = new JsonObject();
        result.addProperty("name", body.name());
        result.addProperty("state", body.state().name().toLowerCase(Locale.ROOT));
        result.addProperty("attached", autoAttach);
        Location actual = body.location();
        if (actual != null) {
            result.add("pos", VecUtil.toArray(actual));
            result.addProperty("world", actual.getWorld() == null ? "" : actual.getWorld().getName());
        }
        return result;
    }

    /** {@code body.delete}：删躯壳（校验归属）。 */
    private JsonElement deleteBody(AgentSession session, JsonObject params) {
        String name = Json.getString(params, "name", "");
        boolean save = Json.getBoolean(params, "save", true);
        if (manager.byName(name) == null) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "没有这个躯壳：" + name,
                    JsonRpc.object("reason", "no_such_body", "body", name));
        }
        manager.remove(name, save, session.fingerprint());
        return JsonRpc.object("ok", true, "name", name, "saved", save);
    }

    /** {@code body.help}：方法清单（只有规格书没有源码的客户端用来发现能力）。 */
    private JsonElement help() {
        JsonArray methods = new JsonArray();
        addHelp(methods, "body.list", "", "列出所有躯壳（不需要 attach）");
        addHelp(methods, "body.create", "name, x/y/z?, world?, autoAttach=true",
                "造一具躯壳（默认自动 attach 并推全量背包）");
        addHelp(methods, "body.delete", "name, save=true", "删掉躯壳（只能删自己造的）");
        addHelp(methods, "body.help", "", "列出所有方法");
        addHelp(methods, "body.attach", "name", "绑定躯壳（每躯壳同时只允许一个会话）");
        addHelp(methods, "body.detach", "", "解绑并立刻进入旁观");
        addHelp(methods, "body.info", "", "完整快照（同 sense.snapshot）");
        addHelp(methods, "body.spawn", "save?", "上线/恢复");
        addHelp(methods, "body.despawn", "save?", "存档并下线");
        addHelp(methods, "body.respawn", "", "倒地/死亡后复活（回满血、回 anchor，物品保留）");
        addHelp(methods, "body.anchor", "x,y,z | clear:true", "设置/清除死亡重生点");
        addHelp(methods, "sense.snapshot", "", "全量快照");
        addHelp(methods, "sense.terrain", "", "地形语义（six/landmark/hazard）");
        addHelp(methods, "sense.entity", "radius?,kind?", "附近实体");
        addHelp(methods, "sense.item", "radius?", "地上的掉落物");
        addHelp(methods, "sense.blockAt", "x,y,z", "单个方块");
        addHelp(methods, "sense.blocks", "x1..z2,filter?,limit?", "立方体区域（≤16³）");
        addHelp(methods, "sense.findBlock", "id,radius?,max?", "找方块");
        addHelp(methods, "sense.player", "radius?", "附近玩家（字段 n/p/d/hp/held/look/gm）");
        addHelp(methods, "sense.canReach", "x,y,z", "能不能够到");
        addHelp(methods, "act.goto", "x,y,z,range?,sprint?,interrupt?", "走过去（目标在实心方块里会自动上抬，回执 adjustY）");
        addHelp(methods, "act.follow", "player,distance?", "跟到 distance+0.5 内并稳定 10 tick 即完成");
        addHelp(methods, "act.look", "yaw+pitch | x,y,z | entityId", "平滑转向");
        addHelp(methods, "act.attack", "entityId?,kind?,times?,chase?", "打目标");
        addHelp(methods, "act.dig", "x,y,z,collect?", "挖一个方块");
        addHelp(methods, "act.digArea", "x1..z2,filter?", "挖一片（≤16³）");
        addHelp(methods, "act.place", "item,x,y,z", "放方块（手上要有）");
        addHelp(methods, "act.craft", "item,count?", "合成（3x3 需要半径 4 内有工作台）");
        addHelp(methods, "act.container", "x,y,z,action,item?,count?", "箱子存取（默认禁用）");
        addHelp(methods, "act.use", "slot?", "右键当前/指定物品");
        addHelp(methods, "act.equip",
                "item? | slot?（0-8 快捷栏 / 9-35 背包 / 36-39 盔甲 / 40 副手）", "装备");
        addHelp(methods, "act.eat",
                "tier?(normal|any),item?,foods?,slot?", "吃东西；默认只吃普通食物");
        addHelp(methods, "act.drop", "slot,count?", "丢物品（受 allow-drop-item 限制）");
        addHelp(methods, "act.jump", "", "跳一下");
        addHelp(methods, "act.sneak", "on", "潜行开关");
        addHelp(methods, "act.sprint", "on", "疾跑开关");
        addHelp(methods, "act.chat", "text", "说话（1 次 / 2 秒）");
        addHelp(methods, "act.idle", "", "原地待命任务");
        addHelp(methods, "act.cancel", "taskId? | all:true", "取消任务");
        addHelp(methods, "act.queue", "", "当前队列（含 elapsedMs）");
        JsonObject result = new JsonObject();
        result.add("methods", methods);
        return result;
    }

    private static void addHelp(JsonArray array, String name, String params, String desc) {
        JsonObject method = new JsonObject();
        method.addProperty("name", name);
        method.addProperty("params", params);
        method.addProperty("desc", desc);
        array.add(method);
    }

    private JsonElement spawn(AIBody body, JsonObject params) {
        boolean save = Json.getBoolean(params, "save", true);
        if (!body.online()) {
            manager.bringOnline(body, "spawn");
        } else {
            body.guard().resume("spawn");
        }
        if (save) {
            manager.save(body.name());
        }
        return JsonRpc.object("state", body.state().name().toLowerCase(Locale.ROOT));
    }

    private JsonElement despawn(AIBody body, JsonObject params) {
        boolean save = Json.getBoolean(params, "save", true);
        if (save) {
            manager.save(body.name());
        }
        body.controller().stopAll("DESPAWN");
        manager.offline(body, "despawn");
        return JsonRpc.object("state", "offline");
    }

    private JsonElement respawn(AIBody body) {
        manager.revive(body);
        Bot bot = body.bot();
        if (body.session() != null && bot != null && bot.isValid()) {
            body.sensors().event("ev.respawn", JsonRpc.object("pos", VecUtil.toArray(bot.getLocation())));
        }
        return JsonRpc.object("pos", bot == null || !bot.isValid()
                ? new com.google.gson.JsonArray() : VecUtil.toArray(bot.getLocation()));
    }

    private JsonElement anchor(AIBody body, JsonObject params) {
        if (Json.getBoolean(params, "clear", false)) {
            body.anchor(null);
            return JsonRpc.object("anchor", null);
        }
        Location current = body.location();
        World world = current == null ? null : current.getWorld();
        if (world == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        Location anchor = new Location(world,
                Json.getDouble(params, "x", current.getX()),
                Json.getDouble(params, "y", current.getY()),
                Json.getDouble(params, "z", current.getZ()));
        body.anchor(anchor);
        manager.save(body.name());
        return JsonRpc.object("anchor", VecUtil.toArray(anchor));
    }

    private JsonElement act(AIBody body, String method, JsonObject params) {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在线");
        }
        if (body.state() != AIBody.State.REMOTE) {
            throw new JsonRpc.RpcException(JsonRpc.SPECTATOR, "躯壳当前不可操作（state="
                    + body.state().name().toLowerCase(Locale.ROOT) + "）");
        }
        boolean interrupt = Json.getBoolean(params, "interrupt", true);
        switch (method) {
            case "act.goto": {
                Location requested = locationOf(body, params);
                Location target = adjustToStandable(requested);
                JsonObject result = (JsonObject) submitted(body.controller().submit(new GotoTask(target,
                        Json.getDouble(params, "range", 1.5),
                        Json.getBoolean(params, "sprint", true)), interrupt));
                if (target.getBlockY() != requested.getBlockY()) {
                    result.addProperty("adjustedY", target.getBlockY());
                }
                return result;
            }
            case "act.follow": {
                String player = Json.getString(params, "player", "");
                return submitted(body.controller().submit(new FollowTask(player,
                        Json.getDouble(params, "distance", 3.0)), interrupt));
            }
            case "act.look": {
                Float yaw = params.has("yaw") ? (float) Json.getDouble(params, "yaw", 0) : null;
                Float pitch = params.has("pitch") ? (float) Json.getDouble(params, "pitch", 0) : null;
                VecUtil.Point point = params.has("x")
                        ? new VecUtil.Point(Json.getDouble(params, "x", 0),
                        Json.getDouble(params, "y", 0), Json.getDouble(params, "z", 0)) : null;
                Integer entityId = params.has("entityId") ? Json.getInt(params, "entityId", -1) : null;
                return submitted(body.controller().submit(new LookTask(yaw, pitch, point, entityId), interrupt));
            }
            case "act.attack": {
                Integer entityId = params.has("entityId") ? Json.getInt(params, "entityId", -1) : null;
                return submitted(body.controller().submit(new AttackTask(entityId,
                        Json.getString(params, "kind", "hostile"),
                        Json.getInt(params, "times", 1),
                        Json.getBoolean(params, "chase", true)), interrupt));
            }
            case "act.dig": {
                return submitted(body.controller().submit(new DigTask(pointOf(params),
                        Json.getBoolean(params, "collect", true)), interrupt));
            }
            case "act.digArea": {
                VecUtil.Point from = new VecUtil.Point(Json.getDouble(params, "x1", 0),
                        Json.getDouble(params, "y1", 0), Json.getDouble(params, "z1", 0));
                VecUtil.Point to = new VecUtil.Point(Json.getDouble(params, "x2", 0),
                        Json.getDouble(params, "y2", 0), Json.getDouble(params, "z2", 0));
                Material filter = ItemUtil.match(params.has("filter") && params.get("filter").isJsonPrimitive()
                        ? params.get("filter").getAsString() : null);
                return submitted(body.controller().submit(new DigAreaTask(from, to, filter), interrupt));
            }
            case "act.place": {
                Material material = ItemUtil.match(Json.getString(params, "item", ""));
                if (material == null) {
                    throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "place 需要 item");
                }
                return submitted(body.controller().submit(new PlaceTask(pointOf(params), material), interrupt));
            }
            case "act.craft": {
                Material material = ItemUtil.match(Json.getString(params, "item", ""));
                if (material == null) {
                    throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "craft 需要 item");
                }
                return submitted(body.controller().submit(new CraftTask(material,
                        Json.getInt(params, "count", 1)), interrupt));
            }
            case "act.container": {
                Material material = ItemUtil.match(Json.getString(params, "item", ""));
                return submitted(body.controller().submit(new ContainerTask(pointOf(params),
                        Json.getString(params, "action", "open"), material,
                        Json.getInt(params, "count", 64)), interrupt));
            }
            case "act.idle":
                return submitted(body.controller().submit(new IdleTask(), interrupt));
            case "act.use":
                return use(bot, params);
            case "act.equip":
                return equip(body, bot, params);
            case "act.eat":
                return eat(body, bot, params);
            case "act.drop":
                return drop(body, bot, params);
            case "act.jump":
                return jump(bot);
            case "act.sneak":
                bot.setSneaking(Json.getBoolean(params, "on", true));
                return JsonRpc.object("sneak", bot.isSneaking());
            case "act.sprint":
                bot.setSprinting(Json.getBoolean(params, "on", true));
                return JsonRpc.object("sprint", bot.isSprinting());
            case "act.chat":
                return chat(body, bot, params);
            case "act.cancel": {
                boolean all = Json.getBoolean(params, "all", false);
                String taskId = Json.getString(params, "taskId", null);
                int cancelled = body.controller().cancel(taskId, all);
                return JsonRpc.object("cancelled", cancelled);
            }
            case "act.queue": {
                JsonArray array = new JsonArray();
                for (AgentTask task : body.controller().snapshot()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("taskId", task.id());
                    entry.addProperty("type", task.type());
                    entry.addProperty("progress", VecUtil.round(task.progress()));
                    entry.addProperty("elapsedMs", task.elapsedMs());
                    array.add(entry);
                }
                return array;
            }
            default:
                throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "未知动作：" + method);
        }
    }

    private JsonElement submitted(BodyController.Submitted submitted) {
        JsonObject result = new JsonObject();
        result.addProperty("taskId", submitted.taskId());
        result.addProperty("status", submitted.status());
        result.addProperty("type", submitted.type());
        result.addProperty("etaMs", submitted.etaMs());
        return result;
    }

    private JsonElement use(Bot bot, JsonObject params) {
        if (params.has("slot")) {
            int slot = Json.getInt(params, "slot", 0);
            if (slot >= 0 && slot < 9) {
                bot.getInventory().setHeldItemSlot(slot);
            }
        }
        plugin.bots().stopActions(bot, UseItemAction.class);
        UseItemAction action = UseItemAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        action.setUseTickTimeout(40);
        bot.addAction(action);
        return JsonRpc.object("ok", true);
    }

    private JsonElement equip(AIBody body, Bot bot, JsonObject params) {
        String rawItem = Json.getString(params, "item", null);
        Material material = null;
        if (rawItem != null && !rawItem.isBlank()) {
            material = ItemUtil.match(rawItem);
            if (material == null) {
                throw badItem(rawItem);
            }
        }
        Integer slotParam = params.has("slot") ? Json.getInt(params, "slot", -1) : null;
        if (material == null) {
            if (slotParam == null) {
                throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "equip 需要 item 或 slot",
                        JsonRpc.object("reason", "missing_param"));
            }
            int slot = slotParam;
            PlayerInventory inventory = bot.getInventory();
            if (slot == 40) {
                ItemStack off = inventory.getItemInOffHand();
                if (off == null || off.getType().isAir()) {
                    throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "副手是空的",
                            JsonRpc.object("reason", "slot_empty", "slot", 40));
                }
                ItemStack main = inventory.getItemInMainHand();
                inventory.setItemInOffHand(main);
                inventory.setItemInMainHand(off);
            } else if (slot >= 36 && slot <= 39) {
                ItemStack armor = switch (slot) {
                    case 36 -> inventory.getBoots();
                    case 37 -> inventory.getLeggings();
                    case 38 -> inventory.getChestplate();
                    default -> inventory.getHelmet();
                };
                if (armor == null || armor.getType().isAir()) {
                    throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "盔甲槽 " + slot + " 是空的",
                            JsonRpc.object("reason", "slot_empty", "slot", slot));
                }
                int free = inventory.firstEmpty();
                if (free < 0) {
                    throw new JsonRpc.RpcException(JsonRpc.BUSY, "背包满了，脱不下来",
                            JsonRpc.object("reason", "inventory_full"));
                }
                inventory.setItem(free, armor);
                switch (slot) {
                    case 36 -> inventory.setBoots(null);
                    case 37 -> inventory.setLeggings(null);
                    case 38 -> inventory.setChestplate(null);
                    default -> inventory.setHelmet(null);
                }
            } else {
                if (slot < 0 || slot >= inventory.getSize()) {
                    throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "槽位不合法：" + slot,
                            JsonRpc.object("reason", "bad_slot", "slot", slot));
                }
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir()) {
                    throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "槽位 " + slot + " 是空的",
                            JsonRpc.object("reason", "slot_empty", "slot", slot));
                }
                material = stack.getType();
            }
        }
        if (material != null && !ItemUtil.equip(bot, material)) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_HOLDING, "背包里没有 " + material.name(),
                    JsonRpc.object("reason", "item_not_found",
                            "item", material.name().toLowerCase(Locale.ROOT)));
        }
        JsonObject result = new JsonObject();
        result.add("held", ItemUtil.describe(bot.getInventory().getItemInMainHand(),
                bot.getInventory().getHeldItemSlot()));
        return result;
    }

    private JsonElement eat(AIBody body, Bot bot, JsonObject params) {
        // 规格 §3.2：默认 tier=normal（只吃普通食物，传统假人的贵重规则不变）；
        // 显式 tier=any 才允许动金苹果类；item/foods 可指定具体物品。
        String tier = Json.getString(params, "tier", "normal");
        boolean allowValuable = "any".equalsIgnoreCase(tier);
        String itemName = Json.getString(params, "item", null);
        Material requested = null;
        if (itemName != null && !itemName.isBlank()) {
            requested = ItemUtil.match(itemName);
            if (requested == null) {
                throw badItem(itemName);
            }
            if (!requested.isEdible()) {
                throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST,
                        requested.name() + " 不是食物",
                        JsonRpc.object("reason", "bad_item_name", "normalized",
                                requested.name().toLowerCase(Locale.ROOT)));
            }
        }
        java.util.Set<Material> exclude = new java.util.LinkedHashSet<>();
        if (params.has("foods") && params.get("foods").isJsonArray()) {
            for (JsonElement element : params.getAsJsonArray("foods")) {
                Material food = ItemUtil.match(element.getAsString());
                if (food != null) {
                    exclude.add(food);
                }
            }
        }
        if (bot.getFoodLevel() >= 20 && requested == null) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED,
                    "现在不饿（饥饿值 20），不浪费食物；真要吃得显式指定 item",
                    JsonRpc.object("reason", "not_hungry", "food", bot.getFoodLevel()));
        }
        int slot;
        if (requested != null) {
            slot = ItemUtil.findFoodSlot(bot.getInventory(), requested);
            if (slot < 0) {
                throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND,
                        "背包里没有 " + requested.name(),
                        JsonRpc.object("reason", "item_not_found",
                                "item", requested.name().toLowerCase(Locale.ROOT)));
            }
            if (FoodUtil.isValuable(requested) && !allowValuable) {
                throw new JsonRpc.RpcException(JsonRpc.DENIED,
                        "贵重食物需要 tier=\"any\" 才吃",
                        JsonRpc.object("reason", "valuable_food", "item",
                                requested.name().toLowerCase(Locale.ROOT)));
            }
        } else if (exclude.isEmpty()) {
            slot = ItemUtil.bestFoodSlot(bot.getInventory(), allowValuable, null);
        } else {
            slot = -1;
            // 候选清单模式：按清单顺序找第一个背包里有的
            for (Material candidate : exclude) {
                if (!candidate.isEdible()) {
                    continue;
                }
                if (FoodUtil.isValuable(candidate) && !allowValuable) {
                    continue;
                }
                slot = ItemUtil.findFoodSlot(bot.getInventory(), candidate);
                if (slot >= 0) {
                    break;
                }
            }
        }
        if (slot < 0) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "背包里没有能吃的",
                    JsonRpc.object("reason", "item_not_found"));
        }
        ItemStack stack = bot.getInventory().getItem(slot);
        ItemStack started = body.controller().startEating(bot, slot);
        if (started == null) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED, "现在吃不了（可能已经吃饱了）",
                    JsonRpc.object("reason", "not_hungry"));
        }
        int remaining = stack == null ? 0 : Math.max(0, stack.getAmount() - 1);
        return JsonRpc.object("eating", ItemUtil.nameOf(started),
                "item", ItemUtil.nameOf(started),
                "slot", slot,
                "remaining", remaining);
    }

    private static JsonRpc.RpcException badItem(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace("minecraft:", "").replace(' ', '_');
        return new JsonRpc.RpcException(JsonRpc.BAD_REQUEST,
                "无法识别的物品名: " + raw + "（归一化后 " + normalized + "）",
                JsonRpc.object("reason", "bad_item_name", "normalized", normalized));
    }

    /** 贵重食物判定（与 buddy 侧 FoodRules 的规则保持一致）。 */
    private static final class FoodUtil {
        private FoodUtil() {
        }

        static boolean isValuable(Material material) {
            return material == Material.GOLDEN_APPLE
                    || material == Material.ENCHANTED_GOLDEN_APPLE
                    || material == Material.GOLDEN_CARROT;
        }
    }

    private JsonElement drop(AIBody body, Bot bot, JsonObject params) {
        if (!body.configBoolean("allow-drop-item", body.manager().config().allowDropItem())) {
            throw new JsonRpc.RpcException(JsonRpc.DENIED, "服务器禁止躯壳丢物品");
        }
        int slot = Json.getInt(params, "slot", -1);
        if (slot < 0 || slot >= bot.getInventory().getSize()) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "槽位不合法");
        }
        ItemStack stack = bot.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_FOUND, "槽位 " + slot + " 是空的");
        }
        int count = Math.max(1, Math.min(stack.getAmount(), Json.getInt(params, "count", 1)));
        ItemStack drop = stack.clone();
        drop.setAmount(count);
        int remain = stack.getAmount() - count;
        if (remain <= 0) {
            bot.getInventory().setItem(slot, null);
        } else {
            ItemStack keep = stack.clone();
            keep.setAmount(remain);
            bot.getInventory().setItem(slot, keep);
        }
        bot.getWorld().dropItemNaturally(bot.getEyeLocation(), drop);
        return JsonRpc.object("dropped", ItemUtil.nameOf(drop), "count", count);
    }

    private JsonElement jump(Bot bot) {
        plugin.bots().stopActions(bot, JumpAction.class);
        JumpAction action = JumpAction.create();
        action.setStartDelayTick(1);
        action.setDoIntervalTick(1);
        action.setDoNumber(1);
        bot.addAction(action);
        return JsonRpc.object("ok", true);
    }

    private JsonElement chat(AIBody body, Bot bot, JsonObject params) {
        AgentSession session = body.session();
        if (session != null && !session.allowChat(config.chatCooldownMs())) {
            throw new JsonRpc.RpcException(JsonRpc.RATE_LIMITED, "聊天太快了");
        }
        String raw = Json.getString(params, "text", "");
        StringBuilder builder = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '§' || c < 0x20) {
                continue;
            }
            builder.append(c);
            if (builder.length() >= 256) {
                break;
            }
        }
        bot.chat(builder.toString());
        return JsonRpc.object("ok", true);
    }

    // ------------------------------------------------------------------ 感知细节

    private JsonElement blockAt(AIBody body, JsonObject params) {
        World world = body.world();
        if (world == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        org.bukkit.block.Block block = world.getBlockAt(Json.getInt(params, "x", 0),
                Json.getInt(params, "y", 0), Json.getInt(params, "z", 0));
        JsonObject object = new JsonObject();
        object.addProperty("id", block.getType().name().toLowerCase(Locale.ROOT));
        object.addProperty("meta", 0);
        object.addProperty("hardness", block.getType().getHardness());
        object.addProperty("light", block.getLightLevel());
        object.addProperty("passable", block.isPassable());
        object.addProperty("solid", block.getType().isSolid());
        return object;
    }

    private JsonElement blocks(AIBody body, JsonObject params) {
        World world = body.world();
        if (world == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        int x1 = Json.getInt(params, "x1", 0);
        int y1 = Json.getInt(params, "y1", 0);
        int z1 = Json.getInt(params, "z1", 0);
        int x2 = Json.getInt(params, "x2", 0);
        int y2 = Json.getInt(params, "y2", 0);
        int z2 = Json.getInt(params, "z2", 0);
        int sizeX = Math.abs(x1 - x2) + 1;
        int sizeY = Math.abs(y1 - y2) + 1;
        int sizeZ = Math.abs(z1 - z2) + 1;
        if ((long) sizeX * sizeY * sizeZ > 16 * 16 * 16) {
            throw new JsonRpc.RpcException(JsonRpc.OUT_OF_RANGE, "体积超过 16³");
        }
        int limit = Math.max(1, Math.min(512, Json.getInt(params, "limit", 512)));
        Material filter = ItemUtil.match(params.has("filter") && params.get("filter").isJsonPrimitive()
                ? params.get("filter").getAsString() : null);
        JsonArray array = new JsonArray();
        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX && array.size() < limit; x++) {
            for (int y = minY; y <= maxY && array.size() < limit; y++) {
                for (int z = minZ; z <= maxZ && array.size() < limit; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type.isAir()) {
                        continue;
                    }
                    if (filter != null && type != filter) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);
                    entry.addProperty("id", type.name().toLowerCase(Locale.ROOT));
                    array.add(entry);
                }
            }
        }
        return array;
    }

    private JsonElement findBlocks(AIBody body, JsonObject params) {
        World world = body.world();
        Location center = body.location();
        if (world == null || center == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        Material material = ItemUtil.match(Json.getString(params, "id", ""));
        if (material == null) {
            throw new JsonRpc.RpcException(JsonRpc.BAD_REQUEST, "findBlock 需要 id");
        }
        int radius = Math.max(1, Math.min(32, Json.getInt(params, "radius", 16)));
        int max = Math.max(1, Math.min(32, Json.getInt(params, "max", 8)));
        JsonArray array = new JsonArray();
        for (int dx = -radius; dx <= radius && array.size() < max; dx++) {
            for (int dy = -radius; dy <= radius && array.size() < max; dy++) {
                for (int dz = -radius; dz <= radius && array.size() < max; dz++) {
                    int x = center.getBlockX() + dx;
                    int y = center.getBlockY() + dy;
                    int z = center.getBlockZ() + dz;
                    if (world.getBlockAt(x, y, z).getType() != material) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);
                    entry.addProperty("dist", VecUtil.round(center.distance(new Location(world, x, y, z))));
                    array.add(entry);
                }
            }
        }
        return array;
    }

    private JsonElement players(AIBody body, JsonObject params) {
        Bot bot = body.bot();
        JsonArray array = new JsonArray();
        if (bot == null || !bot.isValid()) {
            return array;
        }
        double radius = Json.getDouble(params, "radius", 48.0);
        double close = body.manager().config().closeRadius();
        for (Player player : bot.getWorld().getPlayers()) {
            if (player instanceof Bot) {
                continue;
            }
            double distance = player.getLocation().distance(bot.getLocation());
            if (distance > radius) {
                continue;
            }
            // 与 t:"P" 用同一套短字段（P1-2：两份数据字段名必须一致）
            JsonObject entry = new JsonObject();
            entry.addProperty("n", player.getName());
            entry.add("p", VecUtil.toArray(player.getLocation()));
            entry.addProperty("d", VecUtil.round(distance));
            if (distance <= close) {
                entry.addProperty("hp", VecUtil.round(player.getHealth()));
                entry.addProperty("held", ItemUtil.nameOf(player.getInventory().getItemInMainHand()));
                entry.addProperty("look", VecUtil.lookingAt(player, bot.getLocation()) ? 1 : 0);
                entry.addProperty("gm", player.getGameMode() == null ? "SURVIVAL" : player.getGameMode().name());
            }
            array.add(entry);
        }
        return array;
    }

    /** 目标点埋在实心方块里时向上找可站立位置（P1-7）。 */
    private static Location adjustToStandable(Location target) {
        org.bukkit.World world = target.getWorld();
        if (world == null) {
            return target;
        }
        Location probe = target.clone();
        for (int i = 0; i < 4; i++) {
            org.bukkit.block.Block feet = world.getBlockAt(probe.getBlockX(),
                    (int) Math.floor(probe.getY()), probe.getBlockZ());
            org.bukkit.block.Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
            org.bukkit.block.Block below = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (feet.isPassable() && head.isPassable() && !below.isPassable() && below.getType().isSolid()) {
                return probe;
            }
            probe.add(0, 1, 0);
        }
        return probe;
    }

    private JsonElement canReach(AIBody body, JsonObject params) {
        Bot bot = body.bot();
        Location location = body.location();
        if (bot == null || !bot.isValid() || location == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        VecUtil.Point point = pointOf(params);
        double distance = point.distanceTo(location);
        boolean ok = distance <= body.manager().config().operateRadius()
                && bot.hasLineOfSight(new Location(bot.getWorld(), point.x(), point.y(), point.z()));
        return JsonRpc.object("ok", ok, "dist", VecUtil.round(distance));
    }

    // ------------------------------------------------------------------ 工具

    private AIBody attached(AgentSession session) {
        if (session.bodies().isEmpty()) {
            return null;
        }
        return manager.byName(session.bodies().iterator().next());
    }

    private AIBody requireBody(AIBody body) {
        if (body == null) {
            throw new JsonRpc.RpcException(JsonRpc.NOT_ATTACHED, "请先 body.attach");
        }
        return body;
    }

    private Location locationOf(AIBody body, JsonObject params) {
        World world = body.world();
        if (world == null) {
            throw new JsonRpc.RpcException(JsonRpc.BODY_OFFLINE, "躯壳不在世界内");
        }
        VecUtil.Point point = pointOf(params);
        Location target = new Location(world, point.x(), point.y(), point.z());
        Location current = body.location();
        if (current != null && current.distance(target) > body.manager().config().operateRadius()) {
            throw new JsonRpc.RpcException(JsonRpc.OUT_OF_RANGE, "目标超出操作半径 "
                    + body.manager().config().operateRadius());
        }
        return target;
    }

    private static VecUtil.Point pointOf(JsonObject params) {
        return new VecUtil.Point(Json.getDouble(params, "x", 0),
                Json.getDouble(params, "y", 0), Json.getDouble(params, "z", 0));
    }

    /** 供 BodyManager 推送帧用。 */
    public void send(AgentSession session, String frame) {
        try {
            WebSocket connection = session.connection();
            if (connection != null && connection.isOpen()) {
                connection.send(frame);
            }
        } catch (RuntimeException exception) {
            plugin.log().warn("躯壳", "发送失败：" + exception.getMessage());
        }
    }

    private void close(WebSocket connection, int code, String reason) {
        try {
            if (connection != null && connection.isOpen()) {
                connection.close(code, reason);
            }
        } catch (RuntimeException ignored) {
            // 忽略
        }
    }

    public void shutdown() {
        try {
            stop(1000);
        } catch (Throwable throwable) {
            plugin.log().warn("躯壳", "关闭 Agent 服务失败：" + throwable.getMessage());
        }
        sessions.clear();
    }

    /** 供 /ai body take 使用：踢掉占用者。 */
    public void kick(AIBody body) {
        AgentSession session = body.session();
        if (session == null) {
            return;
        }
        body.session(null);
        session.bodies().remove(body.name());
        close(session.connection(), 1000, "taken over by admin");
    }

    /** 广播给所有连接（目前只用于关服通知）。 */
    public void broadcast(String frame) {
        for (AgentSession session : sessions.values()) {
            if (session.authenticated()) {
                send(session, frame);
            }
        }
    }
}
