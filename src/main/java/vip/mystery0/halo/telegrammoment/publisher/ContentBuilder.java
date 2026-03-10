package vip.mystery0.halo.telegrammoment.publisher;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ContentBuilder {

    /** 需要处理的 Entity 类型白名单（其余类型跳过） */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "hashtag", "bold", "italic", "underline",
            "strikethrough", "code", "pre", "text_link"
    );

    /**
     * 将 Telegram 消息文本和 Entity 列表转换为 HTML 及标签列表。
     * <p>
     * Telegram Bot API 规定 offset/length 以 UTF-16 code unit 计数，
     * 与 Java String.charAt() 索引一致，直接使用 String.substring() 即可正确处理。
     *
     * @param text     消息原始文本
     * @param entities MessageEntity 列表
     * @return ContentResult 包含 html 和 tags
     */
    public static ContentResult build(String text, List<MessageEntity> entities) {
        if (text == null || text.isEmpty()) {
            return new ContentResult("", List.of());
        }

        // 只保留白名单内的 entity，并按 offset 排序
        List<MessageEntity> filtered = entities.stream()
                .filter(e -> ALLOWED_TYPES.contains(e.getType()))
                .sorted((a, b) -> Integer.compare(a.getOffset(), b.getOffset()))
                .toList();

        List<String> tags = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int cursor = 0; // UTF-16 索引

        for (MessageEntity entity : filtered) {
            int start = entity.getOffset();          // UTF-16 offset
            int end = start + entity.getLength();    // UTF-16 end

            // entity 之前的普通文本
            if (cursor < start) {
                appendLines(sb, text.substring(cursor, start));
            }

            // String.substring() 直接使用 UTF-16 索引，与 Telegram offset 对应
            String entityText = text.substring(start, end);

            switch (entity.getType()) {
                case "hashtag" -> {
                    // "#java" → tag = "java"
                    String tag = entityText.startsWith("#") ? entityText.substring(1) : entityText;
                    tags.add(tag);
                    String encoded = URLEncoder.encode(tag, StandardCharsets.UTF_8);
                    sb.append("<a class=\"tag\" href=\"?tag=").append(encoded).append("\">")
                            .append(entityText).append("</a>");
                }
                case "bold" -> sb.append("<strong>").append(entityText).append("</strong>");
                case "italic" -> sb.append("<em>").append(entityText).append("</em>");
                case "underline" -> sb.append("<u>").append(entityText).append("</u>");
                case "strikethrough" -> sb.append("<s>").append(entityText).append("</s>");
                case "code" -> sb.append("<code>").append(entityText).append("</code>");
                case "pre" -> sb.append("<pre><code>").append(entityText).append("</code></pre>");
                case "text_link" -> sb.append("<a href=\"").append(entity.getUrl()).append("\">")
                        .append(entityText).append("</a>");
                default -> sb.append(entityText); // 白名单外不会走到这里
            }
            cursor = end;
        }

        // 剩余文本
        if (cursor < text.length()) {
            appendLines(sb, text.substring(cursor));
        }

        return new ContentResult(sb.toString(), List.copyOf(tags));
    }

    /** 将换行分隔的文本按 &lt;p&gt; 标签拼入 StringBuilder */
    private static void appendLines(StringBuilder sb, String text) {
        if (text.isEmpty()) return;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (!line.isEmpty()) {
                sb.append("<p>").append(line).append("</p>");
            }
        }
    }
}
