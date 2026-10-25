package com.leavesdeepseek.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leavesdeepseek.agent.task.AgentTask;import com.leavesdeepseek.agent.util.ItemUtil;
import com.leavesdeepseek.agent.util.VecUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 感知缓存与分层推送（规格 §7.2 / §8）。
 *
 * <p>所有世界读取只在主线程这里发生；WS 线程只读已生成的 JSON 字符串。</p>
 */
public final class SensorHub {

    private final AIBody body;
    private final Map<String, Long> nearPlayers = new HashMap<>();

    private int motionCounter;
    private int terrainCounter;
    private int companionCounter;
    private int taskCounter;
    private String lastInventoryJson;
    private String lastDimension = "";
    private int lastFood = -1;
    private int lastFreeSlots = -1;
    private double lastHealth = -1.0;
    /** 断连期间收到的聊天（attach 时回放最近 20 条，见 P2-4）。 */
    private final java.util.Deque<JsonObject> chatBuffer = new java.util.ArrayDeque<>();
    /** 上一次推 ev.damage 的时刻：兜底逻辑要跳过它，避免一次伤害记两条。 */
    private long lastDamageEventAt;

    public SensorHub(AIBody body) {
        this.body = body;
    }

    public AIBody body() {
        return body;
    }

    /** 20 Hz 主循环调用，按配置的频率推送。 */
    public void tick() {
        if (body.state() == AIBody.State.OFFLINE) {
            return;
        }
        // 没连接时绝不能推进基线：否则「全量 I 帧」在 attach 之前就被消耗掉，
        // 之后背包没变化 → 差分永远是空 → 客户端永远收不到 t:"I"（这就是 P0-1）。
        if (body.session() == null) {
            return;
        }
        AgentConfig config = body.manager().config();
        motionCounter++;
        if (motionCounter >= Math.max(1, 20 / config.motionHz())) {
            motionCounter = 0;
            push(motionFrame());
        }
        terrainCounter++;
        if (terrainCounter >= Math.max(1, 20 / config.terrainHz())) {
            terrainCounter = 0;
            push(terrainFrame());
        }
        companionCounter++;
        if (companionCounter >= Math.max(1, 20 / config.companionHz())) {
            companionCounter = 0;
            push(companionFrame());
        }
        taskCounter++;
        if (taskCounter >= Math.max(1, 20 / config.taskHz())) {
            taskCounter = 0;
            push(taskFrame());
        }
        push(inventoryFrame());
    }

    private void push(String frame) {
        if (frame == null || frame.isEmpty()) {
            return;
        }
        body.manager().push(body, frame);
    }

    /** attach 时调用：清掉所有「上一连接」的基线，下一 tick 会推全量。 */
    public void resetBaselines() {
        lastInventoryJson = null;
        lastFreeSlots = -1;
        lastDimension = "";
        lastFood = -1;
        lastHealth = -1.0;
        motionCounter = 0;
        terrainCounter = 0;
        companionCounter = 0;
        taskCounter = 0;
    }

    /**
     * 立刻推一帧 {@code full:true} 的背包（attach 后调用，不依赖下一 tick 的差分）。
     *
     * <p>之前只 reset 基线再等 tick，实测客户端仍然收不到全量；这里直接生成并发送，
     * 并打日志确认到底推没推出去。</p>
     */
    public void pushFullInventory() {
        lastInventoryJson = null;
        String frame = inventoryFrame();
        if (frame == null) {
            body.manager().plugin().log().warn("躯壳",
                    "[SENSOR-I] pushFull failed reason=frame_null attached="
                            + (body.session() != null) + " online=" + body.online());
            return;
        }
        push(frame);
        body.manager().plugin().log().info("躯壳",
                "[SENSOR-I] pushFull reason=attach bytes=" + frame.length()
                        + " attached=" + (body.session() != null));
    }

    // ---------------------------------------------------------------- snapshot

