package vip.mystery0.halo.telegrammoment.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import vip.mystery0.halo.telegrammoment.LogBuffer;
import vip.mystery0.halo.telegrammoment.publisher.SyncPublisher;
import vip.mystery0.halo.telegrammoment.sync.SyncRepository;
import vip.mystery0.halo.telegrammoment.sync.SyncCoordinator;
import vip.mystery0.halo.telegrammoment.sync.SyncRecord;
import vip.mystery0.halo.telegrammoment.sync.Jsons;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.TelegramApi;
import vip.mystery0.halo.telegrammoment.telegram.TelegramApiException;
import vip.mystery0.halo.telegrammoment.publisher.HaloMomentGateway;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.attachment.Policy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.core.type.TypeReference;

@Component
@RequiredArgsConstructor
public class TelegramBotService {
    private final ReactiveExtensionClient client;
    private final SchemeManager schemes;
    private final SyncRepository repository;
    private final SyncCoordinator coordinator;
    private final SyncPublisher publisher;
    private final LogBuffer logs;
    private final AtomicLong generation = new AtomicLong();
    private volatile String phase = "STOPPED";
    private volatile String username = "";
    private volatile long botId;
    private volatile String error = "";
    private volatile String appliedFingerprint = "";
    private volatile Instant lastPoll;
    private volatile Instant lastPublished;
    private volatile long pendingCount;
    private volatile long failedCount;
    private ScheduledExecutorService workers;
    private TelegramApi api;

    public synchronized void startBot() {
        if (workers != null) return;
        phase = "STARTING"; error = "";
        try {
            var settings = new vip.mystery0.halo.telegrammoment.config.TelegramSettingsStore(client).load(true);
            logs.setEnabled(settings.isEnableLogBuffer());
            logs.add("INFO", "Bot", "Starting bot: validating configuration and Halo dependencies");
            if (!settings.hasValidToken()) { phase = "UNCONFIGURED"; error = "Bot token is required"; logs.add("WARN", "Bot", error); return; }
            settings.validate();
            if (client.fetch(User.class, settings.getMomentOwner()).block(Duration.ofSeconds(15)) == null)
                throw new IllegalArgumentException("Moment owner does not exist");
            if (client.fetch(Policy.class, settings.getStoragePolicy()).block(Duration.ofSeconds(15)) == null)
                throw new IllegalArgumentException("Storage policy does not exist");
            if (schemes.fetch(HaloMomentGateway.GVK).isEmpty()) throw new IllegalArgumentException("Moments plugin is not enabled");
            api = new TelegramApi(settings);
            logs.add("INFO", "Connection", "Calling getMe to verify bot identity; requestTimeoutSeconds=30");
            var me = api.getMe(); botId = me.path("id").asLong(); username = me.path("username").asText();
            if (botId <= 0 || !me.path("is_bot").asBoolean()) throw new IllegalArgumentException("Invalid bot identity");
            logs.add("INFO", "Connection", "Bot identity verified: bot=" + botId + " username=" + username);
            logs.add("INFO", "Bot", "Reception configured: channelEnabled=" + settings.isChannelEnabled()
                + " channel=" + (settings.getChannelId() == null || settings.getChannelId().isBlank() ? "all accessible channels" : settings.getChannelId().trim())
                + " privateEnabled=" + settings.isPrivateEnabled() + " albumDelaySeconds=" + settings.getMediaDelaySeconds()
                + " repliesEnabled=" + (settings.isChannelReplyEnabled() || settings.isPrivateReplyEnabled()));
            long session = generation.incrementAndGet(); long id = botId; TelegramApi transport = api;
            appliedFingerprint = settings.fingerprint();
            workers = Executors.newScheduledThreadPool(2, runnable -> {
                var thread = new Thread(runnable, "telegram-moment-worker"); thread.setDaemon(true); return thread;
            });
            phase = "RUNNING";
            workers.execute(() -> poll(session, id, transport, settings));
            workers.scheduleWithFixedDelay(() -> work(session, id, transport, settings), 1, 1, TimeUnit.SECONDS);
            logs.add("INFO", "Bot", "Bot started");
        } catch (Exception e) {
            phase = "CONFIG_ERROR"; error = safeError(e);
            if (api != null) { api.close(); api = null; }
            logs.add("ERROR", "Bot", "Startup failed: " + error + "; fix configuration/connectivity and restart bot");
        }
    }

