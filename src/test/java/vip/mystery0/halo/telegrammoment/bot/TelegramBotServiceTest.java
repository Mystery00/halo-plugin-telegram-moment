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
}