    public JsonObject snapshot() {
        Bot bot = body.bot();
        JsonObject object = new JsonObject();
        object.addProperty("tick", body.manager().currentTick());
        object.addProperty("name", body.name());
        object.addProperty("state", body.state().name().toLowerCase(Locale.ROOT));
        if (bot == null || !bot.isValid()) {
            object.addProperty("offline", true);
            return object;
        }
        Location location = bot.getLocation();
        object.add("pos", VecUtil.toArray(location));
        object.addProperty("yaw", VecUtil.round(location.getYaw()));
        object.addProperty("pitch", VecUtil.round(location.getPitch()));
        object.add("vel", VecUtil.toArray(bot.getVelocity()));
        object.addProperty("onGround", bot.isOnGround());
        object.addProperty("inWater", bot.isInWater());
        object.addProperty("dimension", body.dimension());
        object.addProperty("health", VecUtil.round(bot.getHealth()));
        object.addProperty("maxHealth", VecUtil.round(bot.getMaxHealth()));
        object.addProperty("food", bot.getFoodLevel());
        object.addProperty("saturation", VecUtil.round(bot.getSaturation()));
        object.addProperty("air", bot.getRemainingAir());
        object.addProperty("xp", bot.getTotalExperience());
        object.addProperty("level", bot.getLevel());
        object.addProperty("gamemode", bot.getGameMode() == null ? "SURVIVAL" : bot.getGameMode().name());

        World world = location.getWorld();
        if (world != null) {
            object.addProperty("worldTime", world.getTime());
            object.addProperty("weather", world.hasStorm()
                    ? (world.isThundering() ? "thunder" : "rain") : "clear");
            Block feet = location.getBlock();
            object.addProperty("lightFeet", feet.getLightLevel());
            object.addProperty("lightEye", location.clone().add(0, 1.62, 0).getBlock().getLightLevel());
            object.addProperty("canSeeSky", world.getHighestBlockYAt(location) <= location.getBlockY());
        }

        ItemStack held = bot.getInventory().getItemInMainHand();
        JsonObject heldObject = ItemUtil.describe(held, bot.getInventory().getHeldItemSlot());
        object.addProperty("hotbarSlot", bot.getInventory().getHeldItemSlot());
        object.add("held", heldObject);

        JsonArray armor = new JsonArray();
        armor.add(ItemUtil.describe(bot.getInventory().getHelmet(), 39));
        armor.add(ItemUtil.describe(bot.getInventory().getChestplate(), 38));
        armor.add(ItemUtil.describe(bot.getInventory().getLeggings(), 37));
        armor.add(ItemUtil.describe(bot.getInventory().getBoots(), 36));
        object.add("armor", armor);

        JsonArray effects = new JsonArray();
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", effectId(effect.getType()));
            entry.addProperty("amplifier", effect.getAmplifier());
            entry.addProperty("durationSec", effect.getDuration() / 20);
            effects.add(entry);
        }
        object.add("effects", effects);

