package vip.mystery0.halo.telegrammoment.publisher;

import java.nio.file.Files;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class HaloMediaUploaderTest {
    @Test void supportsImmutableAnnotationsFromHaloStorage() throws Exception {
        var service = mock(AttachmentService.class);
        var client = mock(ReactiveExtensionClient.class);
        var api = mock(TelegramApi.class);
        var path = Files.createTempFile("upload-test-", ".png");
        when(api.download("file")).thenReturn(new TelegramApi.Download(path, "test.png"));
        var attachment = new Attachment(); var meta = new Metadata(); meta.setName("attachment");
        meta.setAnnotations(Map.of("existing", "keep")); attachment.setMetadata(meta);
        when(service.upload(anyString(), anyString(), isNull(), any(org.springframework.http.codec.multipart.FilePart.class), any())).thenAnswer(invocation -> {
            Consumer<Attachment> callback = invocation.getArgument(4); callback.accept(attachment);
            return Mono.just(attachment);
        });
        when(service.getPermalink(attachment)).thenReturn(Mono.just(java.net.URI.create("/upload/test.png")));
        var settings = new TelegramSetting(); settings.setMomentOwner("owner"); settings.setStoragePolicy("local");
        var message = new IncomingMessage(-1, 1, 1, "channel", "", "", null, "file", "unique", "PHOTO", "", 1);
        var logs = new vip.mystery0.halo.telegrammoment.LogBuffer(); logs.setEnabled(true);
        var uploaded = new HaloMediaUploader(service, client, logs).upload(message, "record", settings, api);
        assertThat(logs.getLogs().toString()).contains("Downloading media", "uploading attachment", "Media ready", "task=record");
        assertThat(uploaded.attachmentName()).isEqualTo("attachment");
        assertThat(attachment.getMetadata().getAnnotations()).containsEntry("existing", "keep")
            .containsEntry(SyncPublisher.PREFIX + "record", "record");
        assertThat(path).doesNotExist();
    }
}
