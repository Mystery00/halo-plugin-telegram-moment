package vip.mystery0.halo.telegrammoment.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;
import vip.mystery0.halo.telegrammoment.telegram.TelegramCommand;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Metadata;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;
import vip.mystery0.halo.telegrammoment.telegram.MessageParser;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;

@Component
public class SyncCoordinator {
    protected final SyncRepository repository;
    public SyncCoordinator(SyncRepository repository) { this.repository = repository; }
    public synchronized String accept(long botId, JsonNode update, TelegramSetting settings) {
        return accept(botId, "", update, settings);
    }

    public synchronized String accept(long botId, String botUsername, JsonNode update, TelegramSetting settings) {
        if (!update.has("update_id")) return "Ignored: missing update ID";
        long updateId = update.path("update_id").asLong();
        var cursor = repository.cursor(botId);
        if (updateId < cursor.getSpec().getNextOffset()) return "Ignored: duplicate or older update";
        String outcome = "Ignored: unsupported update type";
        JsonNode message = null;
        for (String field : List.of("channel_post", "edited_channel_post", "message", "edited_message")) {
            if (update.has(field)) { message = update.path(field); break; }
        }
        if (message != null) {
            String rejection = MessageParser.rejectionReason(message, settings);
            outcome = "Ignored: " + (rejection.isEmpty() ? "no supported text or media" : rejection);
            var parsed = MessageParser.parse(message, settings);
            if (parsed.isPresent() && ownReply(cursor, parsed.get().chatId(), parsed.get().messageId()))
                outcome = "Ignored: own reply message";
            if (parsed.isPresent() && !ownReply(cursor, parsed.get().chatId(), parsed.get().messageId())) {
                IncomingMessage incoming = parsed.get().withUpdate(updateId);
                // caption 中的斜杠文字仍是正文；只有独立文字消息才解释为命令。
                var command = TelegramCommand.parse(message.path("text").asText(""), botUsername);
                if (command.kind().equals("NONE")) outcome = queued(merge(botId, incoming, settings));
                else if (command.kind().equals("OTHER_BOT")) outcome = "Ignored: command is addressed to another bot";
                else outcome = acceptCommand(cursor, botId, incoming, message, command, settings);
            }
        }
        // 命令提示与任务同样先持久化；保存失败时不会向 Telegram 确认更新。
        cursor.getSpec().setNextOffset(updateId + 1); repository.saveCursor(cursor);
        return outcome;
    }

    private String acceptCommand(BotCursor cursor, long botId, IncomingMessage incoming, JsonNode message,
        TelegramCommand command, TelegramSetting settings) {
        Map<String, CommandRequest> requests = commands(cursor);
        String requestKey = Long.toString(incoming.updateId());
        if (requests.size() >= 1000 && !requests.containsKey(requestKey))
            throw new IllegalStateException("Command queue is full; wait for Telegram connectivity to recover");
        String hint = command.hint();
        SyncRecord record = null;
        if (!command.kind().equals("HINT")) {
            var target = message.path("reply_to_message");
            if (target.path("message_id").asLong() <= 0) hint = "请先回复需要操作的原消息，再发送命令。" + TelegramCommand.HELP;
            else if (target.path("chat").path("id").asLong() != incoming.chatId()
                || !target.path("chat").path("type").asText().equals(incoming.chatType())
                || !MessageParser.rejectionReason(target, settings).isEmpty()
                || ownReply(cursor, incoming.chatId(), target.path("message_id").asLong()))
                hint = "只能操作当前会话中来自已授权来源的原消息，不能操作 Bot 回执。";
            else if (!TelegramCommand.parse(target.path("text").asText(""), "").kind().equals("NONE"))
                hint = "请回复要同步的正文消息，不要回复命令。";
            else {
                long targetId = target.path("message_id").asLong();
                record = find(botId, incoming.chatId(), targetId);
                var targetMessage = MessageParser.parse(target, settings);
                if (command.kind().equals("PUBLISH") && targetMessage.isEmpty())
                    hint = "这条消息没有可同步的文字或媒体。";
                else {
                    // 重新同步使用 Telegram 回复携带的当前快照，只更新对应成员，保留相册其他成员。
                    if (command.kind().equals("PUBLISH") || record == null) {
                        var snapshot = targetMessage.orElseGet(() -> new IncomingMessage(incoming.chatId(), targetId,
                            target.path("date").asLong(), incoming.chatType(), "", "", null, "", "", "", "", 0));
                        record = merge(botId, snapshot.withUpdate(incoming.updateId()), settings, command.kind());
                    } else if (record.getSpec().getLastUpdate() < incoming.updateId()) {
                        record.getSpec().setAction(command.kind());
                        schedule(record, incoming.updateId(), 0); record = repository.save(record);
                    }
                }
            }
        }
        int retention = incoming.chatType().equals("channel") ? settings.getChannelReplyDeleteSeconds() : settings.getPrivateReplyDeleteSeconds();
        var request = new CommandRequest(incoming.updateId(), incoming.chatId(), incoming.messageId(), incoming.chatType(),
            command.kind(), record == null ? "" : record.getMetadata().getName(), record == null ? 0 : record.getSpec().getRevision(),
            record == null ? List.of() : record.members().stream().map(IncomingMessage::messageId).toList(),
            settings.isCommandCleanupEnabled(), settings.isDeleteSourceMessages(), retention, hint, 0, 0);
        requests.putIfAbsent(requestKey, request);
        cursor.getSpec().setCommandsJson(Jsons.write(requests));
        return record == null ? "Queued command feedback" : queued(record);
    }

