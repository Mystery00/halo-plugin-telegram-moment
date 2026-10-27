package vip.mystery0.halo.telegrammoment.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramSettingTest {
    @Test void oldConfigurationKeepsExistingPublicationDefaults() {
        var config = new run.halo.app.extension.ConfigMap();
        config.setData(java.util.Map.of("moment", "{\"momentOwner\":\"owner\"}",
            "storage", "{\"storagePolicy\":\"policy\",\"storageGroup\":\"group\"}"));
        var settings = TelegramSetting.from(config);
        assertThat(settings.getMomentOwner()).isEqualTo("owner");
        assertThat(settings.getStoragePolicy()).isEqualTo("policy");
        assertThat(settings.getStorageGroup()).isEqualTo("group");
        assertThat(settings.getMomentVisibility()).isEqualTo("PUBLIC");
        assertThat(settings.isMomentAutoApprove()).isTrue();
        assertThat(settings.isMomentIncludeSourceLink()).isFalse();
        assertThat(settings.isCommandCleanupEnabled()).isTrue();
        assertThat(settings.isDeleteSourceMessages()).isFalse();
    }

    @Test void savesPublicationOptionsAndIndependentCommandOptions() {
        var config = new run.halo.app.extension.ConfigMap();
        config.setData(java.util.Map.of("moment", """
            {"momentVisibility":"PRIVATE","momentAutoApprove":false,"momentDefaultTags":"#日常,技术",
             "momentContentPrefix":"前言","momentContentSuffix":"后记","momentIncludeSourceLink":true}
            """, "command", "{\"commandCleanupEnabled\":false,\"deleteSourceMessages\":true}"));
        var settings = TelegramSetting.from(config);
        assertThat(settings.getMomentVisibility()).isEqualTo("PRIVATE");
        assertThat(settings.isMomentAutoApprove()).isFalse();
        assertThat(settings.getMomentDefaultTagList()).containsExactly("日常", "技术");
        assertThat(settings.getMomentContentPrefix()).isEqualTo("前言");
        assertThat(settings.getMomentContentSuffix()).isEqualTo("后记");
        assertThat(settings.isMomentIncludeSourceLink()).isTrue();
        assertThat(settings.isCommandCleanupEnabled()).isFalse();
        assertThat(settings.isDeleteSourceMessages()).isTrue();
        assertThat(settings.fingerprint()).isNotEqualTo(new TelegramSetting().fingerprint());
    }

    @Test void defaultTagsNormalizeSeparatorsAndRemoveDuplicates() {
        var settings = new TelegramSetting();
        settings.setMomentDefaultTags(" #日常，技术\n日常, #技术\r\n # , ");
        assertThat(settings.getMomentDefaultTagList()).containsExactly("日常", "技术");
        settings.setMomentDefaultTags(null);
        assertThat(settings.getMomentDefaultTagList()).isEmpty();
    }

    @Test void invalidPublicationOptionsAreRejectedBeforeStarting() {
        var settings = new TelegramSetting();
        settings.setBotToken("123:valid"); settings.setMomentOwner("owner"); settings.setStoragePolicy("policy");
        settings.setMomentVisibility("DRAFT");
        assertThatThrownBy(settings::validate).hasMessageContaining("visibility");
        settings.setMomentVisibility("PRIVATE"); settings.setMomentContentPrefix("a".repeat(4097));
        assertThatThrownBy(settings::validate).hasMessageContaining("prefix and suffix");
        settings.setMomentContentPrefix(""); settings.setMomentDefaultTags("a".repeat(65));
        assertThatThrownBy(settings::validate).hasMessageContaining("Default tags");
        settings.setMomentDefaultTags("日常"); settings.validate();
    }

    @Test void trustedEndpointConfigurationMustInvalidateRunningSettings() {
        var config = new run.halo.app.extension.ConfigMap();
        config.setData(java.util.Map.of("bot", "{\"trustedEndpointAddresses\":\"proxy.example=100.64.0.7\"}"));
        assertThat(TelegramSetting.from(config).fingerprint()).isNotEqualTo(new TelegramSetting().fingerprint());
    }

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
