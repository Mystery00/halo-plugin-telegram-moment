package vip.mystery0.halo.telegrammoment.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TelegramSettingTest {

    @Test
    void defaultValues() {
        TelegramSetting s = new TelegramSetting();
        assertThat(s.isChannelEnabled()).isTrue();
        assertThat(s.isPrivateEnabled()).isFalse();
        assertThat(s.getMediaDelaySeconds()).isEqualTo(3);
        assertThat(s.hasValidToken()).isFalse();
    }

    @Test
    void hasValidToken_whenBlank_returnsFalse() {
        TelegramSetting s = new TelegramSetting();
        s.setBotToken("  ");
        assertThat(s.hasValidToken()).isFalse();
    }

    @Test
    void hasValidToken_whenSet_returnsTrue() {
        TelegramSetting s = new TelegramSetting();
        s.setBotToken("123456:ABC");
        assertThat(s.hasValidToken()).isTrue();
    }

    @Test
    void getChannelFilterList_parsesCommaList() {
        TelegramSetting s = new TelegramSetting();
        s.setChannelFilter("dev, test, ad ");
        assertThat(s.getChannelFilterList()).containsExactly("dev", "test", "ad");
    }

    @Test
    void getChannelFilterList_emptyString_returnsEmpty() {
        TelegramSetting s = new TelegramSetting();
        assertThat(s.getChannelFilterList()).isEmpty();
    }
}
