package vip.mystery0.halo.telegrammoment.bot;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.SchemeManager;
import vip.mystery0.halo.telegrammoment.LogBuffer;
import vip.mystery0.halo.telegrammoment.publisher.SyncPublisher;
import vip.mystery0.halo.telegrammoment.sync.MemoryRepository;
import vip.mystery0.halo.telegrammoment.sync.SyncCoordinator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramBotServiceTest {
    @Test void pollingReportsRetryRecoveryAndDoesNotSpamEmptyResponses() throws Exception {
        var repo = new MemoryRepository(); var logs = new LogBuffer(); logs.setEnabled(true);
        var bot = new TelegramBotService(mock(ReactiveExtensionClient.class), mock(SchemeManager.class), repo,
            new SyncCoordinator(repo), mock(SyncPublisher.class), logs);
        var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        var empty = vip.mystery0.halo.telegrammoment.sync.Jsons.MAPPER.createArrayNode();
        when(transport.updates(anyLong()))
            .thenThrow(new vip.mystery0.halo.telegrammoment.telegram.TelegramApiException(429, 1))
            .thenReturn(empty, empty, empty)
            .thenThrow(new vip.mystery0.halo.telegrammoment.telegram.TelegramApiException(401, 0));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "poll", 0L, 1L, transport,
            new vip.mystery0.halo.telegrammoment.config.TelegramSetting());
        assertThat(logs.getLogs().toString()).contains("retryInSeconds=1", "Polling recovered after failures=1");
        assertThat(logs.getLogs().stream().filter(e -> e.message().contains("connection healthy")).count()).isEqualTo(1);
    }
    @Test void pollingExplainsReceptionFilteringAndConnectionFailureWithoutContent() throws Exception {
        var repo = new MemoryRepository(); var logs = new LogBuffer(); logs.setEnabled(true);
        var bot = new TelegramBotService(mock(ReactiveExtensionClient.class), mock(SchemeManager.class), repo,
            new SyncCoordinator(repo), mock(SyncPublisher.class), logs);
        var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        var updates = vip.mystery0.halo.telegrammoment.sync.Jsons.MAPPER.readTree("""
            [{"update_id":1,"channel_post":{"message_id":10,"chat":{"id":-100,"type":"channel"},"text":"DO_NOT_LOG_CONTENT"}},
             {"update_id":2,"channel_post":{"message_id":11,"chat":{"id":-200,"type":"channel"},"text":"DO_NOT_LOG_CONTENT"}}]
            """);
        when(transport.updates(anyLong())).thenReturn(updates)
            .thenThrow(new vip.mystery0.halo.telegrammoment.telegram.TelegramApiException(401, 0));
        var settings = new vip.mystery0.halo.telegrammoment.config.TelegramSetting(); settings.setChannelId("-100");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "poll", 0L, 1L, transport, settings);
        String output = logs.getLogs().toString();
        assertThat(output).contains("updates=2", "update=1", "message=10", "Queued", "channel ID does not match", "401", "token")
            .doesNotContain("DO_NOT_LOG_CONTENT");
    }

    @Test void publishingLogsStartAndOutcomeWithTaskIdentity() {
        var repo = new MemoryRepository(); var logs = new LogBuffer(); logs.setEnabled(true);
        var record = new vip.mystery0.halo.telegrammoment.sync.SyncRecord();
        var metadata = new run.halo.app.extension.Metadata(); metadata.setName("tm-log-test"); record.setMetadata(metadata);
        record.getSpec().setBotId(1); record.getSpec().setChatId(-100); repo.save(record);
        var publisher = mock(SyncPublisher.class);
        doAnswer(call -> { ((vip.mystery0.halo.telegrammoment.sync.SyncRecord) call.getArgument(0)).getSpec().setState("SUCCEEDED"); return null; })
            .when(publisher).publish(any(), any(), any());
        var bot = new TelegramBotService(mock(ReactiveExtensionClient.class), mock(SchemeManager.class), repo,
            new SyncCoordinator(repo), publisher, logs);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L,
            mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class), new vip.mystery0.halo.telegrammoment.config.TelegramSetting());
        assertThat(logs.getLogs().toString()).contains("Processing started", "task=tm-log-test", "action=PUBLISH", "state=SUCCEEDED", "durationMs=");
    }
    @Test void missingTokenIsVisibleAndDoesNotPretendToRun() {
        var client = mock(ReactiveExtensionClient.class);
        when(client.fetch(ConfigMap.class, "telegram-moment-configmap")).thenReturn(Mono.empty());
        var repo = new MemoryRepository();
        var bot = new TelegramBotService(client, mock(SchemeManager.class), repo, new SyncCoordinator(repo),
            mock(SyncPublisher.class), new LogBuffer());
        bot.startBot();
        assertThat(bot.status().phase()).isEqualTo("UNCONFIGURED");
        assertThat(bot.status().running()).isFalse();
        bot.stopBot();
        assertThat(bot.status().phase()).isEqualTo("STOPPED");
    }

    private com.fasterxml.jackson.databind.JsonNode privateCommand(String text, boolean includeTarget) throws Exception {
        var update = vip.mystery0.halo.telegrammoment.sync.Jsons.MAPPER.readTree("""
            {"update_id":2,"message":{"message_id":20,"date":100,"chat":{"id":10,"type":"private"},"from":{"id":10}}}
            """);
        var message = (com.fasterxml.jackson.databind.node.ObjectNode) update.path("message"); message.put("text", text);
        if (includeTarget) message.set("reply_to_message", vip.mystery0.halo.telegrammoment.sync.Jsons.MAPPER.readTree("""
            {"message_id":10,"date":100,"chat":{"id":10,"type":"private"},"from":{"id":10},"text":"original"}
            """));
        return update;
    }
    private vip.mystery0.halo.telegrammoment.config.TelegramSetting commandSettings() {
        var settings = new vip.mystery0.halo.telegrammoment.config.TelegramSetting();
        settings.setPrivateEnabled(true); settings.setPrivateSenderId("10"); return settings;
    }
    private TelegramBotService service(MemoryRepository repo, SyncCoordinator coordinator, SyncPublisher publisher, LogBuffer logs) {
        return new TelegramBotService(mock(ReactiveExtensionClient.class), mock(SchemeManager.class), repo, coordinator, publisher, logs);
    }

    @Test void explicitCommandsReceiveUsefulFeedbackEvenWhenAutomaticRepliesAreOff() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = commandSettings();
        coordinator.accept(1, privateCommand("/redo", false), settings);
        var publisher = mock(SyncPublisher.class); var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true))).thenReturn(99L);
        var bot = service(repo, coordinator, publisher, new LogBuffer());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L, transport, settings);
        verify(transport).reply(eq(10L), eq(20L), contains("回复"), eq(true));
        verifyNoInteractions(publisher);
        assertThat(coordinator.commandRequests(1)).isEmpty();
        assertThat(repo.cursor(1).getSpec().getRepliesJson()).contains("10:99");
        assertThat(repo.records(1)).isEmpty();
    }

    @Test void unknownDeleteReportsMissingMomentAndDoesNotDeleteAnyTelegramMessages() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = commandSettings();
        settings.setDeleteSourceMessages(true); coordinator.accept(1, privateCommand("/rm", true), settings);
        var publisher = mock(SyncPublisher.class); var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        doThrow(new IllegalArgumentException("No synchronized moment was found for the replied message"))
            .when(publisher).publish(any(), any(), any());
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true))).thenReturn(99L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service(repo, coordinator, publisher, new LogBuffer()),
            "work", 0L, 1L, transport, settings);
        assertThat(repo.records(1).getFirst().getSpec().getState()).isEqualTo("FAILED");
        verify(transport).reply(eq(10L), eq(20L), contains("未找到对应瞬间"), eq(true));
        verify(transport, never()).deleteMessage(anyLong(), anyLong());
        assertThat(coordinator.cleanupRequests(1)).isEmpty();
    }

    @Test void telegramCleanupRetriesAfterRestartWithoutRepeatingSuccessfulHaloDeletion() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = commandSettings();
        coordinator.accept(1, privateCommand("/rm", true), settings);
        var publisher = mock(SyncPublisher.class); var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        doAnswer(call -> {
            var record = (vip.mystery0.halo.telegrammoment.sync.SyncRecord) call.getArgument(0);
            record.getSpec().setState("DELETED"); repo.save(record); return null;
        }).when(publisher).publish(any(), any(), any());
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true))).thenReturn(99L);
        doThrow(new vip.mystery0.halo.telegrammoment.telegram.TelegramApiException(429, 1)).doNothing()
            .when(transport).deleteMessage(10, 20);
        var logs = new LogBuffer(); logs.setEnabled(true);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service(repo, coordinator, publisher, logs),
            "work", 0L, 1L, transport, settings);
        assertThat(repo.records(1).getFirst().getSpec().getState()).isEqualTo("DELETED");
        assertThat(repo.cursor(1).getSpec().getTelegramCleanupJson()).contains("10:20");
        var cursor = repo.cursor(1);
        cursor.getSpec().setTelegramCleanupJson(vip.mystery0.halo.telegrammoment.sync.Jsons.write(java.util.Map.of("10:20",
            new SyncCoordinator.CleanupRequest(10, 20, "command", 1, 0)))); repo.saveCursor(cursor);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service(repo, new SyncCoordinator(repo), publisher, logs),
            "work", 0L, 1L, transport, settings);
        verify(publisher, times(1)).publish(any(), any(), any());
        verify(transport, times(2)).deleteMessage(10, 20);
        verify(transport, never()).deleteMessage(10, 10);
        verify(transport, times(1)).reply(eq(10L), eq(20L), anyString(), eq(true));
        assertThat(repo.cursor(1).getSpec().getTelegramCleanupJson()).isEqualTo("{}");
        assertThat(logs.getLogs().toString()).contains("Halo result is unchanged");
    }

    @Test void failedCommandFeedbackDoesNotRollBackOrRepublishHaloSuccess() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = commandSettings();
        coordinator.accept(1, privateCommand("/redo", true), settings);
        var publisher = mock(SyncPublisher.class); var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        doAnswer(call -> {
            var record = (vip.mystery0.halo.telegrammoment.sync.SyncRecord) call.getArgument(0);
            record.getSpec().setState("SUCCEEDED"); repo.save(record); return null;
        }).when(publisher).publish(any(), any(), any());
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true)))
            .thenThrow(new vip.mystery0.halo.telegrammoment.telegram.TelegramApiException(403, 0));
        var bot = service(repo, coordinator, publisher, new LogBuffer());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L, transport, settings);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L, transport, settings);
        assertThat(repo.records(1).getFirst().getSpec().getState()).isEqualTo("SUCCEEDED");
        verify(publisher, times(1)).publish(any(), any(), any());
        verify(transport, times(1)).deleteMessage(10, 20);
        verify(transport, never()).deleteMessage(10, 10);
        assertThat(coordinator.commandRequests(1)).isEmpty();
    }

    private vip.mystery0.halo.telegrammoment.sync.SyncRecord previouslyDeletedRecord(MemoryRepository repo, boolean known) {
        var record = new vip.mystery0.halo.telegrammoment.sync.SyncRecord();
        var metadata = new run.halo.app.extension.Metadata(); metadata.setName("tm-previous-delete"); record.setMetadata(metadata);
        record.getSpec().setBotId(1); record.getSpec().setChatId(10); record.getSpec().setLastUpdate(1);
        record.getSpec().setRevision(2); record.getSpec().setAction("DELETE"); record.getSpec().setState("DELETED");
        record.getSpec().setMomentName("telegram-previous-delete"); record.getSpec().setMomentKnown(known);
        record.members(java.util.List.of(new vip.mystery0.halo.telegrammoment.telegram.IncomingMessage(
            10, 10, 100, "private", "", "original", null, "", "", "", "", 1)));
        repo.save(record); return record;
    }

    @Test void oldUnprovenDeletedStateCannotAuthoriseSourceDeletionAfterUpgrade() throws Exception {
        var repo = new MemoryRepository(); var coordinator = new SyncCoordinator(repo); var settings = commandSettings();
        settings.setDeleteSourceMessages(true);
        var old = previouslyDeletedRecord(repo, false);
        coordinator.accept(1, privateCommand("/rm", true), settings);
        assertThat(repo.get(old.getMetadata().getName()).getSpec().isMomentKnown()).isFalse();
        var moments = mock(vip.mystery0.halo.telegrammoment.publisher.MomentGateway.class);
        var media = mock(vip.mystery0.halo.telegrammoment.publisher.MediaUploader.class);
        var publisher = new SyncPublisher(moments, media, repo);
        var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true))).thenReturn(99L);
        var bot = service(repo, coordinator, publisher, new LogBuffer());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L, transport, settings);
        assertThat(repo.get(old.getMetadata().getName()).getSpec().getState()).isEqualTo("FAILED");
        assertThat(repo.get(old.getMetadata().getName()).getSpec().isMomentKnown()).isFalse();
        verify(transport).reply(eq(10L), eq(20L), contains("未找到对应瞬间"), eq(true));
        coordinator.retry(1, old.getMetadata().getName());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bot, "work", 0L, 1L, transport, settings);
        assertThat(repo.get(old.getMetadata().getName()).getSpec().getState()).isEqualTo("FAILED");
        assertThat(coordinator.cleanupRequests(1)).isEmpty();
        verify(transport, never()).deleteMessage(anyLong(), anyLong());
        verify(moments, never()).delete(any());
        verifyNoInteractions(media);
    }

    @Test void provenDeletedRecordStillSupportsIdempotentRemoveAfterRestart() throws Exception {
        var repo = new MemoryRepository(); var settings = commandSettings();
        var old = previouslyDeletedRecord(repo, true);
        var coordinator = new SyncCoordinator(repo);
        coordinator.accept(1, privateCommand("/rm", true), settings);
        assertThat(repo.get(old.getMetadata().getName()).getSpec().isMomentKnown()).isTrue();
        var moments = mock(vip.mystery0.halo.telegrammoment.publisher.MomentGateway.class);
        var media = mock(vip.mystery0.halo.telegrammoment.publisher.MediaUploader.class);
        when(media.owned(anyString())).thenReturn(java.util.List.of());
        var transport = mock(vip.mystery0.halo.telegrammoment.telegram.TelegramApi.class);
        when(transport.reply(eq(10L), eq(20L), anyString(), eq(true))).thenReturn(99L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service(repo, coordinator, new SyncPublisher(moments, media, repo), new LogBuffer()),
            "work", 0L, 1L, transport, settings);
        assertThat(repo.get(old.getMetadata().getName()).getSpec().getState()).isEqualTo("DELETED");
        assertThat(repo.get(old.getMetadata().getName()).getSpec().isMomentKnown()).isTrue();
        verify(transport).reply(eq(10L), eq(20L), contains("已删除"), eq(true));
        verify(transport).deleteMessage(10, 20);
        verify(transport, never()).deleteMessage(10, 10);
        verify(moments, never()).delete(any());
    }
}
