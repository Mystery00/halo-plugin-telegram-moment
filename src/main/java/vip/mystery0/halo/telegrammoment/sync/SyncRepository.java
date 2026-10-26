package vip.mystery0.halo.telegrammoment.sync;

import java.util.List;

public interface SyncRepository {
    SyncRecord get(String name);
    SyncRecord save(SyncRecord record);
    List<SyncRecord> records(long botId);
    BotCursor cursor(long botId);
    void saveCursor(BotCursor cursor);
}
