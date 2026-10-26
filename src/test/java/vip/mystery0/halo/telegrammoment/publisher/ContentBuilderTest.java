package vip.mystery0.halo.telegrammoment.publisher;

import org.junit.jupiter.api.Test;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ContentBuilderTest {
    @Test
    void partialPreIsOutsideParagraphAndKeepsNewlines() {
        var entities = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        entities.addObject().put("type", "pre").put("offset", 2).put("length", 3);
        assertThat(ContentBuilder.build("a\nx\ny\nz", entities).html())
            .isEqualTo("<p>a<br></p><pre><code>x\ny</code></pre><p><br>z</p>");
    }
    @lombok.Data
    @lombok.Builder
    static class TestEntity { private String type; private int offset; private int length; private String url; }

    @Test
    void escapesUntrustedHtml() {
        assertThat(ContentBuilder.build("<img src=x onerror=alert(1)>", List.of()).html())
            .isEqualTo("<p>&lt;img src=x onerror=alert(1)&gt;</p>");
    }

    @Test
    void nestedEntitiesDoNotDuplicateText() {
        var bold = TestEntity.builder().type("bold").offset(0).length(5).build();
        var italic = TestEntity.builder().type("italic").offset(1).length(3).build();
        assertThat(ContentBuilder.build("Hello", List.of(bold, italic)).html())
            .isEqualTo("<p><strong>H<em>ell</em>o</strong></p>");
    }

    @Test
    void rejectsExecutableLink() {
        var link = TestEntity.builder().type("text_link").offset(0).length(4)
            .url("javascript:alert(1)").build();
        assertThat(ContentBuilder.build("link", List.of(link)).html()).isEqualTo("<p>link</p>");
    }

    @Test
    void plainText_wrapsEachLineInParagraph() {
        ContentResult r = ContentBuilder.build("Hello\nWorld", List.of());
        assertThat(r.html()).isEqualTo("<p>Hello</p><p>World</p>");
        assertThat(r.tags()).isEmpty();
    }

    @Test
    void hashtag_extractedToTagsAndRenderedAsLink() {
        TestEntity entity = TestEntity.builder()
                .type("hashtag")
                .offset(0)
                .length(5) // "#java"
                .build();
        ContentResult r = ContentBuilder.build("#java 绀轰緥", List.of(entity));
        assertThat(r.tags()).containsExactly("java");
        assertThat(r.html()).contains("<a class=\"tag\"");
        assertThat(r.html()).contains("#java");
    }

    @Test
    void boldEntity_wrappedInStrong() {
        TestEntity entity = TestEntity.builder()
                .type("bold")
                .offset(0)
                .length(5) // "Hello"
                .build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.html()).contains("<strong>Hello</strong>");
    }

    @Test
    void italicEntity_wrappedInEm() {
        TestEntity entity = TestEntity.builder()
                .type("italic")
                .offset(0)
                .length(5)
                .build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.html()).contains("<em>Hello</em>");
    }

    @Test
    void codeEntity_wrappedInCode() {
        TestEntity entity = TestEntity.builder()
                .type("code")
                .offset(0)
                .length(3)
                .build();
        ContentResult r = ContentBuilder.build("foo bar", List.of(entity));
        assertThat(r.html()).contains("<code>foo</code>");
    }

    @Test
    void textLinkEntity_wrappedInAnchor() {
        TestEntity entity = TestEntity.builder()
                .type("text_link")
                .offset(0)
                .length(4)
                .url("https://example.com")
                .build();
        ContentResult r = ContentBuilder.build("link text", List.of(entity));
        assertThat(r.html()).contains("<a href=\"https://example.com\">link</a>");
    }

    @Test
    void unknownEntityType_treatedAsPlainText() {
        TestEntity entity = TestEntity.builder()
                .type("mention")
                .offset(0)
                .length(5)
                .build();
        ContentResult r = ContentBuilder.build("@user text", List.of(entity));
        // mention 绫诲瀷涓嶅鐞嗭紝鏁存鏂囧瓧鎸夋櫘閫氭枃鏈緭鍑?
        assertThat(r.html()).doesNotContain("<a");
        assertThat(r.tags()).isEmpty();
    }

    @Test
    void emptyContent_returnsEmptyHtml() {
        ContentResult r = ContentBuilder.build("", List.of());
        assertThat(r.html()).isEmpty();
        assertThat(r.tags()).isEmpty();
    }

    @Test
    void underlineEntity_wrappedInU() {
        TestEntity entity = TestEntity.builder()
                .type("underline").offset(0).length(5).build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.html()).contains("<u>Hello</u>");
    }

    @Test
    void strikethroughEntity_wrappedInS() {
        TestEntity entity = TestEntity.builder()
                .type("strikethrough").offset(0).length(5).build();
        ContentResult r = ContentBuilder.build("Hello World", List.of(entity));
        assertThat(r.html()).contains("<s>Hello</s>");
    }

    @Test
    void preEntity_wrappedInPreCode() {
        TestEntity entity = TestEntity.builder()
                .type("pre").offset(0).length(3).build();
        ContentResult r = ContentBuilder.build("foo bar", List.of(entity));
        assertThat(r.html()).contains("<pre><code>foo</code></pre>");
    }

    @Test
    void emojiBeforeEntity_utf16OffsetCorrect() {
        // "馃榾" 鏄?U+1F600锛屽崰 2 涓?UTF-16 code unit
        // 鏂囨湰锛?馃榾 #java" 鈫?#java 鐨?UTF-16 offset 涓?3
        TestEntity entity = TestEntity.builder()
                .type("hashtag").offset(3).length(5).build();
        ContentResult r = ContentBuilder.build("馃榾 #java", List.of(entity));
        assertThat(r.tags()).containsExactly("java");
        assertThat(r.html()).contains("#java");
    }
}
