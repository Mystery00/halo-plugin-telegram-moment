package vip.mystery0.halo.telegrammoment;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/** 有界日志在读取和写入时清理，不创建额外后台线程。 */
@Component
@Slf4j
public class LogBuffer {
    private final ArrayDeque<LogEntry> buffer = new ArrayDeque<>();
    private volatile boolean enabled;
    public boolean isEnabled() { return enabled; }
    public synchronized void setEnabled(boolean value) { enabled = value; if (!value) buffer.clear(); }
    public synchronized void add(String level, String source, String message) {
        String safe = redact(message);
        if (level.equals("ERROR")) log.error("{}: {}", source, safe);
        else if (level.equals("WARN")) log.warn("{}: {}", source, safe);
        else if (level.equals("DEBUG")) log.debug("{}: {}", source, safe);
        else log.info("{}: {}", source, safe);
        if (!enabled) return;
        clean(); buffer.addLast(new LogEntry(Instant.now(), level, source, safe));
        while (buffer.size() > 500) buffer.removeFirst();
    }
    public synchronized List<LogEntry> getLogs() { clean(); return List.copyOf(buffer); }
    public synchronized void clear() { buffer.clear(); }
    private void clean() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(2));
        while (!buffer.isEmpty() && buffer.peekFirst().timestamp().isBefore(cutoff)) buffer.removeFirst();
    }
    public static String redact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("[0-9]+:[A-Za-z0-9_-]+", "[REDACTED]");
        return result.length() > 500 ? result.substring(0, 500) : result;
    }
    public record LogEntry(Instant timestamp, String level, String source, String message) { }
}
