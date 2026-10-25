package com.leavesdeepseek.buddy.bot;

import com.leavesdeepseek.buddy.config.PluginSettings;
import com.leavesdeepseek.buddy.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesmc.leaves.entity.bot.Bot;
import org.leavesmc.leaves.entity.bot.BotCreator;
import org.leavesmc.leaves.entity.bot.BotManager;
import org.leavesmc.leaves.entity.bot.action.JumpAction;
import org.leavesmc.leaves.entity.bot.action.MoveAction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假人的创建、绑定与动作管理。
 */
public final class BotService {

    private final JavaPlugin plugin;
    private final PluginSettings settings;

    /** 主人 UUID -> 假人名列表 */
    private final Map<UUID, List<String>> ownerBots = new ConcurrentHashMap<>();
    /** 假人 UUID -> 主人 UUID */
    private final Map<UUID, UUID> botOwners = new ConcurrentHashMap<>();
    /** 假人名 -> 主人 UUID */
    private final Map<String, UUID> nameOwners = new ConcurrentHashMap<>();
    /** 倒地状态（死亡被拦截后等待复活）：假人 UUID -> 状态 */
    private final Map<UUID, DownedState> downed = new ConcurrentHashMap<>();

    /**
     * 倒地状态：假人死亡被拦下、保留全部物品，等主人复活。
     *
     * @param since        倒地时间戳
     * @param wasFollowing 倒地前是否正在跟随（复活后自动恢复）
     */
    public record DownedState(long since, boolean wasFollowing) {
    }

    public BotService(JavaPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /**
     * 当前服务端是否提供 Leaves 的假人 API。
     */
    public static boolean leavesAvailable() {
        try {
            Class.forName("org.leavesmc.leaves.entity.bot.BotManager", false, BotService.class.getClassLoader());
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    public BotManager manager() {
        return Bukkit.getBotManager();
    }

    public List<Bot> allBots() {
        Collection<Bot> view = manager().getBots();
        return new ArrayList<>(view);
    }

    public Bot byName(String name) {
        return name == null ? null : manager().getBot(name);
    }

    /**
     * 按 UUID 查找在线假人，找不到返回 null。
     */
    public Bot byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (Bot bot : allBots()) {
            if (id.equals(bot.getUniqueId())) {
                return bot;
            }
        }
        return null;
    }

    public List<Bot> botsOf(Player owner) {
        if (owner == null) {
            return Collections.emptyList();
        }
        List<String> names = ownerBots.get(owner.getUniqueId());
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy;
        synchronized (names) {
            copy = new ArrayList<>(names);
        }
        List<Bot> result = new ArrayList<>();
        for (String name : copy) {
            Bot bot = manager().getBot(name);
            if (bot != null) {
                result.add(bot);
            }
        }
        return result;
    }

    /**
     * 玩家当前的主要伙伴（用于聊天 / 指令）。
     */
    public Bot primaryBot(Player owner) {
        List<Bot> bots = botsOf(owner);
        return bots.isEmpty() ? null : bots.get(0);
    }

    public UUID ownerOf(Bot bot) {
        if (bot == null) {
            return null;
        }
        UUID ownerId = botOwners.get(bot.getUniqueId());
        if (ownerId != null) {
            return ownerId;
        }
        return nameOwners.get(BotNames.rawName(bot));
    }

    /**
     * 创建一个伙伴假人。返回的 future 可能在异步线程完成（获取皮肤时）。
     */
    public CompletableFuture<Bot> create(Player owner, String requestedName, String skin) {
        CompletableFuture<Bot> future = new CompletableFuture<>();
        Location location = owner.getLocation().clone();
        String rawName = uniqueName(requestedName == null || requestedName.isBlank()
                ? settings.namePrefix() + owner.getName()
                : requestedName.trim());

        Tasks.onMain(plugin, () -> {
            BotCreator creator = manager().botCreator(rawName, location).creator(owner);
            String skinName = skin == null || skin.isBlank() ? settings.defaultSkin() : skin;
            if (skinName != null && !skinName.isBlank()) {
                creator.skinName(skinName).mojangAPISkin().spawnWithSkin(bot -> {
                    if (bot == null) {
                        future.complete(null);
                    } else {
                        bind(owner.getUniqueId(), bot);
                        future.complete(bot);
                    }
                });
            } else {
                Bot bot = creator.spawn();
                if (bot == null) {
                    future.complete(null);
                } else {
                    bind(owner.getUniqueId(), bot);
                    future.complete(bot);
                }
            }
            return null;
        }).exceptionally(throwable -> {
            future.completeExceptionally(throwable);
            return null;
        });

        return future;
    }

    public boolean remove(Bot bot, boolean save) {
        if (bot == null) {
            return false;
        }
        unbind(bot);
        return bot.remove(save);
    }

    public void bind(UUID ownerId, Bot bot) {
        if (ownerId == null || bot == null) {
            return;
        }
        try {
            // 假人不参与睡眠计数，避免“假人不睡导致夜晚无法跳过”
            bot.setSleepingIgnored(settings.skipSleepCheck());
        } catch (Throwable ignored) {
            // 旧版本服务端可能不支持
        }
        ownerBots.computeIfAbsent(ownerId, key -> Collections.synchronizedList(new ArrayList<>()))
                .add(BotNames.rawName(bot));
        botOwners.put(bot.getUniqueId(), ownerId);
        nameOwners.put(BotNames.rawName(bot), ownerId);
        saveBindings();
    }

    /**
     * 重新应用“不参与睡眠计数”设置（配置重载后使用）。
     */
    public void applySleepSettings() {
        for (Bot bot : allBots()) {
            if (ownerOf(bot) == null) {
                continue;
            }
            try {
                bot.setSleepingIgnored(settings.skipSleepCheck());
            } catch (Throwable ignored) {
                // 旧版本服务端可能不支持
            }
        }
    }

    public void unbind(Bot bot) {
        if (bot == null) {
            return;
        }
        downed.remove(bot.getUniqueId());
        UUID ownerId = botOwners.remove(bot.getUniqueId());
        if (ownerId == null) {
            ownerId = nameOwners.remove(BotNames.rawName(bot));
        } else {
            nameOwners.remove(BotNames.rawName(bot), ownerId);
        }
        if (ownerId != null) {
            List<String> names = ownerBots.get(ownerId);
            if (names != null) {
                names.remove(BotNames.rawName(bot));
                if (names.isEmpty()) {
                    ownerBots.remove(ownerId);
                }
            }
        }
        saveBindings();
    }

    /**
     * 读取 companions.yml，把上次保存的假人重新绑定到主人。
     */
    public void loadBindings() {
        if (!settings.autoBindExisting()) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "companions.yml");
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            UUID ownerId;
            try {
                ownerId = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            List<String> names = yaml.getStringList(key);
            for (String name : names) {
                Bot bot = manager().getBot(name);
                if (bot != null) {
                    bind(ownerId, bot);
                }
            }
        }
    }

    private void saveBindings() {
        try {
            File file = new File(plugin.getDataFolder(), "companions.yml");
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, List<String>> entry : ownerBots.entrySet()) {
                List<String> copy;
                synchronized (entry.getValue()) {
                    copy = new ArrayList<>(entry.getValue());
                }
                if (!copy.isEmpty()) {
                    yaml.set(entry.getKey().toString(), copy);
                }
            }
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("保存 companions.yml 失败: " + exception.getMessage());
        }
    }

