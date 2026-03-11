package vip.mystery0.halo.telegrammoment;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * 插件统一日志工具。
 * 调试模式开启时，日志同时通过 System.out/err 打印到控制台；
 * 日志记录开启时，日志同时写入内存缓冲区供插件页面展示。
 */
public final class PluginLogger {

    private static final AtomicBoolean debugMode = new AtomicBoolean(false);
    private static volatile LogBuffer logBuffer;

    private PluginLogger() {
    }

    public static void setDebugMode(boolean enabled) {
        debugMode.set(enabled);
    }

    public static boolean isDebugMode() {
        return debugMode.get();
    }

    /** 由插件生命周期管理，启动时注入，停止时置 null */
    public static void setLogBuffer(LogBuffer lb) {
        logBuffer = lb;
    }

    public static void info(Logger log, String msg, Object... args) {
        record("INFO", log, msg, args);
        if (debugMode.get()) {
            System.out.println("[INFO][" + shortName(log) + "] " + format(msg, args));
        }
        log.info(msg, args);
    }

    public static void warn(Logger log, String msg, Object... args) {
        record("WARN", log, msg, args);
        if (debugMode.get()) {
            System.out.println("[WARN][" + shortName(log) + "] " + format(msg, args));
            printThrowable(System.out, args);
        }
        log.warn(msg, args);
    }

    public static void error(Logger log, String msg, Object... args) {
        record("ERROR", log, msg, args);
        if (debugMode.get()) {
            System.err.println("[ERROR][" + shortName(log) + "] " + format(msg, args));
            printThrowable(System.err, args);
        }
        log.error(msg, args);
    }

    public static void debug(Logger log, String msg, Object... args) {
        record("DEBUG", log, msg, args);
        if (debugMode.get()) {
            System.out.println("[DEBUG][" + shortName(log) + "] " + format(msg, args));
        }
        log.debug(msg, args);
    }

    // ---- 私有方法 ----

    private static void record(String level, Logger log, String msg, Object[] args) {
        LogBuffer lb = logBuffer;
        if (lb != null && lb.isEnabled()) {
            lb.add(level, shortName(log), format(msg, args));
        }
    }

    /** 取 logger 名称的最后一段（类名），避免日志前缀过长 */
    private static String shortName(Logger log) {
        String name = log.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /**
     * 将 SLF4J 风格的 {} 占位符替换为参数值。
     * Throwable 类型的参数不参与占位符替换。
     */
    private static String format(String msg, Object[] args) {
        if (args == null || args.length == 0) return msg;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < msg.length()) {
            if (i < msg.length() - 1
                    && msg.charAt(i) == '{'
                    && msg.charAt(i + 1) == '}'
                    && argIdx < args.length
                    && !(args[argIdx] instanceof Throwable)) {
                sb.append(args[argIdx++]);
                i += 2;
            } else {
                sb.append(msg.charAt(i++));
            }
        }
        return sb.toString();
    }

    /** 若最后一个参数是 Throwable，打印堆栈 */
    private static void printThrowable(java.io.PrintStream out, Object[] args) {
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable t) {
            t.printStackTrace(out);
        }
    }
}
