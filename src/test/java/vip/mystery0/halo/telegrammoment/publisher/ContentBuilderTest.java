package vip.mystery0.halo.telegrammoment.publisher;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ContentBuilderTest {

    @Test
    void plainText_wrapsEachLineInParagraph() {
        ContentResult r = ContentBuilder.build("Hello\nWorld", List.of());
        assertThat(r.getHtml()).isEqualTo("<p>Hello</p><p>World</p>");
        assertThat(r.getTags()).isEmpty();
    }

    @Test
    void hashtag_extractedToTagsAndRenderedAsLink() {
        MessageEntity entity = MessageEntity.builder()
                .type("hashtag")
                .offset(0)
                .length(5) // "#java"
                .build();
        ContentResult r = ContentBuilder.build("#java 示例", List.of(entity));
        assertThat(r.getTags()).containsExactly("java");
        assertThat(r.getHtml()).contains("<a class=\"tag\"");
        assertThat(r.getHtml()).contains("#java");
    }

    @Test
    void boldEntity_wrappedInStrong() {
        MessageEntity entity = MessageEntity.builder()
                .type("bold")
                .offset(0)
                .length(5) // "Hello"
                .build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<strong>Hello</strong>");
    }

    @Test
    void italicEntity_wrappedInEm() {
        MessageEntity entity = MessageEntity.builder()
                .type("italic")
                .offset(0)
                .length(5)
                .build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<em>Hello</em>");
    }

    @Test
    void codeEntity_wrappedInCode() {
        MessageEntity entity = MessageEntity.builder()
                .type("code")
                .offset(0)
                .length(3)
                .build();
        ContentResult r = ContentBuilder.build("foo bar", List.of(entity));
        assertThat(r.getHtml()).contains("<code>foo</code>");
    }

    @Test
    void textLinkEntity_wrappedInAnchor() {
        MessageEntity entity = MessageEntity.builder()
                .type("text_link")
                .offset(0)
                .length(4)
                .url("https://example.com")
                .build();
        ContentResult r = ContentBuilder.build("link text", List.of(entity));
        assertThat(r.getHtml()).contains("<a href=\"https://example.com\">link</a>");
    }

    @Test
    void unknownEntityType_treatedAsPlainText() {
        MessageEntity entity = MessageEntity.builder()
                .type("mention")
                .offset(0)
                .length(5)
                .build();
        ContentResult r = ContentBuilder.build("@user text", List.of(entity));
        // mention 类型不处理，整段文字按普通文本输出
        assertThat(r.getHtml()).doesNotContain("<a");
        assertThat(r.getTags()).isEmpty();
    }

    @Test
    void emptyContent_returnsEmptyHtml() {
        ContentResult r = ContentBuilder.build("", List.of());
        assertThat(r.getHtml()).isEmpty();
        assertThat(r.getTags()).isEmpty();
    }

    @Test
    void underlineEntity_wrappedInU() {
        MessageEntity entity = MessageEntity.builder()
                .type("underline").offset(0).length(5).build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<u>Hello</u>");
    }

    @Test
    void strikethroughEntity_wrappedInS() {
        MessageEntity entity = MessageEntity.builder()
                .type("strikethrough").offset(0).length(5).build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.getHtml()).contains("<s>Hello</s>");
    }

    @Test
    void preEntity_wrappedInPreCode() {
        MessageEntity entity = MessageEntity.builder()
                .type("pre").offset(0).length(3).build();
        ContentResult r = ContentBuilder.build("foo bar", List.of(entity));
        assertThat(r.getHtml()).contains("<pre><code>foo</code></pre>");
    }

    @Test
    void emojiBeforeEntity_utf16OffsetCorrect() {
        // "😀" 是 U+1F600，占 2 个 UTF-16 code unit
        // 文本："😀 #java" → #java 的 UTF-16 offset 为 3
        MessageEntity entity = MessageEntity.builder()
                .type("hashtag").offset(3).length(5).build();
        ContentResult r = ContentBuilder.build("😀 #java", List.of(entity));
        assertThat(r.getTags()).containsExactly("java");
        assertThat(r.getHtml()).contains("#java");
    }
}
