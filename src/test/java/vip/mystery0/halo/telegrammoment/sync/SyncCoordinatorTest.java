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

    private JsonNode privateUpdate(long updateId, long messageId, String text) {
        var update = Jsons.MAPPER.createObjectNode(); update.put("update_id", updateId);
        var message = update.putObject("message"); message.put("message_id", messageId).put("date", 100).put("text", text);
        message.putObject("chat").put("id", 10).put("type", "private");
        message.putObject("from").put("id", 10).put("is_bot", false);
        return update;
    }
    private TelegramSetting privateSettings() {
        var settings = new TelegramSetting(); settings.setPrivateEnabled(true); settings.setPrivateSenderId("10"); return settings;
    }
    private JsonNode reply(JsonNode command, String kind, JsonNode target) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) command.path(kind)).set("reply_to_message", target); return command;
    }

    @Test void authorisedPrivateRedoUsesCurrentReplyAndKeepsOneRecord() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        coordinator.accept(1, privateUpdate(1, 10, "old"), settings);
        String name = repo.records(1).getFirst().getMetadata().getName();
        var redo = reply(privateUpdate(2, 20, "/redo@MyBot"), "message", privateUpdate(1, 10, "current").path("message"));
        coordinator.accept(1, "mybot", redo, settings);
        coordinator.accept(1, "mybot", redo, settings);
        new SyncCoordinator(repo).accept(1, "MYBOT", reply(privateUpdate(3, 30, "/redo@mybot"),
            "message", privateUpdate(1, 10, "current").path("message")), settings);
        assertThat(repo.records(1)).hasSize(1);
        assertThat(repo.records(1).getFirst().getMetadata().getName()).isEqualTo(name);
        assertThat(repo.records(1).getFirst().members()).extracting(m -> m.text()).containsExactly("current");
        assertThat(new SyncCoordinator(repo).commandRequests(1)).hasSize(2);
        assertThat(coordinator.commandRequests(2)).isEmpty();
    }

    @Test void privateRemoveTargetsTheExistingMessageInsteadOfPublishingCommand() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        coordinator.accept(1, privateUpdate(1, 10, "original"), settings);
        var record = repo.records(1).getFirst(); record.getSpec().setState("SUCCEEDED"); repo.save(record);
        coordinator.accept(1, reply(privateUpdate(2, 20, "/rm"), "message", privateUpdate(1, 10, "original").path("message")), settings);
        assertThat(repo.records(1)).hasSize(1);
        record = repo.records(1).getFirst();
        assertThat(record.getSpec().getAction()).isEqualTo("DELETE");
        assertThat(record.getSpec().isMomentKnown()).isTrue();
        assertThat(record.members()).extracting(m -> m.messageId()).containsExactly(10L);
    }

    @Test void albumCommandsKeepAllMembersAndRedoRefreshesOnlyTheRepliedMember() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = new TelegramSetting();
        coordinator.accept(1, update(1, 10, "first", true), settings);
        coordinator.accept(1, update(2, 20, "second", true), settings);
        coordinator.accept(1, reply(update(3, 30, "/redo", false), "channel_post", update(2, 20, "edited", true).path("channel_post")), settings);
        assertThat(repo.records(1)).hasSize(1);
        assertThat(repo.records(1).getFirst().members()).extracting(m -> m.text()).containsExactly("first", "edited");
        coordinator.accept(1, reply(update(4, 40, "/rm", false), "channel_post", update(2, 20, "edited", true).path("channel_post")), settings);
        assertThat(repo.records(1).getFirst().getSpec().getAction()).isEqualTo("DELETE");
        assertThat(coordinator.commandRequests(1).getLast().sourceMessageIds()).containsExactly(10L, 20L);
    }

    @Test void missingTargetsHelpUnknownCommandsAndOtherBotsNeverBecomeMoments() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        coordinator.accept(1, "mybot", privateUpdate(1, 11, "/redo"), settings);
        coordinator.accept(1, "mybot", privateUpdate(2, 12, "/rm unexpected"), settings);
        coordinator.accept(1, "mybot", privateUpdate(3, 13, "/help"), settings);
        coordinator.accept(1, "mybot", privateUpdate(4, 14, "/unknown"), settings);
        coordinator.accept(1, "mybot", privateUpdate(5, 15, "/rm@someoneelse"), settings);
        assertThat(repo.records(1)).isEmpty();
        assertThat(coordinator.commandRequests(1)).hasSize(4).allSatisfy(request -> assertThat(request.result()).isNotBlank());
        assertThat(repo.cursor(1).getSpec().getNextOffset()).isEqualTo(6);
    }

    @Test void crossChatBotAuthoredAndUnauthorisedReplyTargetsCannotBeModified() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        coordinator.accept(1, privateUpdate(1, 10, "original"), settings);
        var otherChat = privateUpdate(1, 10, "original").path("message");
        ((com.fasterxml.jackson.databind.node.ObjectNode) otherChat.path("chat")).put("id", 11);
        coordinator.accept(1, reply(privateUpdate(2, 20, "/rm"), "message", otherChat), settings);
        var botTarget = privateUpdate(1, 10, "original").path("message");
        ((com.fasterxml.jackson.databind.node.ObjectNode) botTarget.path("from")).put("is_bot", true);
        coordinator.accept(1, reply(privateUpdate(3, 21, "/rm"), "message", botTarget), settings);
        var otherSender = privateUpdate(1, 10, "original").path("message");
        ((com.fasterxml.jackson.databind.node.ObjectNode) otherSender.path("from")).put("id", 11);
        coordinator.accept(1, reply(privateUpdate(4, 22, "/redo"), "message", otherSender), settings);
        assertThat(repo.records(1)).hasSize(1);
        assertThat(repo.records(1).getFirst().getSpec().getAction()).isEqualTo("PUBLISH");
        assertThat(repo.records(1).getFirst().getSpec().getLastUpdate()).isEqualTo(1);
        assertThat(coordinator.commandRequests(1)).hasSize(3).allSatisfy(request -> assertThat(request.result()).contains("已授权"));
    }

    @Test void untrustedCommandSenderAndBotRepliesDoNotEnterCommandOutbox() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        var outsider = privateUpdate(1, 10, "/help");
        ((com.fasterxml.jackson.databind.node.ObjectNode) outsider.path("message").path("from")).put("id", 11);
        coordinator.accept(1, outsider, settings);
        var botMessage = privateUpdate(2, 11, "/help");
        ((com.fasterxml.jackson.databind.node.ObjectNode) botMessage.path("message").path("from")).put("is_bot", true);
        coordinator.accept(1, botMessage, settings);
        assertThat(repo.records(1)).isEmpty(); assertThat(coordinator.commandRequests(1)).isEmpty();
    }

    @Test void commandAndCleanupOutboxesSurviveRestartWithoutAcknowledgingFailedPersistence() {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = privateSettings();
        coordinator.accept(1, privateUpdate(1, 10, "original"), settings);
        repo.failSave = true;
        var command = reply(privateUpdate(2, 20, "/rm"), "message", privateUpdate(1, 10, "original").path("message"));
        assertThatThrownBy(() -> coordinator.accept(1, command, settings)).isInstanceOf(IllegalStateException.class);
        assertThat(repo.cursor(1).getSpec().getNextOffset()).isEqualTo(2);
        repo.failSave = false; coordinator.accept(1, command, settings);
        var restarted = new SyncCoordinator(repo);
        var request = restarted.commandRequests(1).getFirst();
        restarted.resolveCommand(1, request, "deleted", true);
        assertThat(new SyncCoordinator(repo).commandRequests(1).getFirst().result()).isEqualTo("deleted");
        assertThat(new SyncCoordinator(repo).cleanupRequests(1)).extracting(r -> r.messageId()).containsExactly(20L);
        assertThat(new SyncCoordinator(repo).cleanupRequests(2)).isEmpty();
    }

    @Test void sourceCleanupIsOptInAndIncludesAllKnownAlbumMembers() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = new TelegramSetting();
        settings.setDeleteSourceMessages(true); settings.setCommandCleanupEnabled(false);
        coordinator.accept(1, update(1, 10, "first", true), settings);
        coordinator.accept(1, update(2, 20, "second", true), settings);
        coordinator.accept(1, reply(update(3, 30, "/rm", false), "channel_post", update(2, 20, "second", true).path("channel_post")), settings);
        var request = coordinator.commandRequests(1).getFirst(); coordinator.resolveCommand(1, request, "deleted", true);
        assertThat(coordinator.cleanupRequests(1)).extracting(r -> r.messageId()).containsExactlyInAnyOrder(10L, 20L);
        coordinator.completeCommand(1, request, 99);
        coordinator.accept(1, update(4, 99, "deleted", false), settings);
        assertThat(repo.records(1)).hasSize(1);
    }
}
