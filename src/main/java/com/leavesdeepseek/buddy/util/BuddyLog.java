package com.leavesdeepseek.buddy.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 插件自有日志：写到 {@code plugins/LeavesDeepSeek/logs/yyyy-MM-dd_HH-mm.log}。
 *
 * <p>为什么单独做一套：Bukkit 控制台日志会被刷屏、也不方便用户整份发出来。
 * 这里把「聊天 → AI 解析 → HTTP 请求 → 响应 → 工具执行」全链路写进文件，
 * 出问题时直接看文件就能定位是哪一步断掉。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>异步单线程写盘，主线程只入队，绝不阻塞 tick；</li>
 *   <li>按<b>本次启动的时间</b>分文件（精确到分钟），一次启动一个文件，
 *       不会像按天那样把一整天几十次重启的记录堆在一个大文件里；</li>
 *   <li>同一分钟内重复 reload 会追加序号 {@code yyyy-MM-dd_HH-mm-2.log}；
 *       单文件超过上限也会自动切到下一个序号；</li>
 *   <li>自动清理超过保留天数的旧文件；</li>
 *   <li>写入前统一做 Key 脱敏，绝不落盘明文 API Key。</li>
 * </ul>
 */
public final class BuddyLog implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 文件名里的启动时间戳：yyyy-MM-dd_HH-mm */
    private static final DateTimeFormatter SESSION = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

    /** sk-xxxx / Bearer xxxx / "api_key": "xxxx" 之类的常见 Key 形态。 */
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "(sk-[A-Za-z0-9_\\-]{6,})|((?i)(?:api[-_]?key|token|authorization)\"?\\s*[:=]\\s*\"?)([A-Za-z0-9_\\-]{8,})");

    private final Path directory;
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(20000);
    private final Thread writer;
    /** 本次启动的时间戳，决定日志文件名；一次启动只写一个系列文件。 */
    private final String sessionStamp;

    private volatile boolean enabled;
    private volatile boolean capturePayload;
    private volatile int maxBodyChars;
    private volatile int retainDays;
    private volatile int maxFileBytes;
    private volatile boolean closed;

    /** 当前打开的文件序号，避免每行都去查文件系统。 */
    private int openIndex;
    private BufferedWriter out;
    private long written;

    public BuddyLog(Path directory, boolean enabled, boolean capturePayload,
                    int maxBodyChars, int retainDays, int maxFileMb) {
        this.directory = directory;
        this.sessionStamp = LocalDateTime.now().format(SESSION);
        reconfigure(enabled, capturePayload, maxBodyChars, retainDays, maxFileMb);
        createDirectory();
        cleanupOldFiles();
        this.writer = new Thread(this::pump, "LeavesDeepSeek-Log");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    public void reconfigure(boolean enabled, boolean capturePayload,
                            int maxBodyChars, int retainDays, int maxFileMb) {
        this.enabled = enabled;
        this.capturePayload = capturePayload;
        this.maxBodyChars = Math.max(512, maxBodyChars);
        this.retainDays = Math.max(1, retainDays);
        this.maxFileBytes = Math.max(1, maxFileMb) * 1024 * 1024;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean capturePayload() {
        return capturePayload;
    }

    public String directoryPath() {
        return directory.toString();
    }

    // ------------------------------------------------------------------
    // 对外写入接口
    // ------------------------------------------------------------------

    public void debug(String tag, String message) {
        write("DEBUG", tag, message);
    }

    public void info(String tag, String message) {
        write("INFO", tag, message);
    }

    public void warn(String tag, String message) {
        write("WARN", tag, message);
    }

    public void error(String tag, String message) {
        write("ERROR", tag, message);
    }

    public void error(String tag, String message, Throwable throwable) {
        String text = message == null ? "" : message;
        if (throwable != null) {
            StringWriter buffer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(buffer));
            text = text + System.lineSeparator() + buffer;
        }
        write("ERROR", tag, text);
    }

    /**
     * 记录 HTTP 请求 / 响应体；是否真的写入由 {@code logging.capture-payload} 决定。
     */
    public void payload(String tag, String label, String body) {
        if (!enabled || !capturePayload) {
            return;
        }
        String text = body == null ? "" : body;
        if (text.length() > maxBodyChars) {
            text = text.substring(0, maxBodyChars) + "…（已截断，原始长度 " + body.length() + "）";
        }
        write("PAYLOAD", tag, label + "（" + (body == null ? 0 : body.length()) + " 字符）"
                + System.lineSeparator() + text);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void write(String level, String tag, String message) {
        if (!enabled || closed) {
            return;
        }
        String line = "[" + LocalDate.now().format(DATE) + " " + LocalTime.now().format(TIME) + "] ["
                + level + (level.length() < 7 ? " ".repeat(7 - level.length()) : "") + "] ["
                + (tag == null || tag.isBlank() ? "-" : tag) + "] "
                + (message == null ? "" : message);
        queue.offer(maskSecrets(line));
    }

    /** API Key 一律打码，避免用户把日志发出来时泄露。 */
    static String maskSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = KEY_PATTERN.matcher(text).replaceAll(result -> {
            if (result.group(1) != null) {
                return mask(result.group(1));
            }
            return result.group(2) + mask(result.group(3));
        });
        return masked;
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, Math.min(4, token.length())) + "****"
                + token.substring(token.length() - Math.min(4, token.length()));
    }

    private void pump() {
        while (!closed || !queue.isEmpty()) {
            String line;
            try {
                line = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
            if (line == null) {
                flushQuietly();
                continue;
            }
            try {
                BufferedWriter target = ensureWriter();
                if (target == null) {
                    continue;
                }
                target.write(line);
                target.write(System.lineSeparator());
                written += line.length() + 1L;
                // 每 4KB 刷一次，避免频繁 IO 又能保证崩溃时不丢太多
                if (written >= 4096) {
                    target.flush();
                    written = 0;
                }
            } catch (IOException exception) {
                closeWriter();
            }
        }
        closeWriter();
    }

    private BufferedWriter ensureWriter() throws IOException {
        if (out != null && Files.size(pathOf(openIndex)) < maxFileBytes) {
            return out;
        }
        closeWriter();
        openIndex = nextIndex();
        Path file = pathOf(openIndex);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        written = 0;
        return out;
    }

    /**
     * 找到本次启动可以继续写的序号（已有同名文件且没写满就接着写，否则换下一个）。
     */
    private int nextIndex() {
        int index = 1;
        while (true) {
            Path path = pathOf(index);
            if (!Files.exists(path) || sizeOf(path) < maxFileBytes) {
                return index;
            }
            index++;
            if (index > 999) {
                return 999;
            }
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private Path pathOf(int index) {
        String name = sessionStamp + (index <= 1 ? "" : "-" + index) + ".log";
        return directory.resolve(name);
    }

    private void createDirectory() {
        try {
            Files.createDirectories(directory);
        } catch (IOException ignored) {
            // 目录创建失败时后续写入会自动静默跳过
        }
    }

    private void cleanupOldFiles() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        LocalDate threshold = LocalDate.now().minusDays(retainDays);
        List<Path> stale = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        String datePart = name.length() >= 10 ? name.substring(0, 10) : "";
                        try {
                            if (LocalDate.parse(datePart, DATE).isBefore(threshold)) {
                                stale.add(path);
                            }
                        } catch (RuntimeException ignored) {
                            // 不是本插件命名的文件，不碰
                        }
                    });
        } catch (IOException ignored) {
            return;
        }
        stale.sort(Comparator.naturalOrder());
        for (Path path : stale) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 删除失败不影响使用
            }
        }
    }

    private void flushQuietly() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
            written = 0;
        } catch (IOException exception) {
            closeWriter();
        }
    }

    private void closeWriter() {
        if (out == null) {
            return;
        }
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // 关闭失败只能放弃
        } finally {
            out = null;
        }
    }

    /** 本次启动对应的日志文件绝对路径，供命令/日志提示使用。 */
    public String currentFilePath() {
        return pathOf(out == null ? 1 : openIndex).toAbsolutePath().toString();
    }

    @Override
    public void close() {
        closed = true;
        flushQuietly();
        writer.interrupt();
        try {
            writer.join(3000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        // 把队列里剩下的内容尽量写完
        List<String> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            try {
                BufferedWriter target = ensureWriter();
                if (target != null) {
                    for (String line : rest) {
                        target.write(line);
                        target.write(System.lineSeparator());
                    }
                }
            } catch (IOException ignored) {
                // 关闭阶段不再重试
            }
        }
        closeWriter();
    }

    /** 供插件启动日志展示用的目录（File 形式）。 */
    public File directoryAsFile() {
        return directory.toFile();
    }
}
