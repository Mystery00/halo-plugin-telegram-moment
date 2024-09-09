package vip.mystery0.halo.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.PluginConfigUpdatedEvent;
import vip.mystery0.halo.bot.BotApp;
import vip.mystery0.halo.conf.BaseSettings;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Component
@Slf4j
public class ConfigUpdatedListener {
    @Autowired
    private BotApp botApp;

    @EventListener(classes = PluginConfigUpdatedEvent.class)
    public void onConfigUpdated(PluginConfigUpdatedEvent event) {
        log.info("config updated, restart bot");
        if (event.getNewConfig().containsKey(BaseSettings.GROUP)) {
            botApp.restart();
        }
    }
}