    private static String queued(SyncRecord record) {
        return "Queued task=" + record.getMetadata().getName() + " action=" + record.getSpec().getAction()
            + " members=" + record.members().size() + " waitMs=" + Math.max(0, record.getSpec().getReadyAt() - System.currentTimeMillis());
    }

    private SyncRecord merge(long botId, IncomingMessage incoming, TelegramSetting settings) {
        return merge(botId, incoming, settings, "PUBLISH");
    }

    private SyncRecord merge(long botId, IncomingMessage incoming, TelegramSetting settings, String action) {
        var record = find(botId, incoming.chatId(), incoming.messageId());
        String key = name(botId, incoming.chatId(), incoming.albumId().isBlank()
            ? "message:" + incoming.messageId() : "album:" + incoming.albumId());
        if (record == null) record = repository.get(key);
        if (record != null && record.getSpec().getLastUpdate() >= incoming.updateId()) return record;
        if (record == null || !List.of("PENDING", "FAILED").contains(record.getSpec().getState())) {
            long pending = repository.records(botId).stream().filter(r -> List.of("PENDING", "FAILED").contains(r.getSpec().getState())).count();
            if (pending >= 1000) throw new IllegalStateException("Sync queue is full; retry failed tasks");
        }
        if (record == null) {
            record = new SyncRecord(); var metadata = new Metadata(); metadata.setName(key); record.setMetadata(metadata);
            record.getSpec().setBotId(botId); record.getSpec().setChatId(incoming.chatId());
        }
        var members = new ArrayList<>(record.members());
        members.removeIf(m -> m.messageId() == incoming.messageId());
        if (members.size() >= 10) throw new IllegalStateException("Media group exceeds 10 members");
        members.add(incoming); members.sort(Comparator.comparingLong(IncomingMessage::messageId)); record.members(members);
        record.getSpec().setAction(action);
        schedule(record, incoming.updateId(), incoming.albumId().isBlank() ? 0 : settings.getMediaDelaySeconds() * 1000L);
        return repository.save(record);
    }

    private void schedule(SyncRecord record, long update, long delay) {
        var spec = record.getSpec();
        // 旧版本的 DELETED 也可能表示未找到瞬间，不能据此授权删除 Telegram 源消息。
        if (spec.getState().equals("SUCCEEDED")) spec.setMomentKnown(true);
        spec.setLastUpdate(update); spec.setRevision(update + 1);
        spec.setReadyAt(System.currentTimeMillis() + delay); spec.setAttempts(0); spec.setState("PENDING"); spec.setError("");
    }

