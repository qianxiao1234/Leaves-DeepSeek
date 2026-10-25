package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.leavesmc.leaves.entity.bot.Bot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 假人状态持久化：定期写入 players/&lt;uuid&gt;/bot-state.yml，启动时恢复。
 */
public final class BotStateService {

    private static final int INVENTORY_SLOTS = 41;

    private final LeavesDeepSeekPlugin plugin;
    /** 死亡瞬间的背包快照：重生后放回去，避免装备掉落造成「存档 + 地面掉落」两份 */
    private final Map<UUID, ItemStack[]> pendingDeathInventories = new HashMap<>();
    private BukkitTask saveTask;

    public BotStateService(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.settings().persistEnabled()) {
            return;
        }
        scheduleSaver();
        if (plugin.settings().restoreOnStart()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::restoreAll, 40L);
        }
    }

    public void stop() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        saveAll();
        pendingDeathInventories.clear();
    }

    /**
     * 配置重载时只重排保存任务，不会重新恢复假人。
     */
    public void reload() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (plugin.settings().persistEnabled()) {
            scheduleSaver();
        }
    }

    private void scheduleSaver() {
        long period = Math.max(100L, plugin.settings().persistIntervalSeconds() * 20L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, period, period);
    }

    public boolean enabled() {
        return plugin.settings().persistEnabled();
    }

    public void saveAll() {
        if (!enabled()) {
            return;
        }
        for (Bot bot : plugin.bots().allBots()) {
            if (plugin.bots().ownerOf(bot) != null) {
                save(bot);
            }
        }
    }

    public void save(Bot bot) {
        if (!enabled() || bot == null || !bot.isValid()) {
            return;
        }
        ItemStack[] contents = bot.getInventory().getContents();
        ItemStack[] pending = pendingDeathInventories.get(bot.getUniqueId());
        if (pending != null) {
            // 死亡到重生之间：背包已经被服务端清空，用死亡瞬间的快照，别把存档写成空的
            contents = pending;
        } else if (bot.isDead() && isEmpty(contents)) {
            // 没有快照、人又死了、背包是空的 —— 不要用空背包覆盖上一份存档
            return;
        }
        write(bot, contents);
    }

    /**
     * 写入假人状态。
     *
     * @return true 表示存档已成功落盘
     */
    private boolean write(Bot bot, ItemStack[] contents) {
        UUID ownerId = plugin.bots().ownerOf(bot);
        if (ownerId == null) {
            return false;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", BotNames.rawName(bot));
        yaml.set("skin", bot.getSkinName());
        Location location = bot.getLocation();
        if (location.getWorld() != null) {
            yaml.set("world", location.getWorld().getName());
            yaml.set("x", location.getX());
            yaml.set("y", location.getY());
            yaml.set("z", location.getZ());
            yaml.set("yaw", location.getYaw());
            yaml.set("pitch", location.getPitch());
        }
        yaml.set("health", bot.getHealth());
        yaml.set("food", bot.getFoodLevel());
        yaml.set("saturation", bot.getSaturation());
        yaml.set("level", bot.getLevel());
        yaml.set("exp", bot.getExp());
        yaml.set("total-experience", bot.getTotalExperience());
        yaml.set("gamemode", bot.getGameMode().name());
        yaml.set("downed", plugin.bots().isDowned(bot));

        if (contents != null) {
            for (int slot = 0; slot < Math.min(INVENTORY_SLOTS, contents.length); slot++) {
                yaml.set("inventory." + slot, contents[slot]);
            }
        }
        yaml.set("updated-at", System.currentTimeMillis());

        try {
            yaml.save(plugin.profiles().stateFileOf(ownerId));
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("保存假人状态失败（" + BotNames.rawName(bot) + "）：" + exception.getMessage());
            return false;
        }
    }

    /**
     * 移除假人之前调用：先写存档，成功后再清空背包。
     * <p>Leaves 移除假人时会把装备当掉落物扔在地上，掉落物会随区块存盘、重启后依然存在，
     * 而插件也会从存档恢复同一套装备 → 凭空多一套。先清空就没有掉落物。</p>
     *
     * @return true 表示存档已写好，并且背包已清空
     */
    public boolean saveAndClear(Bot bot) {
        if (!enabled() || bot == null || !bot.isValid()) {
            return false;
        }
        ItemStack[] contents = pendingDeathInventories.getOrDefault(
                bot.getUniqueId(), bot.getInventory().getContents());
        if (!write(bot, contents)) {
            // 存档写失败就不要清空，宁可掉在地上也不能让东西凭空消失
            return false;
        }
        clearInventory(bot);
        return true;
    }

    /**
     * 假人死亡：记下背包快照（掉落物），等重生后原样放回去。
     * <p>同时立刻把快照写进存档，万一下次启动前没能重生，装备也不会丢。</p>
     */
    public void rememberDeathInventory(Bot bot, ItemStack[] contents) {
        if (bot == null || contents == null) {
            return;
        }
        UUID botId = bot.getUniqueId();
        pendingDeathInventories.put(botId, contents);
        write(bot, contents);
        scheduleDeathRestore(bot, 0);
    }

    public boolean hasPendingDeathInventory(Bot bot) {
        return bot != null && pendingDeathInventories.containsKey(bot.getUniqueId());
    }

    /**
     * 重生后把装备放回去。假人不一定会触发 PlayerRespawnEvent，所以用轮询兜底。
     */
    private void scheduleDeathRestore(Bot bot, int attempt) {
        if (attempt > 60) {
            plugin.log().warn("伙伴", "假人 30 秒内没有重生，装备已保存在存档里：" + BotNames.rawName(bot));
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID botId = bot.getUniqueId();
            ItemStack[] pending = pendingDeathInventories.get(botId);
            if (pending == null) {
                return;
            }
            if (!bot.isValid()) {
                // 假人已经被移除：装备留在 bot-state.yml 里，下次恢复时重新穿上
                return;
            }
            if (bot.isDead()) {
                scheduleDeathRestore(bot, attempt + 1);
                return;
            }
            try {
                PlayerInventory inventory = bot.getInventory();
                if (isEmpty(inventory.getContents())) {
                    // 正常情况：重生后背包是空的，按原槽位放回去（盔甲/副手也归位）
                    for (int slot = 0; slot < pending.length && slot < inventory.getSize(); slot++) {
                        inventory.setItem(slot, pending[slot]);
                    }
                } else {
                    // 有别的插件在重生时给了东西：只往空位里塞，绝不覆盖
                    for (ItemStack item : pending) {
                        if (item != null && !item.getType().isAir()) {
                            inventory.addItem(item);
                        }
                    }
                }
                pendingDeathInventories.remove(botId);
                save(bot);
                plugin.log().info("伙伴", "假人 " + BotNames.rawName(bot) + " 已重生，装备已放回背包");
            } catch (RuntimeException exception) {
                plugin.log().warn("伙伴", "重生后恢复装备失败：" + exception.getMessage());
            }
        }, 10L);
    }

    /**
     * 清空假人的背包与装备（含盔甲、副手）。
     * <p>用途：假人被移除 / 服务器关闭前先把东西收进存档，
     * 否则 Leaves 会把装备当掉落物丢在地上，重启后存档里还有一份 → 凭空多一套。</p>
     */
    public void clearInventory(Bot bot) {
        if (bot == null || !bot.isValid()) {
            return;
        }
        try {
            PlayerInventory inventory = bot.getInventory();
            inventory.clear();
            inventory.setHeldItemSlot(0);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("清空假人背包失败（" + BotNames.rawName(bot) + "）：" + exception.getMessage());
        }
    }

    /**
     * 死亡时服务端已经把背包清空，只剩掉落物列表；按装备类型尽量还原到 41 个槽位。
     */
    public static ItemStack[] inventoryFromDrops(List<ItemStack> drops) {
        ItemStack[] contents = new ItemStack[INVENTORY_SLOTS];
        if (drops == null || drops.isEmpty()) {
            return contents;
        }
        List<ItemStack> rest = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            ItemStack copy = drop.clone();
            int slot = armorSlotOf(copy.getType());
            if (slot >= 0 && contents[slot] == null) {
                contents[slot] = copy;
                continue;
            }
            rest.add(copy);
        }
        int slot = 0;
        for (ItemStack item : rest) {
            while (slot < 36 && contents[slot] != null) {
                slot++;
            }
            if (slot >= 36) {
                break;
            }
            contents[slot] = item;
            slot++;
        }
        return contents;
    }

    private static int armorSlotOf(Material material) {
        String name = material.name();
        if ("SHIELD".equals(name)) {
            return 40;
        }
        if (name.endsWith("_HELMET") || "TURTLE_HELMET".equals(name) || "CARVED_PUMPKIN".equals(name)) {
            return 39;
        }
        if (name.endsWith("_CHESTPLATE") || "ELYTRA".equals(name)) {
            return 38;
        }
        if (name.endsWith("_LEGGINGS")) {
            return 37;
        }
        if (name.endsWith("_BOOTS")) {
            return 36;
        }
        return -1;
    }

    private static boolean isEmpty(ItemStack[] contents) {
        if (contents == null) {
            return true;
        }
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    public void delete(UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        File file = plugin.profiles().stateFileOf(ownerId);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("删除假人状态失败：" + file.getAbsolutePath());
        }
    }

    /** 该玩家是否有可用的假人存档（用于「复活/唤醒」）。 */
    public boolean hasState(UUID ownerId) {
        return ownerId != null && plugin.profiles().stateFileOf(ownerId).exists();
    }

    /**
     * 从存档重新生成该玩家的假人（供 {@code /buddy bot revive} 使用）。
     *
     * @return true 表示已开始生成（皮肤可能异步加载，需要轮询等待）
     */
    public boolean restoreSaved(UUID ownerId) {
        if (ownerId == null) {
            return false;
        }
        File file = plugin.profiles().stateFileOf(ownerId);
        if (!file.exists()) {
            return false;
        }
        try {
            return restore(ownerId, YamlConfiguration.loadConfiguration(file), 0);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("从存档唤醒假人失败：" + throwable.getMessage());
            return false;
        }
    }

    /**
     * 启动时扫描所有玩家目录并恢复假人。
     */
    public void restoreAll() {
        if (!enabled()) {
            plugin.getLogger().info("[恢复] 假人状态持久化已关闭（persistence.enabled=false），跳过恢复。");
            return;
        }
        File[] directories = plugin.profiles().playersDirectory().listFiles(File::isDirectory);
        if (directories == null || directories.length == 0) {
            plugin.getLogger().info("[恢复] players 目录为空，没有可恢复的假人。");
            return;
        }
        int found = 0;
        int restored = 0;
        for (File directory : directories) {
            UUID ownerId;
            try {
                ownerId = UUID.fromString(directory.getName());
            } catch (IllegalArgumentException exception) {
                continue;
            }
            File file = new File(directory, "bot-state.yml");
            if (!file.exists()) {
                continue;
            }
            found++;
            try {
                if (restore(ownerId, YamlConfiguration.loadConfiguration(file), 0)) {
                    restored++;
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning("[恢复] 失败（" + directory.getName() + "）：" + throwable.getMessage());
            }
        }
        plugin.getLogger().info("[恢复] 扫描 " + directories.length + " 个玩家目录，发现 "
                + found + " 个假人存档，已处理 " + restored + " 个。");
    }

    /**
     * @return true 表示已处理（含已安排重试），false 表示放弃
     */
    private boolean restore(UUID ownerId, YamlConfiguration yaml, int attempt) {
        String name = yaml.getString("name", "");
        if (name == null || name.isBlank()) {
            plugin.getLogger().warning("[恢复] 存档缺少假人名，跳过：" + ownerId);
            return false;
        }
        String worldName = yaml.getString("world", "");
        World world = Bukkit.getWorld(worldName == null ? "" : worldName);
        if (world == null) {
            if (attempt < 3) {
                plugin.getLogger().info("[恢复] 世界 " + worldName + " 尚未加载，2 秒后重试（"
                        + (attempt + 1) + "/3）：" + name);
                Bukkit.getScheduler().runTaskLater(plugin, () -> restore(ownerId, yaml, attempt + 1), 40L);
                return true;
            }
            plugin.getLogger().warning("[恢复] 世界 " + worldName + " 未加载，放弃恢复假人：" + name);
            return false;
        }
        Location location = new Location(world,
                yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));

        Bot existing = plugin.bots().manager().getBot(name);
        if (existing != null) {
            UUID boundOwner = plugin.bots().ownerOf(existing);
            if (boundOwner != null && !boundOwner.equals(ownerId)) {
                plugin.getLogger().warning("[恢复] 假人 " + name + " 已归属其他玩家，跳过恢复。");
                return false;
            }
            apply(existing, yaml, location);
            plugin.bots().bind(ownerId, existing);
            plugin.getLogger().info("[恢复] 已恢复假人：" + name);
            return true;
        }

        String skin = yaml.getString("skin", "");
        if (skin != null && !skin.isBlank()) {
            spawnWithSkin(ownerId, name, skin, location, yaml);
        } else {
            spawnPlain(ownerId, name, location, yaml);
        }
        return true;
    }

    /**
     * 带皮肤创建假人。Mojang 皮肤接口可能长时间无响应，10 秒后自动降级为默认皮肤重建，
     * 避免假人“静默消失”。
     */
    private void spawnWithSkin(UUID ownerId, String name, String skin, Location location, YamlConfiguration yaml) {
        boolean[] finished = {false};
        try {
            Bukkit.getBotManager().botCreator(name, location)
                    .creator(Bukkit.getConsoleSender())
                    .skinName(skin)
                    .mojangAPISkin()
                    .spawnWithSkin(bot -> {
                        if (finished[0]) {
                            return;
                        }
                        finished[0] = true;
                        if (bot == null) {
                            plugin.getLogger().warning("[恢复] 皮肤加载失败，改用默认皮肤重建：" + name);
                            spawnPlain(ownerId, name, location, yaml);
                            return;
                        }
                        afterSpawn(ownerId, bot, yaml, location);
                    });
        } catch (Throwable throwable) {
            finished[0] = true;
            plugin.getLogger().warning("[恢复] 请求皮肤失败（" + skin + "），改用默认皮肤重建："
                    + name + " - " + throwable.getMessage());
            spawnPlain(ownerId, name, location, yaml);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            plugin.getLogger().warning("[恢复] 皮肤请求超时（10 秒），改用默认皮肤重建：" + name);
            spawnPlain(ownerId, name, location, yaml);
        }, 200L);
    }

    private void spawnPlain(UUID ownerId, String name, Location location, YamlConfiguration yaml) {
        try {
            Bot bot = Bukkit.getBotManager().botCreator(name, location)
                    .creator(Bukkit.getConsoleSender())
                    .spawn();
            afterSpawn(ownerId, bot, yaml, location);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("[恢复] 无法创建假人 " + name + "："
                    + location.getWorld().getName() + " " + location.getBlockX() + ","
                    + location.getBlockY() + "," + location.getBlockZ()
                    + " - " + throwable.getMessage());
        }
    }

    private void afterSpawn(UUID ownerId, Bot bot, YamlConfiguration yaml, Location location) {
        if (bot == null) {
            plugin.getLogger().warning("[恢复] 无法创建假人：" + yaml.getString("name", "?")
                    + " @ " + location.getWorld().getName() + " "
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            apply(bot, yaml, location);
            plugin.bots().bind(ownerId, bot);
            plugin.getLogger().info("[恢复] 已重建假人：" + BotNames.rawName(bot));
        });
    }

    private void apply(Bot bot, YamlConfiguration yaml, Location location) {
        try {
            bot.teleport(location);

            String gameMode = yaml.getString("gamemode", "SURVIVAL");
            try {
                bot.setGameMode(GameMode.valueOf(gameMode == null ? "SURVIVAL" : gameMode));
            } catch (IllegalArgumentException ignored) {
                // 无效的游戏模式则保持当前值
            }

            bot.setFoodLevel(Math.max(0, Math.min(20, yaml.getInt("food", 20))));
            bot.setSaturation((float) Math.max(0, Math.min(20, yaml.getDouble("saturation", 5))));
            bot.setTotalExperience(Math.max(0, yaml.getInt("total-experience", 0)));
            bot.setLevel(Math.max(0, yaml.getInt("level", 0)));
            bot.setExp((float) Math.max(0, Math.min(1, yaml.getDouble("exp", 0))));

            double health = yaml.getDouble("health", bot.getMaxHealth());
            bot.setHealth(Math.max(1, Math.min(bot.getMaxHealth(), health)));

            for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
                bot.getInventory().setItem(slot, yaml.getItemStack("inventory." + slot));
            }

            if (yaml.getBoolean("downed", false)) {
                // 上次是倒地状态存档的：恢复后仍然是倒地状态，等主人复活
                plugin.bots().markDowned(bot, false);
                bot.setInvulnerable(true);
                bot.setHealth(Math.min(1.0, Math.max(0.1, bot.getMaxHealth())));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("应用假人状态失败：" + throwable.getMessage());
        }
    }
}
