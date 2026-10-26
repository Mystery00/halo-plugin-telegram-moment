package vip.mystery0.halo.telegrammoment.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** 按 UTF-16 区间渲染实体；跨区间格式通过关闭和重新打开标签处理。 */
public final class ContentBuilder {
    private ContentBuilder() { }
    private record Span(int start, int end, String open, String close) { }

    public static ContentResult build(String text, List<?> entities) {
        return build(text, new ObjectMapper().<JsonNode>valueToTree(entities));
    }

    public static ContentResult build(String text, JsonNode entities) {
        if (text == null || text.isEmpty()) return new ContentResult("", List.of());
        if (text.length() > 65536) throw new IllegalArgumentException("Message text exceeds limit");
        var spans = new ArrayList<Span>();
        var tags = new LinkedHashSet<String>();
        if (entities != null && entities.isArray()) {
            for (int n = 0; n < Math.min(entities.size(), 256); n++) {
                var entity = entities.get(n);
                int start = entity.path("offset").asInt(-1);
                int length = entity.path("length").asInt(-1);
                if (start < 0 || length <= 0 || start > text.length() - length) continue;
                int end = start + length;
                if (splitSurrogate(text, start) || splitSurrogate(text, end)) continue;
                String raw = text.substring(start, end);
                String open = "", close = "";
                switch (entity.path("type").asText()) {
                    case "bold" -> { open = "<strong>"; close = "</strong>"; }
                    case "italic" -> { open = "<em>"; close = "</em>"; }
                    case "underline" -> { open = "<u>"; close = "</u>"; }
                    case "strikethrough" -> { open = "<s>"; close = "</s>"; }
                    case "code" -> { open = "<code>"; close = "</code>"; }
                    case "pre" -> { open = "<pre><code>"; close = "</code></pre>"; }
                    case "hashtag" -> {
                        String tag = raw.startsWith("#") ? raw.substring(1) : raw;
                        if (!tag.isBlank()) {
                            tags.add(tag);
                            open = "<a class=\"tag\" href=\"?tag="
                                + URLEncoder.encode(tag, StandardCharsets.UTF_8) + "\">";
                            close = "</a>";
                        }
                    }
                    case "text_link", "url" -> {
                        String url = entity.path("type").asText().equals("url") ? raw : entity.path("url").asText();
                        if (safeLink(url)) { open = "<a href=\"" + escape(url) + "\">"; close = "</a>"; }
                    }
                    default -> { }
                }
                if (!open.isEmpty()) spans.add(new Span(start, end, open, close));
            }
        }
        spans.sort(Comparator.comparingInt(Span::start).thenComparing(Comparator.comparingInt(Span::end).reversed()));
        var html = new StringBuilder("<p>");
        List<Span> active = List.of();
        boolean inPre = false;
        for (int i = 0; i < text.length(); i++) {
            var next = new ArrayList<Span>();
            boolean anchor = false;
            for (Span span : spans) {
                if (span.start <= i && i < span.end) {
                    if (span.open.startsWith("<a ") && anchor) continue;
                    anchor |= span.open.startsWith("<a ");
                    next.add(span);
                }
            }
            var pre = next.stream().filter(s -> s.open.equals("<pre><code>")).findFirst();
            boolean nextPre = pre.isPresent();
            if (nextPre) { next.clear(); next.add(pre.get()); }
            if (inPre != nextPre) {
                for (int j = active.size() - 1; j >= 0; j--) html.append(active.get(j).close);
                active = List.of();
                html.append(nextPre ? "</p>" : "<p>");
                inPre = nextPre;
            }
            int common = 0;
            while (common < active.size() && common < next.size() && active.get(common) == next.get(common)) common++;
            for (int j = active.size() - 1; j >= common; j--) html.append(active.get(j).close);
            for (int j = common; j < next.size(); j++) html.append(next.get(j).open);
            active = next;
            if (text.charAt(i) == '\n') html.append(inPre ? "\n" : "<br>");
            else html.append(escape(String.valueOf(text.charAt(i))));
        }
        for (int j = active.size() - 1; j >= 0; j--) html.append(active.get(j).close);
        if (!inPre) html.append("</p>");
        String result = html.toString().replace("<p></p>", "");
        if (spans.isEmpty()) result = result.replace("<br>", "</p><p>");
        return new ContentResult(result, List.copyOf(tags));
    }

    private static boolean splitSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
            && Character.isLowSurrogate(text.charAt(offset));
    }

    private static boolean safeLink(String url) {
        try { return List.of("http", "https", "mailto", "tg").contains(URI.create(url).getScheme()); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
