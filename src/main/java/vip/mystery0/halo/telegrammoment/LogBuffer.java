package vip.mystery0.halo.telegrammoment;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 内存日志缓冲区，用于在插件页面展示运行日志。
 * 不做持久化，插件重启后日志清空。最多保留 500 条，超过 2 小时的条目定期清理。
 */
@Component
public class LogBuffer {

    private static final int MAX_SIZE = 500;
    private static final Duration RETAIN_DURATION = Duration.ofHours(2);
    private final Deque<LogEntry> buffer = new ConcurrentLinkedDeque<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-moment-log-cleaner");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean enabled = false;

    @PostConstruct
    void init() {
        // 每 10 分钟清理一次超过保留时间的旧日志
        cleaner.scheduleAtFixedRate(this::cleanup, 10, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    void destroy() {
        cleaner.shutdownNow();
        buffer.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            buffer.clear();
        }
    }

    public void add(String level, String source, String message) {
        if (!enabled) return;
        buffer.addLast(new LogEntry(Instant.now(), level, source, message));
        // 超出容量上限时丢弃最旧的条目
        while (buffer.size() > MAX_SIZE) {
            buffer.pollFirst();
        }
    }

    public List<LogEntry> getLogs() {
        return List.copyOf(buffer);
    }

    public void clear() {
        buffer.clear();
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minus(RETAIN_DURATION);
        buffer.removeIf(e -> e.timestamp().isBefore(cutoff));
    }

    public record LogEntry(Instant timestamp, String level, String source, String message) {}
}