        AgentTask current = body.controller().current();
        if (current != null) {
            JsonObject task = new JsonObject();
            task.addProperty("taskId", current.id());
            task.addProperty("type", current.type());
            task.addProperty("progress", VecUtil.round(current.progress()));
            task.addProperty("stuck", current.stuck());
            task.addProperty("etaMs", current.etaMs());
            if (current.target() != null) {
                task.add("target", VecUtil.toArray(current.target()));
            }
            object.add("task", task);
        }
        object.addProperty("queued", Math.max(0, body.controller().snapshot().size() - 1));
        if (body.anchor() != null) {
            object.add("anchor", VecUtil.toArray(body.anchor()));
        }
        return object;
    }

    // ------------------------------------------------------------ 推送帧

    private String motionFrame() {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return null;
        }
        Location location = bot.getLocation();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "M");
        frame.addProperty("tick", body.manager().currentTick());
        frame.add("pos", VecUtil.toArray(location));
        frame.add("vel", VecUtil.toArray(bot.getVelocity()));
        frame.addProperty("g", bot.isOnGround() ? 1 : 0);
        frame.addProperty("hp", VecUtil.round(bot.getHealth()));
        frame.addProperty("fd", bot.getFoodLevel());
        frame.addProperty("sat", VecUtil.round(bot.getSaturation()));
        frame.addProperty("in", bot.isInWater() ? 1 : 0);
        String dimension = body.dimension();
        if (!dimension.equals(lastDimension)) {
            lastDimension = dimension;
            frame.addProperty("dim", dimension);
        }
        int food = bot.getFoodLevel();
        lastFood = food;
        // 兜底：血量掉了但没收到 ev.damage（爆炸/近战事件漏掉时）也要告诉 AI。
        // 500ms 内已经推过真实事件就跳过，避免一次伤害记两条。
        double hp = bot.getHealth();
        boolean recentDamageEvent = System.currentTimeMillis() - lastDamageEventAt < 500L;
        if (!recentDamageEvent && lastHealth >= 0 && lastHealth - hp > 0.4) {
            double lost = VecUtil.round(lastHealth - hp);
            body.manager().plugin().log().info("躯壳",
                    "[SENSOR-DMG] fallback hp " + VecUtil.round(lastHealth) + " -> " + VecUtil.round(hp)
                            + " (-" + lost + ")");
            event("ev.damage", JsonRpc.object("amount", lost, "cause", "unknown",
                    "source", "unknown", "sourceName", "", "hpAfter", VecUtil.round(hp),
                    "pos", VecUtil.toArray(location)));
        }
        lastHealth = hp;
        return frame.toString();
    }

    private String inventoryFrame() {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return null;
        }
        JsonObject frame = new JsonObject();
        JsonArray changes = new JsonArray();
        StringBuilder signature = new StringBuilder();
        ItemStack[] contents = bot.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            signature.append(slot).append(':').append(ItemUtil.nameOf(stack))
                    .append(':').append(stack == null ? 0 : stack.getAmount()).append(';');
        }
        String current = signature.toString();
        if (lastInventoryJson != null && current.equals(lastInventoryJson)) {
            return null;
        }
        if (lastInventoryJson == null) {
            lastInventoryJson = current;
            body.manager().plugin().log().info("躯壳",
                    "[SENSOR-I] full=true slots=" + contents.length + " reason=first_frame");
            frame.addProperty("t", "I");
            frame.addProperty("full", true);
            frame.addProperty("free", ItemUtil.freeSlots(bot.getInventory()));
            frame.addProperty("hotbarSlot", bot.getInventory().getHeldItemSlot());
            JsonArray all = new JsonArray();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack != null && !stack.getType().isAir()) {
                    all.add(ItemUtil.describe(stack, slot));
                }
            }
            frame.add("chg", all);
            return frame.toString();
        }
        Map<Integer, String> before = parseSignature(lastInventoryJson);
        lastInventoryJson = current;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            String now = ItemUtil.nameOf(stack) + ":" + (stack == null ? 0 : stack.getAmount());
            String old = before.getOrDefault(slot, "air:0");
            if (!now.equals(old)) {
                changes.add(ItemUtil.describe(stack, slot));
            }
        }
        if (changes.isEmpty()) {
            return null;
        }
        body.manager().plugin().log().info("躯壳",
                "[SENSOR-I] full=false chg=" + changes.size() + " reason=inventory_changed");
        frame.addProperty("t", "I");
        frame.add("chg", changes);
        int free = ItemUtil.freeSlots(bot.getInventory());
        frame.addProperty("full", free <= 0);
        frame.addProperty("free", free);
        frame.addProperty("hotbarSlot", bot.getInventory().getHeldItemSlot());
        if (free <= 0 && lastFreeSlots != 0) {
            event("ev.inventoryFull", JsonRpc.object("freeSlots", 0));
        }
        lastFreeSlots = free;
        return frame.toString();
    }

    private static Map<Integer, String> parseSignature(String signature) {
        Map<Integer, String> map = new HashMap<>();
        for (String part : signature.split(";")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] pieces = part.split(":");
            if (pieces.length < 3) {
                continue;
            }
            try {
                map.put(Integer.parseInt(pieces[0]), pieces[1] + ":" + pieces[2]);
            } catch (NumberFormatException ignored) {
                // 忽略坏数据
            }
        }
        return map;
    }

    /** {@code sense.terrain} 直接返回同一份地形语义。 */
    public JsonObject terrainJson() {
        String raw = terrainFrame();
        if (raw == null) {
            return new JsonObject();
        }
        return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
    }

    private String terrainFrame() {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return null;
        }
        World world = bot.getWorld();
        Location location = bot.getLocation();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "T");
        JsonObject six = new JsonObject();
        six.addProperty("u", walk(location, 0, 1, 0));
        six.addProperty("d", walk(location, 0, -1, 0));
        six.addProperty("n", walk(location, 0, 0, -1));
        six.addProperty("s", walk(location, 0, 0, 1));
        six.addProperty("e", walk(location, 1, 0, 0));
        six.addProperty("w", walk(location, -1, 0, 0));
        frame.add("six", six);
        Block under = location.clone().subtract(0, 1, 0).getBlock();
        frame.addProperty("under", under.getType().name().toLowerCase(Locale.ROOT));
        frame.addProperty("lf", location.getBlock().getLightLevel());
        frame.addProperty("le", location.clone().add(0, 1.62, 0).getBlock().getLightLevel());

        JsonArray landmarks = new JsonArray();
        JsonArray hazards = new JsonArray();
        collectMarks(body, world, location, landmarks, hazards);
        frame.add("landmark", landmarks);
        frame.add("hazard", hazards);
        frame.addProperty("water", bot.isInWater() ? 1 : 0);
        frame.addProperty("lava", isNear(world, location, Material.LAVA) ? 1 : 0);
        frame.addProperty("fall", 0);
        frame.addProperty("slope", 0.0);
        return frame.toString();
    }

    private void collectMarks(AIBody body, World world, Location center, JsonArray landmarks, JsonArray hazards) {
        int radius = Math.min(8, Math.max(3, body.manager().config().landmarkMax() + 2));
        int max = body.manager().config().landmarkMax();
        List<String> found = new ArrayList<>();
        List<String> danger = new ArrayList<>();
        boolean waterBreathing = body.bot() != null
                && body.bot().hasPotionEffect(PotionEffectType.WATER_BREATHING);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy,
                            center.getBlockZ() + dz);
                    String type = classify(block.getType());
                    if (type == null) {
                        continue;
                    }
                    String entry = type + "@" + dx + "," + dy + "," + dz;
                    if ("lava".equals(type) || "cactus".equals(type) || "fire".equals(type)) {
                        danger.add(entry);
                    } else if ("water".equals(type)) {
                        if (!waterBreathing) {
                            danger.add(entry);
                        } else {
                            found.add(entry);
                        }
                    } else {
                        found.add(entry);
                    }
                }
            }
        }
        found.sort(Comparator.comparingInt(SensorHub::distanceOf));
        danger.sort(Comparator.comparingInt(SensorHub::distanceOf));
        // 脚下悬空 > 3 格也算危险（规格 §8.3）
        int drop = 0;
        for (int dy = 1; dy <= 5; dy++) {
            Block below = world.getBlockAt(center.getBlockX(), center.getBlockY() - dy, center.getBlockZ());
            if (!below.getType().isAir() && !below.isLiquid()) {
                break;
            }
            drop++;
        }
        if (drop > 3) {
            danger.add(0, "fall@0," + (-drop) + ",0");
        }
        for (int i = 0; i < found.size() && landmarks.size() < max; i++) {
            landmarks.add(found.get(i));
        }
        for (int i = 0; i < danger.size() && hazards.size() < max; i++) {
            hazards.add(danger.get(i));
        }
    }

    private static int distanceOf(String mark) {
        int at = mark.indexOf('@');
        if (at < 0) {
            return 0;
        }
        String[] parts = mark.substring(at + 1).split(",");
        if (parts.length < 3) {
            return 0;
        }
        try {
            int dx = Integer.parseInt(parts[0]);
            int dy = Integer.parseInt(parts[1]);
            int dz = Integer.parseInt(parts[2]);
            return dx * dx + dy * dy + dz * dz;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String classify(Material material) {
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return null;
        }
        String name = material.name();
        if (name.endsWith("_LOG") || name.equals("LOG") || name.endsWith("_WOOD")) {
            return "tree";
        }
        if (material == Material.CRAFTING_TABLE) {
            return "crafting_table";
        }
        if (material == Material.FURNACE || material == Material.BLAST_FURNACE
                || material == Material.SMOKER) {
            return "furnace";
        }
        if (material == Material.CHEST || material == Material.TRAPPED_CHEST
                || material == Material.BARREL) {
            return "chest";
        }
        if (name.endsWith("_DOOR")) {
            return "door";
        }
        if (material == Material.LADDER) {
            return "ladder";
        }
        if (material == Material.WATER) {
            return "water";
        }
        if (material == Material.LAVA) {
            return "lava";
        }
        if (name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS) {
            return "ore";
        }
        if (name.endsWith("_BED")) {
            return "bed";
        }
        if (material == Material.TORCH || material == Material.WALL_TORCH
                || material == Material.SOUL_TORCH) {
            return "torch";
        }
        if (material == Material.WHEAT || material == Material.CARROTS
                || material == Material.POTATOES || material == Material.BEETROOTS) {
            return "crop";
        }
        if (material == Material.CACTUS) {
            return "cactus";
        }
        if (material == Material.FIRE || material == Material.SOUL_FIRE) {
            return "fire";
        }
        return null;
    }

    private String companionFrame() {
        Bot bot = body.bot();
        if (bot == null || !bot.isValid()) {
            return null;
        }
        AgentConfig config = body.manager().config();
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "P");
        JsonArray players = new JsonArray();
        for (Player player : bot.getWorld().getPlayers()) {
            if (player instanceof Bot) {
                continue;
            }
            double distance = player.getLocation().distance(bot.getLocation());
            if (distance > config.nearbyRadius()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("n", player.getName());
            entry.add("p", VecUtil.toArray(player.getLocation()));
            entry.addProperty("d", VecUtil.round(distance));
            if (distance <= config.closeRadius()) {
                entry.addProperty("hp", VecUtil.round(player.getHealth()));
                entry.addProperty("held", ItemUtil.nameOf(player.getInventory().getItemInMainHand()));
                entry.addProperty("look", VecUtil.lookingAt(player, bot.getLocation()) ? 1 : 0);
                entry.addProperty("gm", player.getGameMode() == null ? "SURVIVAL" : player.getGameMode().name());
            }
            players.add(entry);
            Long since = nearPlayers.put(player.getName(), 0L);
            if (since == null && distance <= config.closeRadius()) {
                event("ev.playerNear", JsonRpc.object("name", player.getName(), "dist", VecUtil.round(distance)));
            }
        }
        double radius = config.nearbyRadius();
        nearPlayers.entrySet().removeIf(entry -> {
            Player player = org.bukkit.Bukkit.getPlayerExact(entry.getKey());
            if (player == null || !player.isOnline() || !player.getWorld().equals(bot.getWorld())
                    || player.getLocation().distance(bot.getLocation()) > radius) {
                event("ev.playerLeave", JsonRpc.object("name", entry.getKey()));
                return true;
            }
            return false;
        });
        frame.add("ps", players);
        return frame.toString();
    }

    private String taskFrame() {
        AgentTask current = body.controller().current();
        if (current == null) {
            return null;
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("t", "K");
        frame.addProperty("id", current.id());
        frame.addProperty("p", VecUtil.round(current.progress()));
        Location location = body.location();
        if (location != null) {
            frame.add("pos", VecUtil.toArray(location));
        }
        frame.addProperty("stuck", current.stuck());
        frame.addProperty("eta", Math.round(current.etaMs() / 100.0) / 10.0);
        return frame.toString();
    }

    // -------------------------------------------------------------- 事件

    public void event(String method, JsonObject params) {
        if ("ev.chat".equals(method)) {
            synchronized (chatBuffer) {
                chatBuffer.addLast(params);
                while (chatBuffer.size() > 20) {
                    chatBuffer.removeFirst();
                }
            }
        }
        if ("ev.damage".equals(method)) {
            lastDamageEventAt = System.currentTimeMillis();
        }
        body.manager().emitEvent(body, JsonRpc.notify(method, params));
    }

    /** attach 时把断连期间攒下的聊天补发（带时间戳）。 */
    public void replayChat() {
        java.util.List<JsonObject> copy;
        synchronized (chatBuffer) {
            copy = new java.util.ArrayList<>(chatBuffer);
            chatBuffer.clear();
        }
        for (JsonObject params : copy) {
            JsonObject replay = params.deepCopy();
            replay.addProperty("replay", true);
            body.manager().emitEvent(body, JsonRpc.notify("ev.chat", replay));
        }
    }

    /** 原版药水 id：damage_resistance -> resistance（P2-1）。 */
    private static String effectId(PotionEffectType type) {
        String name = type.getName().toLowerCase(Locale.ROOT);
        if (name.equals("damage_resistance")) {
            return "resistance";
        }
        if (name.equals("jump_boost")) {
            return "jump_boost";
        }
        return name;
    }

    public void taskStarted(AgentTask task) {
        event("task.started", JsonRpc.object("taskId", task.id(), "type", task.type()));
    }

    public void taskProgress(AgentTask task) {
        JsonObject params = JsonRpc.object("taskId", task.id(),
                "progress", VecUtil.round(task.progress()),
                "stuck", task.stuck(),
                "etaMs", task.etaMs());
        Location location = body.location();
        if (location != null) {
            params.add("pos", VecUtil.toArray(location));
        }
        event("task.progress", params);
    }

    public void taskDone(AgentTask task) {
        event("task.done", JsonRpc.object("taskId", task.id(), "ok", true,
                "elapsedMs", task.elapsedMs()));
    }

    public void taskFailed(AgentTask task, String detail) {
        String code = JsonRpc.nameOf(task.failCode());
        if ("CANCELLED".equals(detail) || "NO_PROGRESS".equals(detail)) {
            code = detail;
        }
        JsonObject params = JsonRpc.object("taskId", task.id(), "code", code,
                "detail", detail == null ? "" : detail);
        if (task.extraData() != null) {
            params.add("data", task.extraData());
        }
        event("task.failed", params);
    }

    public void stateEvent(AIBody.State state, String reason) {
        event("ev.state", JsonRpc.object("state", state.name().toLowerCase(Locale.ROOT), "reason", reason));
    }

    // -------------------------------------------------------------- 工具

    private static int walk(Location from, int dx, int dy, int dz) {
        World world = from.getWorld();
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (int step = 1; step <= 8; step++) {
            Block block = world.getBlockAt(from.getBlockX() + dx * step,
                    from.getBlockY() + dy * step, from.getBlockZ() + dz * step);
            if (block.isPassable()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static boolean isNear(World world, Location location, Material material) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.getBlockAt(location.getBlockX() + dx, location.getBlockY() + dy,
                            location.getBlockZ() + dz).getType() == material) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 对外提供的实体列表（sense.entity）。 */
    public JsonArray entities(double radius, String kind) {
        Bot bot = body.bot();
        JsonArray array = new JsonArray();
        if (bot == null || !bot.isValid()) {
            return array;
        }
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                continue;
            }
            if (entity instanceof Bot || entity instanceof Item) {
                continue;
            }
            boolean hostile = entity instanceof Monster;
            if ("hostile".equals(kind) && !hostile) {
                continue;
            }
            if ("passive".equals(kind) && hostile) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", entity.getEntityId());
            entry.addProperty("type", entity.getType().name().toLowerCase(Locale.ROOT));
            entry.add("pos", VecUtil.toArray(entity.getLocation()));
            entry.addProperty("dist", VecUtil.round(entity.getLocation().distance(bot.getLocation())));
            entry.addProperty("health", VecUtil.round(living.getHealth()));
            if (entity instanceof Player player) {
                entry.addProperty("lookingAtMe", VecUtil.lookingAt(player, bot.getLocation()));
            }
            entry.addProperty("lineOfSight", bot.hasLineOfSight(entity));
            array.add(entry);
        }
        return array;
    }

    /** sense.item */
    public JsonArray items(double radius) {
        Bot bot = body.bot();
        JsonArray array = new JsonArray();
        if (bot == null || !bot.isValid()) {
            return array;
        }
        for (Entity entity : bot.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Item item) || !item.isValid()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("entityId", item.getEntityId());
            entry.addProperty("item", ItemUtil.nameOf(item.getItemStack()));
            entry.addProperty("count", item.getItemStack().getAmount());
            entry.addProperty("x", VecUtil.round(item.getLocation().getX()));
            entry.addProperty("y", VecUtil.round(item.getLocation().getY()));
            entry.addProperty("z", VecUtil.round(item.getLocation().getZ()));
            entry.addProperty("dist", VecUtil.round(item.getLocation().distance(bot.getLocation())));
            array.add(entry);
        }
        return array;
    }
}
