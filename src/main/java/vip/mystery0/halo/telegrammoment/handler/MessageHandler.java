package vip.mystery0.halo.telegrammoment.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.model.Moment;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploadResult;
import vip.mystery0.halo.telegrammoment.publisher.AttachmentUploader;
import vip.mystery0.halo.telegrammoment.publisher.ContentBuilder;
import vip.mystery0.halo.telegrammoment.publisher.ContentResult;
import vip.mystery0.halo.telegrammoment.publisher.MomentPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageHandler {

    private final MomentPublisher momentPublisher;
    private final AttachmentUploader attachmentUploader;
    private final MediaGroupAggregator mediaGroupAggregator;

    /**
     * 处理一条消息（新建或编辑）。
     *
     * @param message         Telegram 消息对象
     * @param isEdit          true = 编辑已有消息，false = 新消息
     * @param isChannel       true = 来自频道，false = 来自私聊
     * @param setting         当前插件配置
     * @param telegramClient  已初始化的 TelegramClient（用于下载文件）
     */
    public void handleMessage(Message message,
                               boolean isEdit,
                               boolean isChannel,
                               TelegramSetting setting,
                               OkHttpTelegramClient telegramClient) {
        // 过滤检查
        if (!checkEnabled(message, isChannel, setting)) {
            return;
        }

        // 媒体组处理：图片组直接交给聚合器，提前返回
        if (message.getPhoto() != null
                && message.getMediaGroupId() != null
                && !isEdit) {
            mediaGroupAggregator.add(message.getMediaGroupId(), message,
                    !isChannel, setting);
            return;
        }

        // 构建 Post 数据
        String rawText;
        List<MessageEntity> entities;
        List<Moment.MediumItem> medium = new ArrayList<>();
        List<String> attachmentNames = new ArrayList<>();

        if (message.getPhoto() != null && !message.getPhoto().isEmpty()) {
            // 单张图片（无 AlbumID 或 isEdit）
            rawText = message.getCaption() != null ? message.getCaption() : "";
            entities = message.getCaptionEntities() != null
                    ? message.getCaptionEntities() : List.of();
            // 选最高分辨率的图片
            PhotoSize largest = message.getPhoto().stream()
                    .max((a, b) -> Integer.compare(a.getFileSize(), b.getFileSize()))
                    .orElse(message.getPhoto().getLast());
            uploadSingleMedia(largest.getFileId(), "PHOTO", setting, telegramClient,
                    medium, attachmentNames);

        } else if (message.getAnimation() != null) {
            // GIF
            rawText = message.getCaption() != null ? message.getCaption() : "";
            entities = message.getCaptionEntities() != null
                    ? message.getCaptionEntities() : List.of();
            uploadSingleMedia(message.getAnimation().getFileId(), "VIDEO", setting, telegramClient,
                    medium, attachmentNames);

        } else if (message.getSticker() != null) {
            // 贴纸
            rawText = "";
            entities = List.of();
            uploadSingleMedia(message.getSticker().getFileId(), "PHOTO", setting, telegramClient,
                    medium, attachmentNames);

        } else if (message.getText() != null && !message.getText().isBlank()) {
            // 纯文字
            rawText = message.getText();
            entities = message.getEntities() != null ? message.getEntities() : List.of();

        } else {
            log.debug("忽略不支持的消息类型，messageId={}", message.getMessageId());
            return;
        }

        ContentResult contentResult = ContentBuilder.build(rawText, entities);
        Instant releaseTime = message.getDate() != null
                ? Instant.ofEpochSecond(message.getDate())
                : Instant.now();

        if (isEdit) {
            momentPublisher.update(contentResult.getHtml(), contentResult.getTags(),
                    medium, attachmentNames,
                    message.getMessageId(), message.getChatId(), releaseTime);
        } else {
            momentPublisher.publish(contentResult.getHtml(), contentResult.getTags(),
                    medium, attachmentNames,
                    message.getMessageId(), message.getChatId(), releaseTime);
        }
    }

    /**
     * 处理删除消息请求。
     */
    public void handleDelete(Message targetMessage) {
        if (targetMessage == null) {
            log.warn("删除目标消息为空，跳过");
            return;
        }
        log.info("删除 Moment，messageId={}, chatId={}",
                targetMessage.getMessageId(), targetMessage.getChatId());
        momentPublisher.delete(targetMessage.getMessageId(), targetMessage.getChatId());
    }

    private boolean checkEnabled(Message message, boolean isChannel, TelegramSetting setting) {
        if (isChannel) {
            if (!setting.isChannelEnabled()) {
                log.debug("频道消息监听已关闭，跳过");
                return false;
            }
            // 指定频道 ID 过滤
            if (setting.getChannelId() != null && !setting.getChannelId().isBlank()) {
                try {
                    long expectedId = Long.parseLong(setting.getChannelId());
                    if (message.getChatId() != expectedId) {
                        log.debug("非目标频道 {}，跳过", message.getChatId());
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // Hashtag 过滤
            String text = message.getText() != null ? message.getText()
                    : (message.getCaption() != null ? message.getCaption() : "");
            for (String filterTag : setting.getChannelFilterList()) {
                if (text.contains("#" + filterTag)) {
                    log.info("消息含屏蔽标签 #{}，跳过", filterTag);
                    return false;
                }
            }
        } else {
            if (!setting.isPrivateEnabled()) {
                log.debug("私聊消息监听已关闭，跳过");
                return false;
            }
            if (setting.getPrivateSenderId() != null && !setting.getPrivateSenderId().isBlank()) {
                try {
                    long expectedSender = Long.parseLong(setting.getPrivateSenderId());
                    if (message.getChatId() != expectedSender) {
                        log.debug("非允许的私聊发送者 {}，跳过", message.getChatId());
                        return false;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return true;
    }

    private void uploadSingleMedia(String fileId, String mediaType,
                                    TelegramSetting setting,
                                    OkHttpTelegramClient telegramClient,
                                    List<Moment.MediumItem> medium,
                                    List<String> attachmentNames) {
        try {
            AttachmentUploadResult result = attachmentUploader.upload(
                    fileId, telegramClient, setting, mediaType);
            Moment.MediumItem item = new Moment.MediumItem();
            item.setType(mediaType);
            item.setUrl(result.getPermalink());
            item.setOriginType(result.getMimeType());
            medium.add(item);
            attachmentNames.add(result.getAttachmentName());
        } catch (Exception e) {
            log.error("单媒体文件上传失败，fileId={}，继续发布文字内容", fileId, e);
        }
    }
}