    private String uniqueName(String requested) {
        String base = requested == null ? "" : requested.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "AI_Bot";
        }
        if (base.length() > 16) {
            base = base.substring(0, 16);
        }
        String candidate = base;
        int index = 2;
        while (manager().getBot(candidate) != null || Bukkit.getPlayerExact(candidate) != null) {
            String suffix = "_" + index++;
            candidate = base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix;
        }
        return candidate;
    }

    public boolean hasAction(Bot bot, Class<?> type) {
        if (bot == null) {
            return false;
        }
        try {
            for (int index = 0; index < bot.getActionSize(); index++) {
                if (type.isInstance(bot.getAction(index))) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
        return false;
    }

    /**
     * 停止某一类动作，返回停止数量。
     */
    public int stopActions(Bot bot, Class<?> type) {
        if (bot == null) {
            return 0;
        }
        int stopped = 0;
        try {
            for (int index = bot.getActionSize() - 1; index >= 0; index--) {
                if (type.isInstance(bot.getAction(index))) {
                    bot.stopAction(index);
                    stopped++;
                }
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
        return stopped;
    }

    public void stopAllActions(Bot bot) {
        if (bot == null) {
            return;
        }
        try {
            bot.stopAllActions();
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
    }

    public List<String> actionNames(Bot bot) {
        List<String> names = new ArrayList<>();
        if (bot == null) {
            return names;
        }
        try {
            for (int index = 0; index < bot.getActionSize(); index++) {
                names.add(bot.getAction(index).getName());
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
        return names;
    }

    /**
     * 是否存在移动以外的动作（挖掘/放置/使用物品等）。跟随中的 MoveAction 不算，战斗可以打断它。
     */
    public boolean hasBlockingAction(Bot bot) {
        if (bot == null) {
            return false;
        }
        try {
            for (int index = 0; index < bot.getActionSize(); index++) {
                var action = bot.getAction(index);
                if (action instanceof MoveAction || action instanceof JumpAction) {
                    continue;
                }
                return true;
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
        return false;
    }

    public int countOf(Player owner) {
        return botsOf(owner).size();
    }

    // ------------------------------------------------------------------
    // 倒地 / 复活
    // ------------------------------------------------------------------

    /**
     * 标记假人倒地：保留全部物品，等待主人用「复活」指令救起。
     */
    public void markDowned(Bot bot, boolean wasFollowing) {
        if (bot == null) {
            return;
        }
        downed.put(bot.getUniqueId(), new DownedState(System.currentTimeMillis(), wasFollowing));
    }

    public boolean isDowned(Bot bot) {
        return bot != null && downed.containsKey(bot.getUniqueId());
    }

    public DownedState downedState(Bot bot) {
        return bot == null ? null : downed.get(bot.getUniqueId());
    }

    public void clearDowned(Bot bot) {
        if (bot != null) {
            downed.remove(bot.getUniqueId());
        }
    }
}
