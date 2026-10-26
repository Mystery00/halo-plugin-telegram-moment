package vip.mystery0.halo.telegrammoment.publisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Unstructured;
import run.halo.app.extension.Metadata;
import vip.mystery0.halo.telegrammoment.sync.*;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.*;
import static org.assertj.core.api.Assertions.*;

class SyncPublisherTest {
    @Test void recoversUnstagedAttachmentByOwnershipAndKeepsCommittedFile() {
        var deleted = new java.util.ArrayList<String>();
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) {
                return new UploadedMedia("new-file", "/new.png", "PHOTO", "image/png");
            }
            public List<String> owned(String record) { return List.of("orphan-file"); }
            public void delete(String name) { deleted.add(name); }
        };
        var record = record("image", "new");
        new SyncPublisher(gateway, upload, repo).publish(record, new TelegramSetting(), null);
        assertThat(deleted).containsExactly("orphan-file");
        assertThat(record.getSpec().getCleanup()).isEmpty();
    }

    @Test void cleanupFailureRemainsRetryableAndProtectsActualReferences() {
        var deleted = new java.util.ArrayList<String>();
        var failures = new java.util.concurrent.atomic.AtomicInteger(1);
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) { throw new AssertionError(); }
            public void delete(String name) {
                if (failures.getAndDecrement() > 0) throw new IllegalStateException("Storage offline");
                deleted.add(name);
            }
        };
        var record = record("text", ""); record.getSpec().setMomentName("existing");
        record.getSpec().getCleanup().addAll(List.of("referenced", "orphan"));
        documents.put("existing", new Unstructured(Map.of("apiVersion", "moment.halo.run/v1alpha1", "kind", "Moment",
            "metadata", Map.of("name", "existing", "annotations", Map.of("attachmentNames", "[\"referenced\"]")))));
        var publisher = new SyncPublisher(gateway, upload, repo);
        publisher.cleanup(record);
        assertThat(record.getSpec().getCleanup()).contains("orphan");
        publisher.cleanup(record);
        assertThat(deleted).containsExactly("orphan");
    }
    @Test void failedNewRevisionCannotDeletePreviouslyCommittedStagedFile() {
        var deleted = new java.util.ArrayList<String>();
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) { throw new IllegalStateException("Upload failed"); }
            public void delete(String name) { deleted.add(name); }
        };
        var record = record("new", "new-file");
        var oldMedia = Jsons.write(Map.of("PHOTO:old", new MediaUploader.UploadedMedia("old-attachment", "/old.png", "PHOTO", "image/png")));
        record.getSpec().setStagedJson(oldMedia);
        documents.put("telegram-tm-test", new Unstructured(Map.of("apiVersion", "moment.halo.run/v1alpha1", "kind", "Moment",
            "metadata", Map.of("name", "telegram-tm-test", "annotations", Map.of(SyncPublisher.PREFIX + "revision", "1",
                SyncPublisher.PREFIX + "media", oldMedia, "attachmentNames", "[\"old-attachment\"]")))));
        assertThatThrownBy(() -> new SyncPublisher(gateway, upload, repo).publish(record, new TelegramSetting(), null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(deleted).doesNotContain("old-attachment");
    }
    @Test void identicalAlbumFilesKeepBothPositions() {
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) {
                return new UploadedMedia("attachment", "/image.png", "PHOTO", "image/png");
            }
            public void delete(String name) { }
        };
        var record = record("first", "file");
        var first = record.members().getFirst();
        record.members(List.of(first, new IncomingMessage(-100, 11, first.date(), "channel", "", "", null,
            "file", "file", "PHOTO", "", 2)));
        new SyncPublisher(gateway, upload, repo).publish(record, new TelegramSetting(), null);
        var medium = (List<?>) Unstructured.getNestedValue(documents.values().iterator().next().getData(),
            "spec", "content", "medium").orElseThrow();
        assertThat(medium).hasSize(2);
    }

    @Test void replacingLegacyMediaSchedulesCleanup() {
        var deleted = new java.util.ArrayList<String>();
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) { throw new AssertionError(); }
            public void delete(String name) { deleted.add(name); }
        };
        var record = record("replace", "");
        documents.put("telegram-tm-test", new Unstructured(Map.of("apiVersion", "moment.halo.run/v1alpha1", "kind", "Moment",
            "metadata", Map.of("name", "telegram-tm-test", "annotations", Map.of("attachmentNames", "[\"legacy-file\"]")))));
        new SyncPublisher(gateway, upload, repo).publish(record, new TelegramSetting(), null);
        assertThat(deleted).containsExactly("legacy-file");
    }
    private final MemoryRepository repo = new MemoryRepository();
    private final Map<String, Unstructured> documents = new HashMap<>();
    private final MomentGateway gateway = new MomentGateway() {
        public Unstructured get(String name) { return documents.get(name); }
        public Unstructured findLegacy(long chat, long message) { return null; }
        public Unstructured save(Unstructured value) { documents.put(value.getMetadata().getName(), value); return value; }
        public void delete(Unstructured value) { documents.remove(value.getMetadata().getName()); }
    };
    private final MediaUploader failingUpload = new MediaUploader() {
        public UploadedMedia upload(IncomingMessage m, String name, TelegramSetting s, TelegramApi a) { throw new IllegalStateException("Upload failed"); }
        public void delete(String name) { }
    };
    private SyncRecord record(String text, String file) {
        var record = new SyncRecord(); var meta = new Metadata(); meta.setName("tm-test"); record.setMetadata(meta);
        record.getSpec().setBotId(1); record.getSpec().setChatId(-100); record.getSpec().setRevision(2);
        record.members(List.of(new IncomingMessage(-100, 10, 1770000000, "channel", "", text, null, file, file, "PHOTO", "", 1)));
        repo.save(record); return record;
    }

    @Test void editingPreservesIdentityAndUnrelatedFields() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var settings = new TelegramSetting(); settings.setMomentOwner("owner");
        var record = record("first", ""); publisher.publish(record, settings, null);
        assertThat(documents).hasSize(1);
        String name = documents.keySet().iterator().next();
        var existing = documents.get(name);
        var data = new HashMap<String,Object>(existing.getData()); data.put("status", Map.of("permalink", "/moments/kept"));
        documents.put(name, new Unstructured(data));
        record = repo.get("tm-test"); record.getSpec().setRevision(3); record.members(record("edited", "").members());
        publisher.publish(record, settings, null);
        assertThat(documents).hasSize(1);
        assertThat(documents.get(name).getData().get("status")).isEqualTo(Map.of("permalink", "/moments/kept"));
        assertThat(Unstructured.getNestedValue(documents.get(name).getData(), "spec", "content", "raw")).contains("edited");
    }

    @Test void failedNewAttachmentKeepsOldContent() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo); var settings = new TelegramSetting();
        var first = record("old", ""); publisher.publish(first, settings, null);
        var edited = repo.get("tm-test"); edited.getSpec().setRevision(3); edited.members(record("new", "broken").members());
        assertThatThrownBy(() -> publisher.publish(edited, settings, null)).isInstanceOf(IllegalStateException.class);
        assertThat(documents).hasSize(1);
        assertThat(Unstructured.getNestedValue(documents.values().iterator().next().getData(), "spec", "content", "raw")).contains("old");
    }

    @Test void replayAfterCommitDoesNotDuplicateMoment() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var record = record("first", ""); publisher.publish(record, new TelegramSetting(), null);
        publisher.publish(record, new TelegramSetting(), null);
        assertThat(documents).hasSize(1);
        assertThat(repo.get("tm-test").getSpec().getState()).isEqualTo("SUCCEEDED");
    }

    @Test void blockedAlbumDoesNotPublishAnyMember() {
        var settings = new TelegramSetting(); settings.setChannelFilter("ad");
        var record = record("#ad", ""); new SyncPublisher(gateway, failingUpload, repo).publish(record, settings, null);
        assertThat(documents).isEmpty();
        assertThat(repo.get("tm-test").getSpec().getState()).isEqualTo("IGNORED");
    }
}