    public synchronized SyncRecord find(long bot, long chat, long message) {
        return repository.records(bot).stream().filter(r -> r.getSpec().getChatId() == chat
            && r.members().stream().anyMatch(m -> m.messageId() == message)).findFirst().orElse(null);
    }

    public synchronized List<SyncRecord> ready(long botId) {
        return repository.records(botId).stream().filter(r -> r.getSpec().getState().equals("PENDING")
            && r.getSpec().getReadyAt() <= System.currentTimeMillis()).sorted(Comparator.comparingLong(r -> r.getSpec().getLastUpdate())).toList();
    }

    public synchronized void retry(long botId, String name) {
        SyncRecord record = repository.get(name);
        if (record == null || record.getSpec().getBotId() != botId) throw new IllegalArgumentException("Sync task not found for this bot");
        if (!record.getSpec().getState().equals("FAILED")) return;
        record.getSpec().setState("PENDING"); record.getSpec().setAttempts(0); record.getSpec().setReadyAt(0); record.getSpec().setError("");
        repository.save(record);
    }

    public synchronized void rememberReply(long bot, long chat, long message, int deleteSeconds) {
        var cursor = repository.cursor(bot);
        rememberReply(cursor, chat, message, deleteSeconds); repository.saveCursor(cursor);
    }

    private void rememberReply(BotCursor cursor, long chat, long message, int deleteSeconds) {
        Map<String, Long> replies = Jsons.read(cursor.getSpec().getRepliesJson(), new TypeReference<>() { });
        replies.put(chat + ":" + message, deleteSeconds > 0 ? System.currentTimeMillis() + deleteSeconds * 1000L : 0L);
        while (replies.size() > 2000) replies.remove(replies.keySet().iterator().next());
        cursor.getSpec().setRepliesJson(Jsons.write(replies));
    }

    public synchronized boolean hasCommand(long bot, SyncRecord record) {
        return commands(repository.cursor(bot)).values().stream().anyMatch(request ->
            request.recordName().equals(record.getMetadata().getName()) && request.revision() == record.getSpec().getRevision());
    }

    public synchronized List<CommandRequest> commandRequests(long bot) {
        return commands(repository.cursor(bot)).values().stream()
            .filter(request -> request.readyAt() <= System.currentTimeMillis())
            .sorted(Comparator.comparingLong(CommandRequest::updateId)).toList();
    }

    /** 确认 Halo 结果后先保存清理队列；Telegram 发送或删除失败不能反向重做 Halo 操作。 */
    public synchronized CommandRequest resolveCommand(long bot, CommandRequest request, String result, boolean succeeded) {
        var cursor = repository.cursor(bot);
        var requests = commands(cursor);
        var current = requests.get(Long.toString(request.updateId()));
        if (current == null || !current.result().isBlank()) return current;
        current = current.withResult(result);
        if (succeeded) {
            var cleanup = telegramCleanup(cursor);
            var additions = new LinkedHashMap<String, CleanupRequest>();
            if (current.cleanCommand()) addCleanup(additions, current.chatId(), current.messageId(), "command");
            if (current.deleteSources() && current.action().equals("DELETE"))
                for (long message : current.sourceMessageIds()) addCleanup(additions, current.chatId(), message, "source");
            additions.keySet().removeAll(cleanup.keySet());
            if (cleanup.size() + additions.size() > 2000)
                throw new IllegalStateException("Telegram cleanup queue is full; retry after pending deletions finish");
            cleanup.putAll(additions);
            cursor.getSpec().setTelegramCleanupJson(Jsons.write(cleanup));
        }
        requests.put(Long.toString(current.updateId()), current);
        cursor.getSpec().setCommandsJson(Jsons.write(requests)); repository.saveCursor(cursor);
        return current;
    }

