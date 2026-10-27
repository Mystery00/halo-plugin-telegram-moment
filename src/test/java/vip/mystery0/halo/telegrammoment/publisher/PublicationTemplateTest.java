package vip.mystery0.halo.telegrammoment.publisher;

import java.util.List;
import org.junit.jupiter.api.Test;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.sync.Jsons;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;
import static org.assertj.core.api.Assertions.assertThat;

class PublicationTemplateTest {
    @Test void plainTextDecorationsKeepOriginalEntityOffsetsAndMergeTags() {
        var settings = new TelegramSetting();
        settings.setMomentContentPrefix("<b>前言</b>\n#prefix");
        settings.setMomentContentSuffix("结语 & <script>alert(1)</script>");
        settings.setMomentDefaultTags("daily, telegram, daily");
        var entities = Jsons.MAPPER.createArrayNode();
        entities.addObject().put("type", "bold").put("offset", 3).put("length", 5);
        entities.addObject().put("type", "hashtag").put("offset", 9).put("length", 6);
        var message = new IncomingMessage(-1_001_234_567_890L, 20, 1, "channel", "", "😀 hello #daily",
            entities, "", "", "", "", 1);

        var content = PublicationTemplate.build(message, message, settings);

        assertThat(content.html()).startsWith("<p>&lt;b&gt;前言&lt;/b&gt;</p><p>#prefix</p><p>😀 ")
            .contains("<strong>hello</strong>", "<a class=\"tag\" href=\"?tag=daily\">#daily</a>")
            .endsWith("<p>结语 &amp; &lt;script&gt;alert(1)&lt;/script&gt;</p>")
            .doesNotContain("<b>", "<script>", "?tag=prefix");
        assertThat(content.raw()).isEqualTo("<b>前言</b>\n#prefix\n\n😀 hello #daily\n\n结语 & <script>alert(1)</script>");
        assertThat(content.tags()).containsExactly("daily", "telegram");
    }

    @Test void defaultsLeaveOriginalTextAndFormattingUnchanged() {
        var message = message(-1_001_234_567_890L, 20, "channel", "Hello\nWorld");
        var content = PublicationTemplate.build(message, message, new TelegramSetting());
        assertThat(content.raw()).isEqualTo("Hello\nWorld");
        assertThat(content.html()).isEqualTo("<p>Hello</p><p>World</p>");
        assertThat(content.tags()).isEmpty();
    }

    @Test void sourceLinkUsesAlbumFirstMemberAndCorrectBotApiIdConversion() {
        var settings = new TelegramSetting(); settings.setMomentIncludeSourceLink(true);
        var first = message(-1_234_567_890_123L, 20, "channel", "");
        var caption = message(first.chatId(), 21, "channel", "Caption on second member");

        var content = PublicationTemplate.build(caption, first, settings);

        assertThat(content.html()).isEqualTo("<p>Caption on second member</p>"
            + "<p><a href=\"https://t.me/c/234567890123/20\">Telegram 原消息</a></p>");
        assertThat(content.raw()).isEqualTo("Caption on second member\n\nTelegram 原消息：https://t.me/c/234567890123/20");
    }

    @Test void privateChatsAndUnsupportedSourceIdsNeverProduceSourceLinks() {
        var settings = new TelegramSetting(); settings.setMomentIncludeSourceLink(true);
        for (var source : List.of(
            message(12345, 20, "private", "Private text"),
            message(-1_001_234_567_890L, 20, "private", "Private text"),
            message(-1_001_234_567_890L, 20, "supergroup", "Group text"),
            message(-100, 20, "channel", "Invalid source"),
            message(Long.MIN_VALUE, 20, "channel", "Invalid source"),
            message(-1_000_000_000_000L, 20, "channel", "Invalid source"),
            message(-1_997_852_516_353L, 20, "channel", "Invalid source"),
            message(-1_001_234_567_890L, 0, "channel", "Invalid message"))) {
            var content = PublicationTemplate.build(source, source, settings);
            assertThat(content.html()).doesNotContain("t.me", "Telegram 原消息");
            assertThat(content.raw()).isEqualTo(source.text());
        }
    }

    @Test void publicChannelsPreferTheirUsernameAndFirstAlbumMessage() {
        var settings = new TelegramSetting(); settings.setMomentIncludeSourceLink(true);
        var first = new IncomingMessage(-1_001_234_567_890L, 20, 1, "channel", "album", "", null,
            "", "", "", "", 1, "Mystery0_Channel");
        var caption = message(first.chatId(), 21, "channel", "Caption");
        var content = PublicationTemplate.build(caption, first, settings);
        assertThat(content.html()).contains("href=\"https://t.me/Mystery0_Channel/20\"").doesNotContain("t.me/c/");
        assertThat(content.raw()).endsWith("Telegram 原消息：https://t.me/Mystery0_Channel/20");

        var shortUsername = new IncomingMessage(first.chatId(), 20, 1, "channel", "", "", null,
            "", "", "", "", 1, "news");
        assertThat(PublicationTemplate.build(shortUsername, shortUsername, settings).html()).contains("https://t.me/news/20");
    }

    @Test void maliciousOrMissingUsernamesFallBackToNumericChannelLink() {
        var settings = new TelegramSetting(); settings.setMomentIncludeSourceLink(true);
        for (String username : new String[] { null, "", "../../login", "evil\" onclick=\"alert(1)", "name?start=token",
            "name/123", "name%2F123", "@ChannelName", "频道名称", "a".repeat(33) }) {
            var source = new IncomingMessage(-1_001_234_567_890L, 20, 1, "channel", "", "", null,
                "", "", "", "", 1, username);
            assertThat(PublicationTemplate.build(source, source, settings).html())
                .isEqualTo("<p><a href=\"https://t.me/c/1234567890/20\">Telegram 原消息</a></p>");
        }
        var privateMessage = new IncomingMessage(12345, 20, 1, "private", "", "Private text", null,
            "", "", "", "", 1, "PersonalUsername");
        var privateContent = PublicationTemplate.build(privateMessage, privateMessage, settings);
        assertThat(privateContent.html()).isEqualTo("<p>Private text</p>");
        assertThat(privateContent.raw()).isEqualTo("Private text");
    }

    @Test void validChannelIdBoundaryValuesProduceLinks() {
        var settings = new TelegramSetting(); settings.setMomentIncludeSourceLink(true);
        var first = message(-1_000_000_000_001L, 1, "channel", "");
        var last = message(-1_997_852_516_352L, 1, "channel", "");
        assertThat(PublicationTemplate.build(first, first, settings).html()).contains("https://t.me/c/1/1");
        assertThat(PublicationTemplate.build(last, last, settings).html()).contains("https://t.me/c/997852516352/1");
    }

    private IncomingMessage message(long chat, long id, String type, String text) {
        return new IncomingMessage(chat, id, 1, type, "", text, null, "", "", "", "", 1);
    }
}
