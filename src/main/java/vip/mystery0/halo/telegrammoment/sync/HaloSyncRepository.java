package vip.mystery0.halo.telegrammoment.sync;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import static run.halo.app.extension.index.query.QueryFactory.equal;

@Component
@RequiredArgsConstructor
public class HaloSyncRepository implements SyncRepository {
    private final ReactiveExtensionClient client;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    public SyncRecord get(String name) { return client.fetch(SyncRecord.class, name).block(TIMEOUT); }
    public SyncRecord save(SyncRecord record) {
        return (record.getMetadata().getVersion() == null ? client.create(record) : client.update(record)).block(TIMEOUT);
    }
    public List<SyncRecord> records(long botId) {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(equal("spec.botId", Long.toString(botId))));
        return client.listAll(SyncRecord.class, options, Sort.unsorted()).collectList().block(TIMEOUT);
    }
    public BotCursor cursor(long botId) {
        var cursor = client.fetch(BotCursor.class, "bot-" + botId).block(TIMEOUT);
        if (cursor != null) return cursor;
        cursor = new BotCursor(); var metadata = new Metadata(); metadata.setName("bot-" + botId); cursor.setMetadata(metadata);
        return cursor;
    }
    public void saveCursor(BotCursor cursor) {
        (cursor.getMetadata().getVersion() == null ? client.create(cursor) : client.update(cursor)).block(TIMEOUT);
    }
}