    private void poll(long session, long id, TelegramApi transport, TelegramSetting settings) {
        int failures = 0;
        long lastHeartbeat = 0;
        logs.add("INFO", "Polling", "Long polling started: bot=" + id + " timeoutSeconds=20; idle heartbeat every 60 seconds");
        while (generation.get() == session && !Thread.currentThread().isInterrupted()) {
            String operation = "getUpdates";
            try {
                long offset;
                synchronized (coordinator) { offset = repository.cursor(id).getSpec().getNextOffset(); }
                long started = System.nanoTime();
                if (settings.isDebugMode()) logs.add("DEBUG", "Polling", "Request started: method=getUpdates offset=" + offset);
                var updates = transport.updates(offset);
                if (generation.get() != session) return;
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (failures > 0) logs.add("INFO", "Connection", "Polling recovered after failures=" + failures);
                if (!updates.isEmpty() || lastHeartbeat == 0 || System.currentTimeMillis() - lastHeartbeat >= 60000 || settings.isDebugMode()) {
                    logs.add("INFO", "Polling", "getUpdates succeeded: updates=" + updates.size() + " offset=" + offset
                        + " durationMs=" + duration + (updates.isEmpty() ? "; connection healthy, waiting for messages" : ""));
                    lastHeartbeat = System.currentTimeMillis();
                }
                operation = "persist received update";
                for (var update : updates) {
                    if (generation.get() != session) return;
                    String context = updateContext(update);
                    logs.add("INFO", "Receive", "Received " + context);
                    String outcome = coordinator.accept(id, username, update, settings);
                    logs.add("INFO", "Receive", outcome + " " + context);
                }
                if (generation.get() != session) return;
                lastPoll = Instant.now(); phase = "RUNNING"; error = ""; failures = 0;
                if (updates.isEmpty()) sleep(200);
            } catch (Exception e) {
                if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
                error = safeError(e);
                if (e instanceof TelegramApiException telegram && !telegram.retryable()) {
                    phase = "CONFIG_ERROR";
                    logs.add("ERROR", "Polling", operation + " failed: " + error + "; polling stopped, fix the cause and restart bot");
                    return;
                }
                phase = "RECONNECTING";
                failures++;
                int delay = e instanceof TelegramApiException telegram && telegram.retryAfter() > 0
                    ? telegram.retryAfter() : Math.min(60, 1 << Math.min(failures, 6));
                logs.add("WARN", "Polling", operation + " failed: " + error + " consecutiveFailures=" + failures
                    + " retryInSeconds=" + delay + "; unpersisted updates will be requested again");
                if (!sleep(delay * 1000L)) return;
            }
        }
    }

