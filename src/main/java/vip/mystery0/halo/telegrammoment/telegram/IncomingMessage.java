package vip.mystery0.halo.telegrammoment.telegram;

import com.fasterxml.jackson.databind.JsonNode;

/** 仅保留同步所需字段，不持久化完整 Telegram Update。 */
public record IncomingMessage(long chatId, long messageId, long date, String chatType,
    String albumId, String text, JsonNode entities, String fileId, String fileUniqueId,
    String mediaType, String unsupported, long updateId) {
    public IncomingMessage withUpdate(long id) {
        return new IncomingMessage(chatId, messageId, date, chatType, albumId, text, entities,
            fileId, fileUniqueId, mediaType, unsupported, id);
    }
}
