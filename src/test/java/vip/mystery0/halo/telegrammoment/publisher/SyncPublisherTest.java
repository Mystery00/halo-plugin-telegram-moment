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

    @Test void incompleteLegacyAlbumKeepsEveryAttachmentWithoutUploadingOrDeleting() {
        var legacy = legacyMoment(2);
        documents.put("legacy-moment", legacy);
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) { throw new AssertionError("Must not upload"); }
            public List<String> owned(String record) { throw new AssertionError("Must reject before attachment preparation"); }
            public void delete(String name) { throw new AssertionError("Must not delete"); }
        };
        var record = record("edited first member", "new-file");

        assertThatThrownBy(() -> new SyncPublisher(legacyGateway(), upload, repo).publish(record, new TelegramSetting(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Legacy album mapping is incomplete");

        assertThat(documents).containsOnlyKeys("legacy-moment");
        assertThat(documents.get("legacy-moment")).isSameAs(legacy);
        assertThat(legacy.getMetadata().getAnnotations().get("attachmentNames")).isEqualTo("[\"legacy-0\",\"legacy-1\"]");
        assertThat(Unstructured.getNestedValue(legacy.getData(), "spec", "content", "medium").orElseThrow())
            .asList().hasSize(2);
        assertThat(record.getSpec().getMomentName()).isBlank();
    }

    @Test void legacySingleImageStillUpdatesInPlaceAndCleansReplacedAttachment() {
        documents.put("legacy-moment", legacyMoment(1));
        var deleted = new java.util.ArrayList<String>();
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) {
                return new UploadedMedia("replacement", "/replacement.png", "PHOTO", "image/png");
            }
            public void delete(String name) { deleted.add(name); }
        };

        new SyncPublisher(legacyGateway(), upload, repo).publish(record("edited", "new-file"), new TelegramSetting(), null);

        assertThat(documents).containsOnlyKeys("legacy-moment");
        var updated = documents.get("legacy-moment");
        assertThat(Unstructured.getNestedValue(updated.getData(), "spec", "content", "raw")).contains("edited");
        assertThat(updated.getMetadata().getAnnotations().get("attachmentNames")).isEqualTo("[\"replacement\"]");
        assertThat(deleted).containsExactly("legacy-0");
    }

    @Test void legacyAlbumAddedDuringUploadCannotBeOverwritten() {
        documents.put("legacy-moment", legacyMoment(1));
        var expandedAlbum = legacyMoment(2);
        var deleted = new java.util.ArrayList<String>();
        var upload = new MediaUploader() {
            public UploadedMedia upload(IncomingMessage m, String n, TelegramSetting s, TelegramApi a) {
                documents.put("legacy-moment", expandedAlbum);
                return new UploadedMedia("uncommitted-replacement", "/replacement.png", "PHOTO", "image/png");
            }
            public void delete(String name) { deleted.add(name); }
        };
        var record = record("edited first member", "new-file");

        assertThatThrownBy(() -> new SyncPublisher(legacyGateway(), upload, repo).publish(record, new TelegramSetting(), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Legacy album mapping is incomplete");

        assertThat(documents).containsOnlyKeys("legacy-moment");
        assertThat(documents.get("legacy-moment")).isSameAs(expandedAlbum);
        assertThat(expandedAlbum.getMetadata().getAnnotations().get("attachmentNames"))
            .isEqualTo("[\"legacy-0\",\"legacy-1\"]");
        assertThat(Unstructured.getNestedValue(expandedAlbum.getData(), "spec", "content", "medium").orElseThrow())
            .asList().hasSize(2);
        assertThat(deleted).containsExactly("uncommitted-replacement");
        assertThat(record.getSpec().getStagedJson()).isEqualTo("{}");
    }

    private Unstructured legacyMoment(int mediaCount) {
        var attachments = java.util.stream.IntStream.range(0, mediaCount).mapToObj(i -> "legacy-" + i).toList();
        var medium = attachments.stream().map(name -> Map.of("type", "PHOTO", "url", "/" + name + ".png", "originType", "image/png")).toList();
        return new Unstructured(Map.of("apiVersion", "moment.halo.run/v1alpha1", "kind", "Moment",
            "metadata", Map.of("name", "legacy-moment", "annotations", Map.of("chatId", "-100", "messageId", "10", "attachmentNames", Jsons.write(attachments))),
            "spec", Map.of("owner", "legacy-owner", "visible", "PRIVATE", "approved", false,
                "content", Map.of("raw", "legacy caption", "html", "<p>legacy caption</p>", "medium", medium))));
    }

    private MomentGateway legacyGateway() {
        return new MomentGateway() {
            public Unstructured get(String name) { return gateway.get(name); }
            public Unstructured findLegacy(long chat, long message) { return documents.get("legacy-moment"); }
            public Unstructured save(Unstructured value) { return gateway.save(value); }
            public void delete(Unstructured value) { gateway.delete(value); }
        };
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

    @Test void newMomentsUseConfiguredVisibilityAndApprovalDefaults() {
        var settings = new TelegramSetting(); settings.setMomentOwner("configured-owner");
        settings.setMomentVisibility("PRIVATE"); settings.setMomentAutoApprove(false);
        new SyncPublisher(gateway, failingUpload, repo).publish(record("pending review", ""), settings, null);

        var data = documents.get("telegram-tm-test").getData();
        assertThat(Unstructured.getNestedValue(data, "spec", "owner")).contains("configured-owner");
        assertThat(Unstructured.getNestedValue(data, "spec", "visible")).contains("PRIVATE");
        assertThat(Unstructured.getNestedValue(data, "spec", "approved")).contains(false);
        assertThat(Unstructured.getNestedValue(data, "spec", "approvedTime")).isEmpty();
        assertThat(repo.get("tm-test").getSpec().isMomentKnown()).isTrue();
    }

    @Test void editingCannotRepublishPrivateUnapprovedContentOrChangeOwner() {
        var settings = new TelegramSetting(); settings.setMomentOwner("original-owner");
        settings.setMomentVisibility("PRIVATE"); settings.setMomentAutoApprove(false);
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var record = record("first", ""); publisher.publish(record, settings, null);

        settings.setMomentOwner("new-default-owner"); settings.setMomentVisibility("PUBLIC"); settings.setMomentAutoApprove(true);
        record.getSpec().setRevision(3); record.members(record("edited", "").members());
        publisher.publish(record, settings, null);

        var data = documents.get("telegram-tm-test").getData();
        assertThat(Unstructured.getNestedValue(data, "spec", "owner")).contains("original-owner");
        assertThat(Unstructured.getNestedValue(data, "spec", "visible")).contains("PRIVATE");
        assertThat(Unstructured.getNestedValue(data, "spec", "approved")).contains(false);
        assertThat(Unstructured.getNestedValue(data, "spec", "approvedTime")).isEmpty();
        assertThat(Unstructured.getNestedValue(data, "spec", "content", "raw")).contains("edited");
    }

    @Test void editingKeepsExistingApprovalTimestampWhenDefaultsChange() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var settings = new TelegramSetting();
        var record = record("first", ""); publisher.publish(record, settings, null);
        var approvedTime = Unstructured.getNestedValue(documents.get("telegram-tm-test").getData(), "spec", "approvedTime").orElseThrow();

        settings.setMomentAutoApprove(false);
        record.getSpec().setRevision(3); record.members(record("edited", "").members());
        publisher.publish(record, settings, null);

        var data = documents.get("telegram-tm-test").getData();
        assertThat(Unstructured.getNestedValue(data, "spec", "approved")).contains(true);
        assertThat(Unstructured.getNestedValue(data, "spec", "approvedTime")).contains(approvedTime);
    }

    @Test void unknownDeleteRemainsAnExplicitFailureOnRetry() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var record = record("", ""); record.getSpec().setAction("DELETE");
        repo.save(record);
        for (int attempt = 0; attempt < 2; attempt++) {
            var retry = repo.get("tm-test");
            assertThatThrownBy(() -> publisher.publish(retry, new TelegramSetting(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No synchronized moment was found for the replied message");
            assertThat(repo.get("tm-test").getSpec().getMomentName()).isBlank();
            assertThat(repo.get("tm-test").getSpec().isMomentKnown()).isFalse();
        }
        assertThat(repo.get("tm-test").getSpec().getState()).isNotEqualTo("DELETED");
    }

    @Test void preallocatedNameFromFailedPublishDoesNotMakeUnknownDeleteSuccessful() {
        var record = record("", ""); record.getSpec().setAction("DELETE");
        record.getSpec().setMomentName("telegram-tm-test");
        assertThatThrownBy(() -> new SyncPublisher(gateway, failingUpload, repo).publish(record, new TelegramSetting(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No synchronized moment was found for the replied message");
    }

    @Test void deletingAKnownMomentIsIdempotentEvenAfterItDisappears() {
        var publisher = new SyncPublisher(gateway, failingUpload, repo);
        var settings = new TelegramSetting();
        var record = record("first", ""); publisher.publish(record, settings, null);
        record.getSpec().setAction("DELETE"); record.getSpec().setRevision(3); record.getSpec().setState("PENDING");
        publisher.publish(record, settings, null);
        assertThat(documents).isEmpty();
        assertThat(record.getSpec().getState()).isEqualTo("DELETED");

        record.getSpec().setState("PENDING"); publisher.publish(record, settings, null);
        assertThat(record.getSpec().getState()).isEqualTo("DELETED");
    }

    @Test void interruptedDeletionRemembersLegacyMomentBeforeItDisappears() {
        var record = record("", ""); record.getSpec().setAction("DELETE");
        documents.put("legacy-moment", new Unstructured(Map.of("apiVersion", "moment.halo.run/v1alpha1", "kind", "Moment",
            "metadata", Map.of("name", "legacy-moment", "annotations", Map.of("attachmentNames", "[]")))));
        var interruptingGateway = new MomentGateway() {
            public Unstructured get(String name) { return gateway.get(name); }
            public Unstructured findLegacy(long chat, long message) { return documents.get("legacy-moment"); }
            public Unstructured save(Unstructured value) { return gateway.save(value); }
            public void delete(Unstructured value) {
                gateway.delete(value);
                throw new IllegalStateException("Response lost after delete");
            }
        };
        assertThatThrownBy(() -> new SyncPublisher(interruptingGateway, failingUpload, repo).publish(record, new TelegramSetting(), null))
            .hasMessage("Response lost after delete");
        assertThat(documents).isEmpty();

        var retry = repo.get("tm-test");
        new SyncPublisher(gateway, failingUpload, repo).publish(retry, new TelegramSetting(), null);
        assertThat(retry.getSpec().getState()).isEqualTo("DELETED");
        assertThat(retry.getSpec().isMomentKnown()).isTrue();
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
