package vip.mystery0.halo.telegrammoment.sync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import run.halo.app.extension.Metadata;

/** 测试存储按序列化副本读写，模拟进程重启后对象不能共享的边界。 */
public class MemoryRepository implements SyncRepository {
    public final Map<String, SyncRecord> data = new HashMap<>();
    private final Map<Long, BotCursor> cursors = new HashMap<>();
    public boolean failSave;
    private static <T> T copy(T value, Class<T> type) { return Jsons.MAPPER.convertValue(value, type); }
    public SyncRecord get(String name) { return copy(data.get(name), SyncRecord.class); }
    public SyncRecord save(SyncRecord record) {
        if (failSave) throw new IllegalStateException("Storage unavailable");
        var result = copy(record, SyncRecord.class); data.put(record.getMetadata().getName(), result); return result;
    }
    public List<SyncRecord> records(long botId) {
        return data.values().stream().filter(r -> r.getSpec().getBotId() == botId).map(r -> copy(r, SyncRecord.class)).toList();
    }
    public BotCursor cursor(long botId) {
        if (cursors.containsKey(botId)) return copy(cursors.get(botId), BotCursor.class);
        var cursor = new BotCursor(); var metadata = new Metadata(); metadata.setName("bot-" + botId); cursor.setMetadata(metadata); return cursor;
    }
    public void saveCursor(BotCursor cursor) { cursors.put(Long.parseLong(cursor.getMetadata().getName().substring(4)), copy(cursor, BotCursor.class)); }
}
