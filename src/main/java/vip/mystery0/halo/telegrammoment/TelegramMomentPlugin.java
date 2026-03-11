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
        // Moment 类型由 moments 插件（pluginDependencies）负责注册，无需重复注册
        PluginLogger.info(log, "Telegram Moment 插件启动");
        botService.startBot();
    }

    @Override
    public void stop() {
        PluginLogger.info(log, "Telegram Moment 插件停止");
        botService.stopBot();
    }
}
