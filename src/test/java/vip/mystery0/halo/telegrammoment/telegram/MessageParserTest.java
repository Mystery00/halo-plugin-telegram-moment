package vip.mystery0.halo.telegrammoment.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import static org.assertj.core.api.Assertions.*;

class MessageParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final TelegramSetting settings = new TelegramSetting();

    @Test void channelUsernameSurvivesPersistenceAndRevisionChanges() throws Exception {
        var parsed = MessageParser.parse(json.readTree("""
            {"message_id":12,"chat":{"id":-1001234567890,"type":"channel","username":"Public_Channel"},
             "text":"hello","date":123}
            """), settings).orElseThrow().withUpdate(42);
        var restored = json.readValue(json.writeValueAsString(parsed), IncomingMessage.class);
        assertThat(restored.chatUsername()).isEqualTo("Public_Channel");
        assertThat(restored.updateId()).isEqualTo(42);
    }

    @Test void oldStoredMessagesWithoutUsernameRemainReadable() throws Exception {
        var restored = json.readValue("""
            {"chatId":-1001234567890,"messageId":12,"date":123,"chatType":"channel","albumId":"",
             "text":"hello","entities":null,"fileId":"","fileUniqueId":"","mediaType":"","unsupported":"","updateId":1}
            """, IncomingMessage.class);
        assertThat(restored.text()).isEqualTo("hello");
        assertThat(restored.withUpdate(2).chatUsername()).isNull();
        assertThat(restored.withUpdate(2).updateId()).isEqualTo(2);
    }

    @Test void channelWithoutFromIsAccepted() throws Exception {
        var message = json.readTree("""
            {"message_id":12,"chat":{"id":-100,"type":"channel"},"text":"hello","date":123}
            """);
        var result = MessageParser.parse(message, settings).orElseThrow();
        assertThat(result.text()).isEqualTo("hello");
        assertThat(result.messageId()).isEqualTo(12);
    }

    @Test void groupsAreNeverTreatedAsPrivate() throws Exception {
        settings.setPrivateEnabled(true);
        settings.setPrivateSenderId("10");
        assertThat(MessageParser.parse(json.readTree("""
            {"message_id":1,"chat":{"id":10,"type":"supergroup"},"from":{"id":10},"text":"hello"}
            """), settings)).isEmpty();
    }

    @Test void privateMessagesRequireMatchingUserAndChat() throws Exception {
        settings.setPrivateEnabled(true);
        settings.setPrivateSenderId("10");
        assertThat(MessageParser.parse(json.readTree("""
            {"message_id":1,"chat":{"id":10,"type":"private"},"from":{"id":11},"text":"hello"}
            """), settings)).isEmpty();
    }

    @Test void missingPhotoSizeDoesNotDiscardPhoto() throws Exception {
        var result = MessageParser.parse(json.readTree("""
            {"message_id":1,"chat":{"id":-100,"type":"channel"},"photo":[
            {"file_id":"small","width":100,"height":100}, {"file_id":"large","width":500,"height":500}]}
            """), settings).orElseThrow();
        assertThat(result.fileId()).isEqualTo("large");
    }

    @Test void exactHashtagsDoNotFilterPrefixes() throws Exception {
        settings.setChannelFilter("ad");
        var message = json.readTree("""
            {"message_id":1,"chat":{"id":-100,"type":"channel"},"text":"#admin"}
            """);
        assertThat(MessageParser.blocked(MessageParser.parse(message, settings).orElseThrow(), settings)).isFalse();
    }

    @Test void invalidSenderCannotWidenAccess() {
        settings.setBotToken("123:test"); settings.setMomentOwner("owner"); settings.setStoragePolicy("local");
        settings.setPrivateEnabled(true);
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class);
        settings.setPrivateSenderId("typo");
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
