package vip.mystery0.halo.telegrammoment.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
                String command = incoming.chatType().equals("channel") ? incoming.text().trim() : "";
                boolean remove = command.equals("/rm");
                boolean redo = command.equals("/redo");
                if (remove || redo) {
                    outcome = "Ignored: command requires a supported reply target in the same chat";
                    var target = message.path("reply_to_message");
                    long targetId = target.path("message_id").asLong();
                    if (targetId > 0 && target.path("chat").path("id").asLong(incoming.chatId()) == incoming.chatId()) {
                        var record = find(botId, incoming.chatId(), targetId);
                        if (record == null) {
                            var targetMessage = MessageParser.parse(target, settings);
                            if (targetMessage.isPresent()) record = merge(botId, targetMessage.get().withUpdate(updateId), settings, remove ? "DELETE" : "PUBLISH");
                            else if (remove) record = merge(botId, new IncomingMessage(incoming.chatId(), targetId,
                                incoming.date(), "channel", "", "", null, "", "", "", "", updateId), settings, "DELETE");
                        }
                        if (record != null && record.getSpec().getLastUpdate() < updateId) {
                            record.getSpec().setAction(remove ? "DELETE" : "PUBLISH");
                            schedule(record, updateId, 0); repository.save(record);
                        }
                        if (record != null) outcome = queued(record);
                    }
                } else outcome = queued(merge(botId, incoming, settings));
            }
        }
        // 记录保存失败时不会推进游标，因此 Telegram 能重新投递。
        cursor.getSpec().setNextOffset(updateId + 1); repository.saveCursor(cursor);
        return outcome;
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
        var spec = record.getSpec(); spec.setLastUpdate(update); spec.setRevision(update + 1);
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
        Map<String, Long> replies = Jsons.read(cursor.getSpec().getRepliesJson(), new TypeReference<>() { });
        replies.put(chat + ":" + message, deleteSeconds > 0 ? System.currentTimeMillis() + deleteSeconds * 1000L : 0L);
        while (replies.size() > 2000) replies.remove(replies.keySet().iterator().next());
        cursor.getSpec().setRepliesJson(Jsons.write(replies)); repository.saveCursor(cursor);
    }

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
