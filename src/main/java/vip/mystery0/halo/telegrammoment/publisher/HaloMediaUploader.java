package vip.mystery0.halo.telegrammoment.publisher;

import java.time.Duration;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;
import vip.mystery0.halo.telegrammoment.telegram.TelegramApi;

@Component
@RequiredArgsConstructor
public class HaloMediaUploader implements MediaUploader {
    private final AttachmentService attachments;
    private final ReactiveExtensionClient client;
    private final vip.mystery0.halo.telegrammoment.LogBuffer logs;

    public UploadedMedia upload(IncomingMessage message, String recordName, TelegramSetting settings, TelegramApi api) {
        String created = null;
        String context = "task=" + recordName + " chat=" + message.chatId() + " message=" + message.messageId();
        String stage = "download";
        logs.add("INFO", "Media", "Downloading media: " + context + " timeoutSeconds=60 limitMiB=20");
        try (var download = api.download(message.fileId())) {
            stage = "upload";
            logs.add("INFO", "Media", "Download completed; uploading attachment: " + context + " timeoutSeconds=60");
            MediaType mime = MediaTypeFactory.getMediaType(download.filename()).orElse(MediaType.APPLICATION_OCTET_STREAM);
            var attachment = attachments.upload(settings.getMomentOwner(), settings.getStoragePolicy(),
                settings.getStorageGroup().isBlank() ? null : settings.getStorageGroup(),
                new LocalFilePart(download.path(), download.filename(), mime), value -> {
                    var existing = value.getMetadata().getAnnotations();
                    var annotations = existing == null ? new HashMap<String, String>() : new HashMap<>(existing);
                    annotations.put(SyncPublisher.PREFIX + "record", recordName);
                    value.getMetadata().setAnnotations(annotations);
                }).block(Duration.ofSeconds(60));
            if (attachment == null) throw new IllegalStateException("Storage policy returned no attachment");
            created = attachment.getMetadata().getName();
            stage = "permalink";
            logs.add("INFO", "Media", "Attachment uploaded: " + context + " attachment=" + created + "; resolving permalink");
            var permalink = attachments.getPermalink(attachment).block(Duration.ofSeconds(15));
            if (permalink == null) throw new IllegalStateException("Storage policy returned no permalink");
            logs.add("INFO", "Media", "Media ready: " + context + " attachment=" + created + " type=" + message.mediaType());
            return new UploadedMedia(created, permalink.toString(), message.mediaType(), mime.toString());
        } catch (RuntimeException e) {
            logs.add("WARN", "Media", "Media transfer failed: " + context + " stage=" + stage + " errorType=" + e.getClass().getSimpleName());
            if (created != null) { try { delete(created); } catch (Exception ignored) { } }
            throw e;
        } catch (Exception e) { logs.add("WARN", "Media", "Media transfer failed: " + context + " stage=" + stage); throw new IllegalStateException("Media transfer failed"); }
    }

    public void delete(String name) {
        // 通过资源删除触发 Halo 附件 finalizer，避免绕过持久化的文件清理流程。
        client.fetch(Attachment.class, name).flatMap(client::delete).block(Duration.ofSeconds(15));
    }

    public java.util.List<String> owned(String recordName) {
        return client.listAll(Attachment.class, new run.halo.app.extension.ListOptions(),
            org.springframework.data.domain.Sort.unsorted())
            .filter(value -> value.getMetadata().getAnnotations() != null
                && recordName.equals(value.getMetadata().getAnnotations().get(SyncPublisher.PREFIX + "record")))
            .map(value -> value.getMetadata().getName()).collectList().block(Duration.ofSeconds(15));
    }
}
