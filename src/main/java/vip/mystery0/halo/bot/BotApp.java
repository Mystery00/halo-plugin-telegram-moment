package vip.mystery0.halo.bot;

import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import vip.mystery0.halo.conf.BaseSettings;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Slf4j
@Service
public class BotApp {
    private final TelegramBotsLongPollingApplication botsApplication =
        new TelegramBotsLongPollingApplication();
    private Optional<String> botToken = Optional.empty();

    @Autowired
    private ConfigService configService;

    public void start() {
        if (botToken.isEmpty()) {
            return;
        }
        if (botsApplication.isRunning()) {
            return;
        }
        try {
            botsApplication.start();
        } catch (Exception e) {
            log.error("start bot failed", e);
        }
    }

    public void restart() {
        if (botsApplication.isRunning()) {
            try {
                botsApplication.stop();
            } catch (TelegramApiException e) {
                log.error("stop bot failed", e);
            }
        }
        String token = getApiToken();
        if (botToken.isPresent() && !StringUtils.equals(token, botToken.get())) {
            unregister();
        }
        botToken = Optional.ofNullable(token);
        checkRegister();
        start();
    }

    @Nullable
    private String getApiToken() {
        BaseSettings settings = configService.getBaseSettings();
        if (settings == null) {
            return null;
        }
        if (!settings.enabled()) {
            log.info("bot is disabled");
            return null;
        }
        if (StringUtils.isBlank(settings.botToken())) {
            log.error("bot token is empty");
            return null;
        }
        return settings.botToken();
    }

    private void checkRegister() {
        if (botToken.isEmpty()) {
            return;
        }
        try {
            botsApplication.registerBot(botToken.get(), new ChannelBot(botToken.get()));
        } catch (TelegramApiException e) {
            log.error("register bot failed", e);
        }
    }

    private void unregister() {
        if (botToken.isEmpty()) {
            return;
        }
        try {
            botsApplication.unregisterBot(botToken.get());
        } catch (TelegramApiException e) {
            log.error("unregister bot failed", e);
        }
    }

    @PostConstruct
    public void init() {
        botToken = Optional.ofNullable(getApiToken());
        checkRegister();
        start();
    }

    @PreDestroy
    public void destroy() {
        try {
            botsApplication.close();
        } catch (Exception e) {
            log.error("error while closing bots application", e);
        }
    }
}
