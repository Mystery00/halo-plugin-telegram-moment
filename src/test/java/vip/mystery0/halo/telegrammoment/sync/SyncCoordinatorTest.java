package vip.mystery0.halo.telegrammoment.sync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import static org.assertj.core.api.Assertions.*;

class SyncCoordinatorTest {
    @Test void unknownDeleteNeverPersistsPublishIntermediateState() throws Exception {
        var savedActions = new java.util.ArrayList<String>();
        var repo = new MemoryRepository() {
            @Override public SyncRecord save(SyncRecord record) {
                savedActions.add(record.getSpec().getAction());
                return super.save(record);
            }
        };
        new SyncCoordinator(repo).accept(1, Jsons.MAPPER.readTree("""
            {"update_id":2,"channel_post":{"message_id":20,"chat":{"id":-100,"type":"channel"},
            "text":"/rm","reply_to_message":{"message_id":10,"chat":{"id":-100,"type":"channel"},"text":"old"}}}
            """), new TelegramSetting());
        assertThat(savedActions).containsOnly("DELETE");
    }
    private JsonNode update(long id, int message, String text, boolean album) throws Exception {
        return Jsons.MAPPER.readTree("{\"update_id\":" + id + ",\"channel_post\":{\"message_id\":" + message
            + ",\"chat\":{\"id\":-100,\"type\":\"channel\"},\"text\":\"" + text + "\""
            + (album ? ",\"media_group_id\":\"album\"" : "") + "}}");
    }

    @Test void duplicateDeliveryAndRestartKeepOneRecord() throws Exception {
        var repo = new MemoryRepository(); var settings = new TelegramSetting();
        new SyncCoordinator(repo).accept(1, update(5, 10, "first", false), settings);
        new SyncCoordinator(repo).accept(1, update(5, 10, "first", false), settings);
        assertThat(repo.records(1)).hasSize(1);
        assertThat(repo.cursor(1).getSpec().getNextOffset()).isEqualTo(6);
    }

    @Test void failedPersistenceNeverAcknowledgesUpdate() throws Exception {
        var repo = new MemoryRepository(); repo.failSave = true;
        var node = update(5, 10, "first", false);
        assertThatThrownBy(() -> new SyncCoordinator(repo).accept(1, node, new TelegramSetting())).isInstanceOf(IllegalStateException.class);
        assertThat(repo.cursor(1).getSpec().getNextOffset()).isZero();
    }

    @Test void albumMembersAreSortedAndEditsReplaceOneMember() throws Exception {
        var repo = new MemoryRepository(); var settings = new TelegramSetting(); var coordinator = new SyncCoordinator(repo);
        coordinator.accept(1, update(5, 20, "later", true), settings);
        coordinator.accept(1, update(6, 10, "first", true), settings);
        coordinator.accept(1, update(7, 20, "edited", true), settings);
        var records = repo.records(1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().members()).extracting(m -> m.text()).containsExactly("first", "edited");
        coordinator.accept(2, update(5, 10, "other bot", true), settings);
        assertThat(repo.records(2)).hasSize(1);
    }

    @Test void unauthorisedDeleteDoesNotChangeExistingRecord() throws Exception {
        var repo = new MemoryRepository(); var settings = new TelegramSetting(); settings.setChannelId("-100");
        var coordinator = new SyncCoordinator(repo); coordinator.accept(1, update(1, 10, "first", false), settings);
        coordinator.accept(1, Jsons.MAPPER.readTree("""
            {"update_id":2,"channel_post":{"message_id":20,"chat":{"id":-200,"type":"channel"},
            "text":"/rm","reply_to_message":{"message_id":10,"chat":{"id":-100,"type":"channel"}}}}
            """), settings);
        assertThat(repo.records(1)).hasSize(1);
        assertThat(repo.records(1).getFirst().getSpec().getAction()).isEqualTo("PUBLISH");
    }
}
