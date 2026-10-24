package com.leavesdeepseek.buddy.bot;

import org.leavesmc.leaves.entity.bot.Bot;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 兼容不同 Leaves 版本的假人名获取。
 * <p>1.21.8 使用 {@code getRealName()}，1.21.11 起改名为 {@code getRawName()}。</p>
 */
public final class BotNames {

    private static final Map<Class<?>, Method> CACHE = new ConcurrentHashMap<>();

    private BotNames() {
    }

    public static String rawName(Bot bot) {
        if (bot == null) {
            return "?";
        }
        Method method = CACHE.computeIfAbsent(bot.getClass(), BotNames::findNameMethod);
        if (method != null) {
            try {
                Object value = method.invoke(bot);
                if (value instanceof String name && !name.isBlank()) {
                    return name;
                }
            } catch (ReflectiveOperationException ignored) {
                // 退回到 getName()
            }
        }
        return bot.getName();
    }

    private static Method findNameMethod(Class<?> type) {
        for (String name : new String[]{"getRawName", "getRealName"}) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException ignored) {
                // 尝试下一个方法名
            }
        }
        return null;
    }
}
