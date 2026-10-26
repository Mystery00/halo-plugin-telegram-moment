package vip.mystery0.halo.telegrammoment.config;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramSettingsStoreTest {
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);

    private ConfigMap config(String bot) {
        var config = new ConfigMap();
        var metadata = new Metadata(); metadata.setName("telegram-moment-configmap"); metadata.setVersion(7L);
        metadata.setCreationTimestamp(java.time.Instant.parse("2026-10-26T00:00:00Z"));
        config.setMetadata(metadata); config.setData(Map.of("bot", bot, "channel", "{\"channelId\":\"-42\"}"));
        when(client.fetch(ConfigMap.class, "telegram-moment-configmap")).thenReturn(Mono.just(config));
        return config;
    }

    private Secret secret(String value) {
        var secret = new Secret(); secret.setData(Map.of("token", value.getBytes(StandardCharsets.UTF_8)));
        return secret;
    }

    @Test void readsReferenceAndNeverSerializesToken() throws Exception {
        config("{\"botTokenSecretName\":\"selected\",\"botToken\":\"old:ignored\"}");
        when(client.fetch(Secret.class, "selected")).thenReturn(Mono.just(secret("123:secret")));
        var settings = new TelegramSettingsStore(client).load(false);
        assertThat(settings.getBotToken()).isEqualTo("123:secret");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings)).doesNotContain("123:secret", "old:ignored");
        assertThat(settings.toString()).doesNotContain("123:secret");
        verify(client, never()).update(any()); verify(client, never()).create(any());
        var fingerprint = settings.fingerprint(); settings.setBotToken("456:rotated");
        assertThat(settings.fingerprint()).isNotEqualTo(fingerprint);
    }

    @Test void invalidReferenceNeverFallsBackToLegacy() {
        config("{\"botTokenSecretName\":\"missing\",\"botToken\":\"123:legacy\"}");
        when(client.fetch(Secret.class, "missing")).thenReturn(Mono.empty());
        assertThatThrownBy(() -> new TelegramSettingsStore(client).load(true)).hasMessageContaining("Secret").hasMessageNotContaining("legacy");
        verify(client, never()).create(any()); verify(client, never()).update(any());
    }

    @Test void readOnlyLoadDoesNotMigrateLegacy() {
        config("{\"botToken\":\"123:legacy\"}");
        assertThat(new TelegramSettingsStore(client).load(false).hasValidToken()).isFalse();
        verify(client, never()).create(any()); verify(client, never()).update(any());
    }

    @Test void migrationVerifiesSecretBeforeRemovingOnlyLegacyField() {
        var original = config("{\"botToken\":\"123:legacy\",\"debugMode\":true}");
        when(client.fetch(Secret.class, "telegram-moment-bot-token")).thenReturn(Mono.empty(), Mono.just(secret("123:legacy")));
        when(client.create(any(Secret.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(client.update(any(ConfigMap.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        var result = new TelegramSettingsStore(client).load(true);
        assertThat(result.getBotToken()).isEqualTo("123:legacy");
        var captor = org.mockito.ArgumentCaptor.forClass(ConfigMap.class); verify(client).update(captor.capture());
        var updated = captor.getValue();
        assertThat(updated.getData().get("bot")).contains("botTokenSecretName", "debugMode").doesNotContain("123:legacy");
        assertThat(updated.getData().get("channel")).isEqualTo(original.getData().get("channel"));
        assertThat(updated.getMetadata().getVersion()).isEqualTo(7L);
        assertThat(original.getData().get("bot")).contains("123:legacy");
    }

    @Test void failedSecretVerificationKeepsLegacyUntouched() {
        var original = config("{\"botToken\":\"123:legacy\"}");
        when(client.fetch(Secret.class, "telegram-moment-bot-token")).thenReturn(Mono.empty());
        when(client.create(any(Secret.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        assertThatThrownBy(() -> new TelegramSettingsStore(client).load(true)).hasMessageNotContaining("123:legacy");
        verify(client, never()).update(any());
        assertThat(original.getData().get("bot")).contains("123:legacy");
    }

    @Test void existingUnownedSecretIsNeverOverwritten() {
        config("{\"botToken\":\"123:legacy\"}");
        when(client.fetch(Secret.class, "telegram-moment-bot-token")).thenReturn(Mono.just(secret("456:someone-else")));
        assertThatThrownBy(() -> new TelegramSettingsStore(client).load(true)).hasMessageNotContaining("legacy").hasMessageNotContaining("someone-else");
        verify(client, never()).update(any()); verify(client, never()).create(any());
    }

    @Test void conflictPreservesOriginalAndNextStartCanResume() {
        var original = config("{\"botToken\":\"123:legacy\"}");
        var owned = secret("123:legacy");
        var metadata = new Metadata(); metadata.setName("telegram-moment-bot-token");
        metadata.setLabels(Map.of("telegram-moment.halo.run/managed", "true")); owned.setMetadata(metadata);
        when(client.fetch(Secret.class, "telegram-moment-bot-token")).thenReturn(Mono.just(owned));
        when(client.update(any(ConfigMap.class))).thenReturn(Mono.error(new IllegalStateException("123:legacy")));
        assertThatThrownBy(() -> new TelegramSettingsStore(client).load(true)).hasMessageNotContaining("123:legacy");
        assertThat(original.getData().get("bot")).contains("123:legacy");
        when(client.update(any(ConfigMap.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        assertThat(new TelegramSettingsStore(client).load(true).getBotToken()).isEqualTo("123:legacy");
        verify(client, never()).create(any());
    }

    @Test void creationFailureDoesNotRemoveTheOnlyCredential() {
        var original = config("{\"botToken\":\"123:legacy\"}");
        when(client.fetch(Secret.class, "telegram-moment-bot-token")).thenReturn(Mono.empty());
        when(client.create(any(Secret.class))).thenReturn(Mono.error(new IllegalStateException("123:legacy")));
        assertThatThrownBy(() -> new TelegramSettingsStore(client).load(true)).hasMessageContaining("create migration Secret").hasMessageNotContaining("123:legacy");
        verify(client, never()).update(any());
        assertThat(original.getData().get("bot")).contains("123:legacy");
    }
}
