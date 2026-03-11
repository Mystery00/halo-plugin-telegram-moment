package vip.mystery0.halo.telegrammoment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import vip.mystery0.halo.telegrammoment.bot.TelegramBotService;

@Slf4j
@Component
public class TelegramMomentPlugin extends BasePlugin {
    @Autowired
    private TelegramBotService botService;

    public TelegramMomentPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("Telegram Moment 插件启动");
        botService.startBot();
    }

    @Override
    public void stop() {
        log.info("Telegram Moment 插件停止");
        botService.stopBot();
    }
}