    private void work(long session, long id, TelegramApi transport, TelegramSetting settings) {
        if (generation.get() != session || phase.equals("CONFIG_ERROR")) return;
        try {
            synchronized (coordinator) {
                for (var record : coordinator.ready(id)) {
                    if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
                    boolean succeeded = false;
                    long started = System.nanoTime();
                    logs.add("INFO", "Sync", "Processing started: task=" + record.getMetadata().getName()
                        + " action=" + record.getSpec().getAction() + " chat=" + record.getSpec().getChatId()
                        + " members=" + record.members().size() + " attempt=" + (record.getSpec().getAttempts() + 1) + "/5");
                    try {
                        publisher.publish(record, settings, transport);
                        succeeded = List.of("SUCCEEDED", "DELETED").contains(record.getSpec().getState());
                        if (succeeded) lastPublished = Instant.now();
                        logs.add("INFO", "Sync", "Processing completed: task=" + record.getMetadata().getName()
                            + " state=" + record.getSpec().getState() + " moment=" + record.getSpec().getMomentName()
                            + " durationMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                            + (record.getSpec().getState().equals("IGNORED") ? "; skipped because an album member matched a blocked hashtag" : ""));
                    } catch (Exception e) {
                        if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
                        var latest = repository.get(record.getMetadata().getName());
                        if (latest == null) continue;
                        var spec = latest.getSpec(); spec.setAttempts(spec.getAttempts() + 1);
                        spec.setError(safeError(e));
                        boolean retryable = !(e instanceof IllegalArgumentException)
                            && (!(e instanceof TelegramApiException telegram) || telegram.retryable());
                        spec.setState(retryable && spec.getAttempts() < 5 ? "PENDING" : "FAILED");
                        int retryAfter = e instanceof TelegramApiException telegram ? telegram.retryAfter() : 0;
                        spec.setReadyAt(System.currentTimeMillis() + Math.max(retryAfter, 1L << Math.min(5, spec.getAttempts())) * 1000L);
                        repository.save(latest); logs.add("ERROR", "Sync", "Processing failed: task=" + latest.getMetadata().getName()
                            + " attempt=" + spec.getAttempts() + "/5 state=" + spec.getState() + " reason=" + spec.getError()
                            + (spec.getState().equals("PENDING") ? " retryInSeconds=" + Math.max(retryAfter, 1L << Math.min(5, spec.getAttempts()))
                                : "; automatic retry stopped, fix the cause and retry from the management page"));
                        record = latest;
                    }
                    if (generation.get() != session) return;
                    if ((succeeded || record.getSpec().getState().equals("FAILED")) && !coordinator.hasCommand(id, record))
                        reply(id, record, succeeded, settings, transport);
                }
                var records = repository.records(id);
                pendingCount = records.stream().filter(r -> r.getSpec().getState().equals("PENDING")).count();
                failedCount = records.stream().filter(r -> r.getSpec().getState().equals("FAILED")).count();
                for (var record : records) {
                    if (generation.get() != session) return;
                    if (!record.getSpec().getCleanup().isEmpty())
                        publisher.cleanup(record);
                }
                processTelegramCleanup(session, id, transport);
                processCommands(session, id, transport);
                processTelegramCleanup(session, id, transport);
                deleteDueReplies(id, transport);
            }
        } catch (Exception e) {
            if (generation.get() == session) logs.add("ERROR", "Worker", safeError(e));
        }
    }

    private void processCommands(long session, long id, TelegramApi transport) {
        for (var pending : coordinator.commandRequests(id).stream().limit(20).toList()) {
            if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
            var request = pending;
            try {
                if (request.result().isBlank()) {
                    var record = repository.get(request.recordName());
                    var result = commandResult(request, record, id);
                    if (result == null) continue;
                    request = coordinator.resolveCommand(id, request, result.text(), result.succeeded());
                    if (request == null) continue;
                }
            } catch (Exception e) {
                // 存储或队列容量错误保留命令，不消耗 Telegram 发送的重试次数。
                logs.add("WARN", "Command", "Command result could not be persisted: update=" + request.updateId() + "; " + safeError(e));
                continue;
            }
            try {
                long sent = transport.reply(request.chatId(), request.messageId(), request.result(), true);
                if (sent <= 0) throw new TelegramApiException(502, 0);
                coordinator.completeCommand(id, request, sent);
                logs.add("INFO", "Command", "Command feedback delivered: update=" + request.updateId() + " chat=" + request.chatId());
            } catch (Exception e) {
                if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
                boolean retryable = !(e instanceof TelegramApiException telegram) || telegram.retryable();
                int delay = e instanceof TelegramApiException telegram ? telegram.retryAfter() : 0;
                boolean retry = coordinator.retryCommand(id, request, retryable, delay);
                logs.add("WARN", "Command", "Command feedback failed: update=" + request.updateId()
                    + " retry=" + retry + "; Halo result is unchanged; " + safeError(e));
            }
        }
    }

    private CommandResult commandResult(SyncCoordinator.CommandRequest request, SyncRecord record, long bot) {
        if (record == null || record.getSpec().getBotId() != bot)
            return new CommandResult("未找到对应的同步记录，请确认回复的是原消息。", false);
        var spec = record.getSpec();
        if (spec.getRevision() < request.revision()) return null;
        if (!spec.getAction().equals(request.action()))
            return new CommandResult("该命令已被后续操作替代。", false);
        if (spec.getState().equals("PENDING")) return null;
        if (spec.getState().equals("FAILED")) {
            if (spec.getError().contains("No synchronized moment was found"))
                return new CommandResult("未找到对应瞬间：该消息可能尚未同步，或旧相册缺少成员关联。", false);
            if (spec.getError().contains("Legacy album mapping is incomplete"))
                return new CommandResult("旧相册关联不完整，已保留原内容和附件，需要完整相册后再重试。", false);
            return new CommandResult("命令执行失败，请在 Halo 插件页面查看原因并重试。", false);
        }
        if (spec.getState().equals("IGNORED"))
            return new CommandResult("消息命中屏蔽标签，未重新同步。", false);
        if (spec.getState().equals("DELETED"))
            return new CommandResult("对应瞬间已删除（相册按整组处理）。", true);
        if (spec.getState().equals("SUCCEEDED"))
            return new CommandResult("原消息已重新同步，保持同一个瞬间。", true);
        return null;
    }

    private void processTelegramCleanup(long session, long id, TelegramApi transport) {
        for (var request : coordinator.cleanupRequests(id).stream().limit(20).toList()) {
            if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
            try {
                transport.deleteMessage(request.chatId(), request.messageId());
                coordinator.finishCleanup(id, request, false, false, 0);
                logs.add("INFO", "Command", "Telegram message cleanup completed: chat=" + request.chatId()
                    + " message=" + request.messageId() + " kind=" + request.kind());
            } catch (Exception e) {
                if (generation.get() != session || Thread.currentThread().isInterrupted()) return;
                boolean retryable = !(e instanceof TelegramApiException telegram) || telegram.retryable();
                int delay = e instanceof TelegramApiException telegram ? telegram.retryAfter() : 0;
                boolean retry = coordinator.finishCleanup(id, request, true, retryable, delay);
                logs.add("WARN", "Command", "Telegram message cleanup failed: chat=" + request.chatId()
                    + " message=" + request.messageId() + " kind=" + request.kind() + " retry=" + retry
                    + "; Halo result is unchanged; " + safeError(e));
            }
        }
    }

    private record CommandResult(String text, boolean succeeded) { }

    private void reply(long id, SyncRecord record, boolean succeeded, TelegramSetting settings, TelegramApi transport) {
        if (record.members().isEmpty()) return;
        var first = record.members().getFirst(); boolean channel = first.chatType().equals("channel");
        if (!(channel ? settings.isChannelReplyEnabled() : settings.isPrivateReplyEnabled())) return;
        try {
            long sent = transport.reply(first.chatId(), first.messageId(), succeeded ? "瞬间同步完成" : "瞬间同步失败，请在 Halo 插件页面查看并重试");
            if (sent > 0) coordinator.rememberReply(id, first.chatId(), sent,
                channel ? settings.getChannelReplyDeleteSeconds() : settings.getPrivateReplyDeleteSeconds());
        } catch (Exception e) { logs.add("WARN", "Reply", "Reply could not be delivered"); }
    }

    private void deleteDueReplies(long id, TelegramApi transport) {
        var cursor = repository.cursor(id);
        Map<String, Long> replies = Jsons.read(cursor.getSpec().getRepliesJson(), new TypeReference<>() { });
        boolean changed = false;
        for (var entry : replies.entrySet()) {
            if (entry.getValue() > 0 && entry.getValue() <= System.currentTimeMillis()) {
                var ids = entry.getKey().split(":");
                try { transport.deleteReply(Long.parseLong(ids[0]), Long.parseLong(ids[1])); entry.setValue(0L); changed = true; }
                catch (TelegramApiException e) {
                    if (!e.retryable()) { entry.setValue(0L); changed = true; }
                }
            }
        }
        if (changed) { cursor.getSpec().setRepliesJson(Jsons.write(replies)); repository.saveCursor(cursor); }
    }

    public synchronized void stopBot() {
        generation.incrementAndGet();
        if (api != null) { api.close(); api = null; }
        if (workers != null) {
            workers.shutdownNow();
            try {
                if (!workers.awaitTermination(55, TimeUnit.SECONDS)) { phase = "ERROR"; throw new IllegalStateException("Previous worker has not stopped"); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Stop interrupted"); }
            workers = null;
        }
        phase = "STOPPED"; logs.add("INFO", "Bot", "Bot stopped");
    }
    public synchronized void restartBot() { stopBot(); startBot(); }
    public boolean isRunning() { return phase.equals("RUNNING") || phase.equals("RECONNECTING"); }
    public String getBotUsername() { return username; }
    public Status status() {
        boolean pending = false;
        try { pending = !appliedFingerprint.isEmpty() && !appliedFingerprint.equals(settings().fingerprint()); }
        catch (Exception e) { pending = true; }
        return new Status(phase, isRunning(), username, botId, error, lastPoll, lastPublished, pending, pendingCount, failedCount, logs.isEnabled());
    }
    public List<TaskView> tasks() {
        if (botId <= 0) return List.of();
        synchronized (coordinator) {
            return repository.records(botId).stream().filter(r -> List.of("FAILED", "PENDING").contains(r.getSpec().getState()))
                .limit(100).map(r -> new TaskView(r.getMetadata().getName(), r.getSpec().getState(), r.getSpec().getAttempts(), r.getSpec().getError())).toList();
        }
    }
    public void retry(String name) { coordinator.retry(botId, name); logs.add("INFO", "Sync", "Manual retry requested: task=" + name); }
    private static String updateContext(com.fasterxml.jackson.databind.JsonNode update) {
        for (String kind : List.of("channel_post", "edited_channel_post", "message", "edited_message")) {
            if (update.has(kind)) {
                var message = update.path(kind);
                return "update=" + update.path("update_id").asLong() + " type=" + kind
                    + " chat=" + message.path("chat").path("id").asLong() + " message=" + message.path("message_id").asLong()
                    + " album=" + message.has("media_group_id");
            }
        }
        return "update=" + update.path("update_id").asLong() + " type=unsupported";
    }
    private TelegramSetting settings() { return new vip.mystery0.halo.telegrammoment.config.TelegramSettingsStore(client).load(false); }
    private static boolean sleep(long millis) {
        try { Thread.sleep(millis); return true; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    public static String safeError(Exception error) {
        if (error instanceof TelegramApiException) return error.getMessage();
        if (error instanceof IllegalArgumentException && error.getStackTrace().length > 0
            && error.getStackTrace()[0].getClassName().startsWith("vip.mystery0.halo.telegrammoment"))
            return LogBuffer.redact(error.getMessage());
        return "Operation failed (" + error.getClass().getSimpleName() + ")";
    }
    public record TaskView(String name, String state, int attempts, String error) { }
    public record Status(String phase, boolean running, String username, long botId, String error,
        Instant lastPoll, Instant lastPublished, boolean configPending, long pendingCount, long failedCount, boolean logEnabled) { }
}