    public synchronized void completeCommand(long bot, CommandRequest request, long replyId) {
        var cursor = repository.cursor(bot); var requests = commands(cursor);
        requests.remove(Long.toString(request.updateId()));
        cursor.getSpec().setCommandsJson(Jsons.write(requests));
        if (replyId > 0) rememberReply(cursor, request.chatId(), replyId, request.deleteSeconds());
        repository.saveCursor(cursor);
    }

    public synchronized boolean retryCommand(long bot, CommandRequest request, boolean retryable, int retryAfter) {
        var cursor = repository.cursor(bot); var requests = commands(cursor);
        var current = requests.get(Long.toString(request.updateId()));
        if (current == null) return false;
        int attempts = current.attempts() + 1;
        boolean retry = retryable && attempts < 5;
        if (retry) requests.put(Long.toString(request.updateId()), current.withRetry(attempts, retryAt(attempts, retryAfter)));
        else requests.remove(Long.toString(request.updateId()));
        cursor.getSpec().setCommandsJson(Jsons.write(requests)); repository.saveCursor(cursor);
        return retry;
    }

    public synchronized List<CleanupRequest> cleanupRequests(long bot) {
        return telegramCleanup(repository.cursor(bot)).values().stream()
            .filter(request -> request.readyAt() <= System.currentTimeMillis()).toList();
    }

    public synchronized boolean finishCleanup(long bot, CleanupRequest request, boolean failed, boolean retryable, int retryAfter) {
        var cursor = repository.cursor(bot); var cleanup = telegramCleanup(cursor);
        String key = request.chatId() + ":" + request.messageId();
        var current = cleanup.get(key);
        if (current == null) return false;
        int attempts = current.attempts() + 1;
        boolean retry = failed && retryable && attempts < 5;
        if (retry) cleanup.put(key, new CleanupRequest(current.chatId(), current.messageId(), current.kind(), attempts, retryAt(attempts, retryAfter)));
        else cleanup.remove(key);
        cursor.getSpec().setTelegramCleanupJson(Jsons.write(cleanup)); repository.saveCursor(cursor);
        return retry;
    }

    private static long retryAt(int attempts, int retryAfter) {
        return System.currentTimeMillis() + Math.max(retryAfter, 1L << Math.min(attempts, 5)) * 1000L;
    }
    private static void addCleanup(Map<String, CleanupRequest> cleanup, long chat, long message, String kind) {
        cleanup.putIfAbsent(chat + ":" + message, new CleanupRequest(chat, message, kind, 0, 0));
    }
    private static Map<String, CommandRequest> commands(BotCursor cursor) {
        return Jsons.read(cursor.getSpec().getCommandsJson(), new TypeReference<>() { });
    }
    private static Map<String, CleanupRequest> telegramCleanup(BotCursor cursor) {
        return Jsons.read(cursor.getSpec().getTelegramCleanupJson(), new TypeReference<>() { });
    }

    public record CommandRequest(long updateId, long chatId, long messageId, String chatType, String action,
        String recordName, long revision, List<Long> sourceMessageIds, boolean cleanCommand, boolean deleteSources,
        int deleteSeconds, String result, int attempts, long readyAt) {
        CommandRequest withResult(String value) {
            return new CommandRequest(updateId, chatId, messageId, chatType, action, recordName, revision,
                sourceMessageIds, cleanCommand, deleteSources, deleteSeconds, value, attempts, readyAt);
        }
        CommandRequest withRetry(int count, long ready) {
            return new CommandRequest(updateId, chatId, messageId, chatType, action, recordName, revision,
                sourceMessageIds, cleanCommand, deleteSources, deleteSeconds, result, count, ready);
        }
    }
    public record CleanupRequest(long chatId, long messageId, String kind, int attempts, long readyAt) { }

    private boolean ownReply(BotCursor cursor, long chat, long message) {
        Map<String, Long> replies = Jsons.read(cursor.getSpec().getRepliesJson(), new TypeReference<>() { });
        return replies.containsKey(chat + ":" + message);
    }

    public static String name(long bot, long chat, String key) {
        try {
            return "tm-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((bot + ":" + chat + ":" + key).getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (Exception e) { throw new IllegalStateException("Cannot derive sync key"); }
    }
}
