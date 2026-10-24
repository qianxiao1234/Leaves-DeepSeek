package com.leavesdeepseek.buddy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * 文本与聊天组件工具。
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    public static Component component(String message) {
        return LEGACY.deserialize(message == null ? "" : message);
    }

    public static void send(CommandSender sender, String message) {
        if (sender == null || message == null || message.isBlank()) {
            return;
        }
        for (String line : message.split("\n")) {
            sender.sendMessage(LEGACY.deserialize(line));
        }
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("§[0-9a-fk-orx]", "");
    }
}
