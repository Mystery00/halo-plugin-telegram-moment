package vip.mystery0.halo.telegrammoment.publisher;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
     *
     * @param text     消息原始文本（Unicode）
     * @param entities MessageEntity 列表
     * @return ContentResult 包含 html 和 tags
     */
    public static ContentResult build(String text, List<MessageEntity> entities) {
        if (text == null || text.isEmpty()) {
            return new ContentResult("", List.of());
        }

        // 只保留白名单内的 entity
        List<MessageEntity> filtered = entities.stream()
                .filter(e -> ALLOWED_TYPES.contains(e.getType()))
                .sorted((a, b) -> Integer.compare(a.getOffset(), b.getOffset()))
                .toList();

        List<String> tags = new ArrayList<>();
        // 使用 rune（Unicode codepoint）数组，与 Telegram 的 offset/length 保持一致
        int[] codePoints = text.codePoints().toArray();

        StringBuilder sb = new StringBuilder();
        int cursor = 0;

        for (MessageEntity entity : filtered) {
            int start = entity.getOffset();
            int end = entity.getOffset() + entity.getLength();

            // entity 之前的普通文本
            if (cursor < start) {
                appendLines(sb, cpSubstring(codePoints, cursor, start));
            }

            String entityText = cpSubstring(codePoints, start, end);

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
        if (cursor < codePoints.length) {
            appendLines(sb, cpSubstring(codePoints, cursor, codePoints.length));
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

    /** 从 Unicode codepoint 数组中截取子串 */
    private static String cpSubstring(int[] codePoints, int start, int end) {
        return new String(Arrays.copyOfRange(codePoints, start, end), 0, end - start);
    }
}
