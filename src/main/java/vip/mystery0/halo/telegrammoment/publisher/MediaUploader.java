package vip.mystery0.halo.telegrammoment.publisher;

import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;
import vip.mystery0.halo.telegrammoment.telegram.TelegramApi;

public interface MediaUploader {
    UploadedMedia upload(IncomingMessage message, String recordName, TelegramSetting settings, TelegramApi api);
    void delete(String attachmentName);
    default java.util.List<String> owned(String recordName) { return java.util.List.of(); }
    record UploadedMedia(String attachmentName, String url, String type, String mimeType) { }
}
