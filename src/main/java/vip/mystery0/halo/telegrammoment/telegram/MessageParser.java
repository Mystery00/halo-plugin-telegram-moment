package vip.mystery0.halo.telegrammoment.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.stream.StreamSupport;
import java.util.Comparator;
import java.util.regex.Pattern;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;

public final class MessageParser {
    private MessageParser() { }
    public static Optional<IncomingMessage> parse(JsonNode message, TelegramSetting settings) {
        if (!rejectionReason(message, settings).isEmpty()) return Optional.empty();
        String type = message.path("chat").path("type").asText();
        long chat = message.path("chat").path("id").asLong();
        return parseContent(message, type, chat);
    }

    /** 返回固定诊断原因，不拼接外部消息内容。 */
    public static String rejectionReason(JsonNode message, TelegramSetting settings) {
        if (message == null || !message.isObject() || message.path("message_id").asLong() <= 0) return "invalid message structure";
        String type = message.path("chat").path("type").asText();
        long chat = message.path("chat").path("id").asLong();
        if (type.equals("channel")) {
            if (!settings.isChannelEnabled()) return "channel reception is disabled";
            if (settings.getChannelId() != null && !settings.getChannelId().isBlank()) {
                try { if (chat != Long.parseLong(settings.getChannelId().trim())) return "channel ID does not match"; }
                catch (NumberFormatException e) { return "invalid configured channel ID"; }
            }
        } else if (type.equals("private")) {
            if (!settings.isPrivateEnabled()) return "private reception is disabled";
            try {
                long sender = Long.parseLong(settings.getPrivateSenderId().trim());
                if (sender <= 0 || chat != sender || message.path("from").path("id").asLong() != sender) return "private sender is not allowed";
            } catch (RuntimeException e) { return "invalid configured private sender ID"; }
        } else return "chat type is not supported";
        if (message.path("from").path("is_bot").asBoolean(false)) return "message was sent by a bot";
        return "";
    }

    private static Optional<IncomingMessage> parseContent(JsonNode message, String type, long chat) {
        String text = message.has("text") ? message.path("text").asText() : message.path("caption").asText("");
        JsonNode entities = message.path(message.has("text") ? "entities" : "caption_entities");
        String media = "", unsupported = "";
        JsonNode file = null;
        if (message.path("photo").isArray() && !message.path("photo").isEmpty()) {
            file = StreamSupport.stream(message.path("photo").spliterator(), false)
                .max(Comparator.comparingLong(p -> p.path("width").asLong() * p.path("height").asLong())).orElse(null);
            media = "PHOTO";
        } else if (message.has("video") || message.has("animation")) {
            file = message.path(message.has("video") ? "video" : "animation"); media = "VIDEO";
        } else if (message.has("sticker")) {
            file = message.path("sticker");
            media = file.path("is_video").asBoolean() ? "VIDEO" : "PHOTO";
            if (file.path("is_animated").asBoolean()) unsupported = "Animated TGS stickers are not supported";
        }
        if (text.length() > 65536) unsupported = "Message text exceeds limit";
        if (file != null && file.path("file_size").asLong() > TelegramApi.MAX_FILE_BYTES) unsupported = "Media exceeds 20 MiB limit";
        if (file == null && text.isBlank()) return Optional.empty();
        return Optional.of(new IncomingMessage(chat, message.path("message_id").asLong(), message.path("date").asLong(), type,
            message.path("media_group_id").asText(""), text.length() <= 65536 ? text : "", entities,
            file == null ? "" : file.path("file_id").asText(), file == null ? "" : file.path("file_unique_id").asText(),
            media, unsupported, 0));
    }
    public static boolean blocked(IncomingMessage message, TelegramSetting settings) {
        if (!message.chatType().equals("channel")) return false;
        var matcher = Pattern.compile("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]+)").matcher(message.text());
        while (matcher.find()) if (settings.getChannelFilterList().contains(matcher.group(1))) return true;
        return false;
    }
}
