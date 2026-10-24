package com.leavesdeepseek.buddy.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leavesdeepseek.buddy.util.Json;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把假人周围的世界状态压缩成 JSON，作为模型的观察结果。
 */
public final class BotSnapshot {

    private BotSnapshot() {
    }

    public static JsonObject capture(Bot bot, Player owner) {
        JsonObject json = new JsonObject();
        try {
            Location location = bot.getLocation();
            World world = location.getWorld();
            json.addProperty("name", BotNames.rawName(bot));
            json.addProperty("world", world == null ? "?" : world.getName());
            json.addProperty("position", coords(location));
            json.addProperty("yaw", Math.round(location.getYaw()));
            json.addProperty("pitch", Math.round(location.getPitch()));
            json.addProperty("health", round(bot.getHealth()));
            json.addProperty("max_health", round(bot.getMaxHealth()));
            json.addProperty("food", bot.getFoodLevel());
            json.addProperty("gamemode", bot.getGameMode().name());
            json.addProperty("on_ground", bot.isOnGround());
            json.addProperty("in_water", bot.isInWater());
            json.addProperty("sneaking", bot.isSneaking());
            json.addProperty("sprinting", bot.isSprinting());
            json.addProperty("held_slot", bot.getInventory().getHeldItemSlot());
            json.addProperty("held_item", describeItem(bot.getInventory().getItemInMainHand()));
            json.addProperty("offhand_item", describeItem(bot.getInventory().getItemInOffHand()));
            json.add("armor", armorSummary(bot.getInventory()));
            json.add("hotbar", hotbar(bot.getInventory()));
            json.add("inventory", inventorySummary(bot.getInventory()));
            json.add("actions", actionNames(bot));

            Block below = location.getBlock().getRelative(0, -1, 0);
            json.addProperty("block_below", below.getType().name());
            if (world != null) {
                json.addProperty("time", world.isDayTime() ? "day" : "night");
                json.addProperty("weather", world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear");
            }

            Block inSight = bot.getTargetBlockExact(5);
            if (inSight != null) {
                json.addProperty("block_in_sight", inSight.getType().name() + "@" + coords(inSight.getLocation()));
            }

            if (owner != null && owner.isOnline()) {
                json.addProperty("owner", owner.getName());
                json.addProperty("owner_health", round(owner.getHealth()));
                json.addProperty("owner_position", coords(owner.getLocation()));
                json.add("owner_inventory", inventorySummary(owner.getInventory()));
                if (owner.getWorld() != null && owner.getWorld().equals(world)) {
                    json.addProperty("owner_distance", round(owner.getLocation().distance(location)));
                }
            }

            json.add("nearby_players", nearbyPlayers(bot, world));
            json.add("nearby_entities", nearbyEntities(bot));
            json.add("nearby_items", nearbyItems(bot));
            json.add("nearby_beds", nearbyBeds(bot));
        } catch (Throwable throwable) {
            json.addProperty("snapshot_error", String.valueOf(throwable.getMessage()));
        }
        return json;
    }

    private static JsonArray nearbyPlayers(Bot bot, World world) {
        JsonArray array = new JsonArray();
        if (world == null) {
            return array;
        }
        Location origin = bot.getLocation();
        for (Player player : world.getPlayers()) {
            if (player.getUniqueId().equals(bot.getUniqueId())) {
                continue;
            }
            double distance = player.getLocation().distance(origin);
            if (distance > 32) {
                continue;
            }
            array.add(Json.obj(
                    "name", player.getName(),
                    "distance", round(distance),
                    "position", coords(player.getLocation())));
        }
        return array;
    }

    private static JsonArray nearbyBeds(Bot bot) {
        JsonArray array = new JsonArray();
        World world = bot.getWorld();
        if (world == null) {
            return array;
        }
        Location origin = bot.getLocation();
        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();
        int radius = 6;
        for (int dx = -radius; dx <= radius && array.size() < 5; dx++) {
            for (int dz = -radius; dz <= radius && array.size() < 5; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Block block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    if (!(block.getBlockData() instanceof Bed bed) || bed.getPart() != Bed.Part.HEAD) {
                        continue;
                    }
                    array.add(Json.obj(
                            "position", coords(block.getLocation()),
                            "distance", round(block.getLocation().distance(origin)),
                            "occupied", bed.isOccupied()));
                    break;
                }
            }
        }
        return array;
    }

    private static JsonArray nearbyItems(Bot bot) {
        JsonArray array = new JsonArray();
        for (Entity entity : bot.getNearbyEntities(16, 8, 16)) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            array.add(Json.obj(
                    "item", stack.getType().name(),
                    "amount", stack.getAmount(),
                    "distance", round(entity.getLocation().distance(bot.getLocation())),
                    "position", coords(entity.getLocation())));
            if (array.size() >= 10) {
                break;
            }
        }
        return array;
    }

    private static JsonArray nearbyEntities(Bot bot) {
        JsonArray array = new JsonArray();
        List<Entity> entities = new ArrayList<>(bot.getNearbyEntities(16, 8, 16));
        entities.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bot.getLocation())));
        int count = 0;
        for (Entity entity : entities) {
            if (entity instanceof Player || !(entity instanceof LivingEntity living)) {
                continue;
            }
            if (count++ >= 10) {
                break;
            }
            array.add(Json.obj(
                    "type", entity.getType().name(),
                    "distance", round(entity.getLocation().distance(bot.getLocation())),
                    "health", round(living.getHealth()),
                    "position", coords(entity.getLocation())));
        }
        return array;
    }

    private static JsonArray actionNames(Bot bot) {
        JsonArray array = new JsonArray();
        try {
            for (int index = 0; index < bot.getActionSize(); index++) {
                array.add(bot.getAction(index).getName());
            }
        } catch (RuntimeException ignored) {
            // 假人可能已被移除
        }
        return array;
    }

    private static JsonArray armorSummary(Inventory inventory) {
        JsonArray array = new JsonArray();
        for (int slot : ArmorRules.ALL_SLOTS) {
            array.add(Json.obj(
                    "slot", ArmorRules.slotName(slot),
                    "item", describeItem(inventory.getItem(slot))));
        }
        return array;
    }

    private static JsonArray hotbar(Inventory inventory) {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir()) {
                array.add(Json.obj("slot", slot, "item", describeItem(stack)));
            }
        }
        return array;
    }

    private static JsonArray inventorySummary(Inventory inventory) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        List<Map.Entry<Material, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<Material, Integer>comparingByValue().reversed());

        JsonArray array = new JsonArray();
        int limit = 0;
        for (Map.Entry<Material, Integer> entry : entries) {
            if (limit++ >= 15) {
                break;
            }
            array.add(Json.obj("item", entry.getKey().name(), "amount", entry.getValue()));
        }
        return array;
    }

    private static String describeItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "empty";
        }
        String name = stack.getType().name();
        return stack.getAmount() > 1 ? name + " x" + stack.getAmount() : name;
    }

    private static String coords(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
