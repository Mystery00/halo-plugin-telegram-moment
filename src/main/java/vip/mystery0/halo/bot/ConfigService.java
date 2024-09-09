package vip.mystery0.halo.bot;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import run.halo.app.plugin.SettingFetcher;
import vip.mystery0.halo.conf.BaseSettings;
import vip.mystery0.halo.conf.ChannelSettings;
import java.util.Optional;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Service
@RequiredArgsConstructor
public class ConfigService {
    private final SettingFetcher settingFetcher;

    @Nullable
    public BaseSettings getBaseSettings() {
        Optional<BaseSettings> optional = settingFetcher.fetch(BaseSettings.GROUP, BaseSettings.class);
        return optional.orElse(null);
    }

    @Nullable
    public ChannelSettings getChannelSettings() {
        Optional<ChannelSettings> optional = settingFetcher.fetch(ChannelSettings.GROUP, ChannelSettings.class);
        return optional.orElse(null);
    }
}
