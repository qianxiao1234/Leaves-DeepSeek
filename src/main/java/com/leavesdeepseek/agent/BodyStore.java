package com.leavesdeepseek.agent;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.leavesmc.leaves.entity.bot.Bot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 躯壳存档 {@code bodies/<name>/state.yml}（规格 §11）。
 *
 * <p>背包、盔甲、副手、经验、血量、饥饿、饱和度、维度、坐标、朝向、锚点、出生时间，一项都不能少。</p>
 */
public final class BodyStore {

    private final JavaPlugin plugin;
    private final File root;

    public BodyStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "bodies");
    }

    public File fileOf(String name) {
        return new File(new File(root, name), "state.yml");
    }

    public boolean exists(String name) {
        return fileOf(name).isFile();
    }

    public YamlConfiguration read(String name) {
        File file = fileOf(name);
        if (!file.isFile()) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            return yaml;
        } catch (Exception exception) {
            plugin.getLogger().warning("读取躯壳存档失败（" + name + "）：" + exception.getMessage());
            exception.printStackTrace();
            return null;
        }
    }

    /**
     * 读存档里的归属指纹；没有则返回空串（老存档 = legacy）。
     */
    public String ownerOf(String name) {
        YamlConfiguration yaml = read(name);
        if (yaml == null) {
            return "";
        }
        String owner = yaml.getString("owner", "");
        return owner == null ? "" : owner.trim();
    }

    /**
     * 列出所有有存档的躯壳名（启动时登记用）。
     */
    public List<String> listStored() {
        List<String> names = new ArrayList<>();
        if (!root.isDirectory()) {
            return names;
        }
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            return names;
        }
        for (File dir : dirs) {
            if (new File(dir, "state.yml").isFile()) {
                names.add(dir.getName());
            }
        }
        return names;
    }

    /** 躯壳自己的目录（bodies/&lt;name&gt;/），像 players/&lt;uuid&gt;/ 一样。 */
    public File directoryOf(String name) {
        return new File(root, name);
    }

    private File tokenFileOf(String name) {
        return new File(directoryOf(name), "token.txt");
    }

    /**
     * 该躯壳的自定义 token；没有则返回空串（表示只能用全局 agent.token）。
     */
    public String tokenOf(String name) {
        File file = tokenFileOf(name);
        if (!file.isFile()) {
            return "";
        }
        try {
            String text = java.nio.file.Files.readString(file.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return text;
        } catch (IOException exception) {
            plugin.getLogger().warning("读取躯壳 token 失败（" + name + "）：" + exception.getMessage());
            return "";
        }
    }

    /**
     * 写该躯壳的自定义 token；传空串 = 删除（回落到全局 token）。
     *
     * @return true 表示已写入/删除
     */
    public boolean setToken(String name, String token) {
        File dir = directoryOf(name);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("创建躯壳目录失败：" + dir.getAbsolutePath());
            return false;
        }
        File file = tokenFileOf(name);
        try {
            if (token == null || token.isBlank()) {
                if (file.isFile() && !file.delete()) {
                    return false;
                }
                return true;
            }
            java.nio.file.Files.writeString(file.toPath(), token.trim() + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("写入躯壳 token 失败（" + name + "）：" + exception.getMessage());
            return false;
        }
    }

    public void delete(String name) {
        File file = fileOf(name);
        if (file.isFile() && !file.delete()) {
            plugin.getLogger().warning("删除躯壳存档失败：" + file.getAbsolutePath());
        }
        // token.txt 也一起删（dir 非空就删不掉）
        File tokenFile = tokenFileOf(name);
        if (tokenFile.isFile() && !tokenFile.delete()) {
            plugin.getLogger().warning("删除躯壳 token 失败：" + tokenFile.getAbsolutePath());
        }
        File dir = new File(root, name);
        String[] rest = dir.list();
        if (rest != null && rest.length == 0 && !dir.delete()) {
            plugin.getLogger().warning("删除躯壳目录失败：" + dir.getAbsolutePath());
        }
    }

    /**
     * 把当前实体写进存档。{@code bot} 必须在线。
     *
     * @return true 表示写入成功
     */
    public boolean save(String name, Bot bot, Location anchor, long createdAt, long lastSeen) {
        return save(name, bot, anchor, createdAt, lastSeen, null);
    }

    /**
     * 把当前实体写进存档。{@code bot} 必须在线。
     *
     * @param owner 躯壳归属指纹（token 的 sha256 前 16 位）；null 表示不写/保留原值
     * @return true 表示写入成功
     */
    public boolean save(String name, Bot bot, Location anchor, long createdAt, long lastSeen,
                        String owner) {
        return save(name, bot, anchor, createdAt, lastSeen, owner, false);
    }

    /**
     * 把当前实体写进存档。{@code bot} 必须在线。
     *
     * @param owner  躯壳归属指纹（token 的 sha256 前 16 位）；null 表示沿用旧值
     * @param downed 是否处于「倒地」状态（重启后要恢复成倒地，等 revive）
     * @return true 表示写入成功
     */
    public boolean save(String name, Bot bot, Location anchor, long createdAt, long lastSeen,
                        String owner, boolean downed) {
        if (bot == null || !bot.isValid()) {
            return false;
        }
        Location location = bot.getLocation();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", name);
        yaml.set("uuid", bot.getUniqueId().toString());
        if (owner != null && !owner.isBlank()) {
            yaml.set("owner", owner);
        } else {
            // 没传就沿用旧存档里的 owner，避免中途被清掉
            YamlConfiguration previous = read(name);
            String keep = previous == null ? null : previous.getString("owner");
            if (keep != null && !keep.isBlank()) {
                yaml.set("owner", keep);
            }
        }
        yaml.set("world", location.getWorld() == null ? "" : location.getWorld().getName());
        yaml.set("x", round(location.getX()));
        yaml.set("y", round(location.getY()));
        yaml.set("z", round(location.getZ()));
        yaml.set("yaw", round(location.getYaw()));
        yaml.set("pitch", round(location.getPitch()));
        yaml.set("health", round(bot.getHealth()));
        yaml.set("food", bot.getFoodLevel());
        yaml.set("saturation", round(bot.getSaturation()));
        yaml.set("xp", bot.getTotalExperience());
        yaml.set("level", bot.getLevel());
        yaml.set("exp", round(bot.getExp()));
        yaml.set("gamemode", bot.getGameMode() == null ? "SURVIVAL" : bot.getGameMode().name());
        if (anchor != null && anchor.getWorld() != null) {
            yaml.set("anchor.world", anchor.getWorld().getName());
            yaml.set("anchor.x", round(anchor.getX()));
            yaml.set("anchor.y", round(anchor.getY()));
            yaml.set("anchor.z", round(anchor.getZ()));
        }
        PlayerInventory inventory = bot.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            yaml.set("inventory." + i, stack);
        }
        yaml.set("heldSlot", inventory.getHeldItemSlot());
        yaml.set("armor.helmet", inventory.getHelmet());
        yaml.set("armor.chestplate", inventory.getChestplate());
        yaml.set("armor.leggings", inventory.getLeggings());
        yaml.set("armor.boots", inventory.getBoots());
        yaml.set("armor.offhand", inventory.getItemInOffHand());

        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : bot.getActivePotionEffects()) {
            effects.add(effect.getType().getName() + ":" + effect.getDuration() + ":" + effect.getAmplifier());
        }
        yaml.set("effects", effects);
        yaml.set("createdAt", createdAt <= 0 ? System.currentTimeMillis() : createdAt);
        yaml.set("lastSeen", lastSeen <= 0 ? System.currentTimeMillis() : lastSeen);
        yaml.set("downed", downed);

        File file = fileOf(name);
        File dir = file.getParentFile();
        try {
            if (dir != null) {
                java.nio.file.Files.createDirectories(dir.toPath());
            }
            yaml.save(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("保存躯壳存档失败（" + name + "）：" + exception.getMessage());
            exception.printStackTrace();
            return false;
        }
    }

    /**
     * 把存档写回一个在线的实体（创建/唤醒/旁观切回后调用）。
     */
    public void apply(Bot bot, YamlConfiguration yaml) {
        if (bot == null || !bot.isValid() || yaml == null) {
            return;
        }
        try {
            World world = org.bukkit.Bukkit.getWorld(yaml.getString("world", ""));
            if (world != null) {
                Location location = new Location(world,
                        yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                        (float) yaml.getDouble("yaw"), (float) yaml.getDouble("pitch"));
                bot.teleport(location);
            }
            String mode = yaml.getString("gamemode", "SURVIVAL");
            bot.setGameMode(mode == null ? GameMode.SURVIVAL : GameMode.valueOf(mode));
            bot.setHealth(Math.max(1.0, Math.min(bot.getMaxHealth(), yaml.getDouble("health", bot.getMaxHealth()))));
            bot.setFoodLevel(Math.max(0, Math.min(20, yaml.getInt("food", 20))));
            bot.setSaturation((float) Math.max(0.0, Math.min(20.0, yaml.getDouble("saturation", 5.0))));
            bot.setTotalExperience(Math.max(0, yaml.getInt("xp", 0)));
            bot.setLevel(Math.max(0, yaml.getInt("level", 0)));
            bot.setExp((float) Math.max(0.0, Math.min(1.0, yaml.getDouble("exp", 0.0))));

            PlayerInventory inventory = bot.getInventory();
            if (yaml.isConfigurationSection("inventory")) {
                for (String key : yaml.getConfigurationSection("inventory").getKeys(false)) {
                    int slot;
                    try {
                        slot = Integer.parseInt(key);
                    } catch (NumberFormatException exception) {
                        continue;
                    }
                    if (slot < 0 || slot >= inventory.getSize()) {
                        continue;
                    }
                    inventory.setItem(slot, yaml.getItemStack("inventory." + key));
                }
            }
            inventory.setHeldItemSlot(Math.max(0, Math.min(8, yaml.getInt("heldSlot", 0))));
            inventory.setHelmet(yaml.getItemStack("armor.helmet"));
            inventory.setChestplate(yaml.getItemStack("armor.chestplate"));
            inventory.setLeggings(yaml.getItemStack("armor.leggings"));
            inventory.setBoots(yaml.getItemStack("armor.boots"));
            inventory.setItemInOffHand(yaml.getItemStack("armor.offhand"));

            for (PotionEffect effect : bot.getActivePotionEffects()) {
                bot.removePotionEffect(effect.getType());
            }
            for (String raw : yaml.getStringList("effects")) {
                String[] parts = raw.split(":");
                if (parts.length < 3) {
                    continue;
                }
                PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase(Locale.ROOT));
                if (type == null) {
                    continue;
                }
                try {
                    bot.addPotionEffect(new PotionEffect(type,
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), true, false));
                } catch (NumberFormatException ignored) {
                    // 存档坏了就跳过这条
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("应用躯壳存档失败：" + throwable.getMessage());
            throwable.printStackTrace();
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
