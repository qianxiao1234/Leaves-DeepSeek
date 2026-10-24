package com.leavesdeepseek.buddy.tools;

import com.leavesdeepseek.buddy.LeavesDeepSeekPlugin;
import com.leavesdeepseek.buddy.ai.AgentTool;
import com.leavesdeepseek.buddy.ai.Schema;
import com.leavesdeepseek.buddy.ai.Tools;
import com.leavesdeepseek.buddy.bot.ArmorRules;
import com.leavesdeepseek.buddy.util.Json;
import org.leavesmc.leaves.entity.bot.Bot;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 穿盔甲。
 */
public final class ArmorTools {

    private final LeavesDeepSeekPlugin plugin;

    public ArmorTools(LeavesDeepSeekPlugin plugin) {
        this.plugin = plugin;
    }

    public List<AgentTool> all() {
        return List.of(equipArmor());
    }

    private AgentTool equipArmor() {
        Schema schema = Schema.object()
                .strEnum("slot", "只处理这个槽位；不填则头盔/胸甲/护腿/靴子都检查一遍",
                        "helmet", "chestplate", "leggings", "boots");
        return Tools.of("equip_armor",
                "穿上背包里的盔甲。空槽位会自动穿上最好的一件；已有盔甲时，只有背包里严格更好的才会换，"
                        + "否则保持原样并说明原因。不会把旧盔甲丢在地上。",
                schema, (context, arguments) -> {
                    Bot bot = context.bot();
                    String slotName = Json.getString(arguments, "slot", "");
                    List<Integer> slots;
                    if (slotName == null || slotName.isBlank()) {
                        slots = ArmorRules.ALL_SLOTS;
                    } else {
                        int slot = ArmorRules.slotIndexOf(slotName);
                        if (slot < 0) {
                            return done(Json.error("没有这个装备槽位：" + slotName
                                    + "（可用 helmet / chestplate / leggings / boots）"));
                        }
                        slots = List.of(slot);
                    }

                    ArmorRules.Report report = ArmorRules.equip(bot, slots);
                    if (report.changed()) {
                        plugin.botStates().save(bot);
                    }

                    var changes = Json.array();
                    for (ArmorRules.Change change : report.changes()) {
                        changes.add(Json.obj(
                                "slot", change.slot(),
                                "from", change.from(),
                                "to", change.to()));
                    }
                    var notes = Json.array();
                    for (String note : report.notes()) {
                        notes.add(note);
                    }

                    String message;
                    if (report.changed()) {
                        StringBuilder builder = new StringBuilder("已穿上：");
                        boolean first = true;
                        for (ArmorRules.Change change : report.changes()) {
                            if (!first) {
                                builder.append("，");
                            }
                            builder.append(ArmorRules.slotLabel(ArmorRules.slotIndexOf(change.slot())))
                                    .append(' ').append(change.to());
                            first = false;
                        }
                        message = builder.toString();
                        if (!report.notes().isEmpty()) {
                            message += "；其余：" + String.join("；", report.notes());
                        }
                        return done(Json.ok(message, "equipped", changes, "skipped", notes));
                    }

                    String reason = report.notes().isEmpty()
                            ? "背包里没有可穿的盔甲"
                            : String.join("；", report.notes());
                    return done(Json.ok("这次没有换装：" + reason, "equipped", changes, "skipped", notes));
                });
    }

    private CompletableFuture<String> done(String value) {
        return CompletableFuture.completedFuture(value);
    }
}
