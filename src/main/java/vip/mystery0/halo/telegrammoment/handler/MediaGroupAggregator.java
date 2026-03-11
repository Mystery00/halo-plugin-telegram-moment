package vip.mystery0.halo.telegrammoment.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.PluginLogger;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploadResult;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploader;
import vip.mystery0.halo.telegrammoment.publisher.ContentBuilder;
import vip.mystery0.halo.telegrammoment.publisher.ContentResult;
import vip.mystery0.halo.telegrammoment.publisher.MomentPublisher;
import run.halo.moments.Moment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaGroupAggregator {

    private final MomentPublisher momentPublisher;
    private final AttachmentUploader attachmentUploader;

    private final ConcurrentHashMap<String, PendingMediaGroup> pendingGroups = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    /**
     * 由 TelegramBotService 在 Bot 启动时调用
     */
    public void start(TelegramSetting setting, OkHttpTelegramClient telegramClient) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "media-group-aggregator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
            () -> drainReady(setting, telegramClient),
            5, 5, TimeUnit.SECONDS
        );
        PluginLogger.info(log, "MediaGroupAggregator 已启动");
    }

    /**
     * 由 TelegramBotService 在 Bot 停止时调用
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        pendingGroups.clear();
        PluginLogger.info(log, "MediaGroupAggregator 已停止");
    }

    /**
     * 将一条图片消息加入对应的媒体组。
     */
    public void add(String albumId, Message message, boolean isPrivate, TelegramSetting setting) {
        long executeTimeMs = System.currentTimeMillis() + setting.getMediaDelaySeconds() * 1000L;
        pendingGroups.compute(albumId, (key, existing) -> {
            if (existing == null) {
                PendingMediaGroup group = new PendingMediaGroup(executeTimeMs, isPrivate);
                group.add(message, executeTimeMs);
                return group;
            }
            existing.add(message, executeTimeMs);
            return existing;
        });
    }

    private void drainReady(TelegramSetting setting, OkHttpTelegramClient telegramClient) {
        pendingGroups.entrySet().removeIf(entry -> {
            PendingMediaGroup group = entry.getValue();
            if (!group.isReady()) {
                return false;
            }

            try {
                processGroup(group, setting, telegramClient);
            } catch (Exception e) {
                PluginLogger.error(log, "处理媒体组失败，albumId={}", entry.getKey(), e);
            }
            return true; // 无论成功失败，都从 map 移除
        });
    }

    private void processGroup(PendingMediaGroup group, TelegramSetting setting,
        OkHttpTelegramClient telegramClient) {
        List<Message> messages = group.getMessages();
        if (messages.isEmpty()) {
            return;
        }

        Message firstMsg = messages.getFirst();
        String captionText = firstMsg.getCaption() != null ? firstMsg.getCaption() : "";
        List<org.telegram.telegrambots.meta.api.objects.MessageEntity> captionEntities =
            firstMsg.getCaptionEntities() != null ? firstMsg.getCaptionEntities() : List.of();

        ContentResult contentResult = ContentBuilder.build(captionText, captionEntities);

        List<Moment.MomentMedia> medium = new ArrayList<>();
        List<String> attachmentNames = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.getPhoto() == null || msg.getPhoto().isEmpty()) {
                continue;
            }
            // 选最高分辨率的图片（FileSize 最大的）
            PhotoSize largest = msg.getPhoto().stream()
                .max(Comparator.comparingInt(PhotoSize::getFileSize))
                .orElse(msg.getPhoto().getLast());

            try {
                AttachmentUploadResult result = attachmentUploader.upload(largest.getFileId(), telegramClient, setting,
                    Moment.MomentMediaType.PHOTO);
                Moment.MomentMedia item = new Moment.MomentMedia();
                item.setType(Moment.MomentMediaType.PHOTO);
                item.setUrl(result.getPermalink());
                item.setOriginType(result.getMimeType());
                medium.add(item);
                attachmentNames.add(result.getAttachmentName());
            } catch (Exception e) {
                PluginLogger.error(log, "媒体组图片上传失败，跳过: {}", largest.getFileId(), e);
            }
        }

        momentPublisher.publish(
            contentResult.html(),
            contentResult.tags(),
            medium,
            attachmentNames,
            firstMsg.getMessageId(),
            firstMsg.getChatId(),
            firstMsg.getDate() != null ? Instant.ofEpochSecond(firstMsg.getDate()) : Instant.now()
        );
    }
}
