package vip.mystery0.halo.telegrammoment.publisher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;

/** 为已经解析的正文添加发布选项，保留 Telegram 实体原本的 UTF-16 区间。 */
final class PublicationTemplate {
    private static final long CHANNEL_ID_OFFSET = 1_000_000_000_000L;
    private static final long MIN_BOT_CHANNEL_ID = -1_997_852_516_352L;
    private static final long MAX_BOT_CHANNEL_ID = -1_000_000_000_001L;

    private PublicationTemplate() { }

    record Content(String raw, String html, List<String> tags) { }

    static Content build(IncomingMessage caption, IncomingMessage source, TelegramSetting settings) {
        var body = ContentBuilder.build(caption.text(), caption.entities());
        var raw = new ArrayList<String>();
        var html = new StringBuilder();
        appendPlainText(raw, html, settings.getMomentContentPrefix());
        if (!caption.text().isEmpty()) {
            raw.add(caption.text());
            html.append(body.html());
        }
        appendPlainText(raw, html, settings.getMomentContentSuffix());
        if (settings.isMomentIncludeSourceLink()) {
            String sourceLink = sourceLink(source);
            if (!sourceLink.isEmpty()) {
                raw.add("Telegram 原消息：" + sourceLink);
                html.append("<p><a href=\"").append(sourceLink).append("\">Telegram 原消息</a></p>");
            }
        }
        var tags = new LinkedHashSet<>(settings.getMomentDefaultTagList());
        tags.addAll(body.tags());
        return new Content(String.join("\n\n", raw), html.toString(), List.copyOf(tags));
    }

    private static void appendPlainText(List<String> raw, StringBuilder html, String text) {
        if (text == null || text.isBlank()) return;
        raw.add(text);
        html.append(ContentBuilder.build(text, List.of()).html());
    }

    private static String sourceLink(IncomingMessage message) {
        if (!"channel".equals(message.chatType()) || message.messageId() <= 0) return "";
        String username = message.chatUsername();
        // 允许四字符收藏用户名，但不接受任意路径、查询参数或 HTML 字符。
        if (username != null && username.matches("[A-Za-z][A-Za-z0-9_]{3,31}"))
            return "https://t.me/" + username + "/" + message.messageId();
        if (message.chatId() < MIN_BOT_CHANNEL_ID || message.chatId() > MAX_BOT_CHANNEL_ID) return "";
        // Telegram 官方规定通过减去 10^12 转换 ID，不能简单截去字符串中的 -100。
        long channelId = -message.chatId() - CHANNEL_ID_OFFSET;
        return "https://t.me/c/" + channelId + "/" + message.messageId();
    }
}
