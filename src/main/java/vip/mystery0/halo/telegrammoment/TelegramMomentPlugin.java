package vip.mystery0.halo.telegrammoment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.moments.Moment;
import vip.mystery0.halo.telegrammoment.PluginLogger;
import vip.mystery0.halo.telegrammoment.bot.TelegramBotService;

@Slf4j
@Component
public class TelegramMomentPlugin extends BasePlugin {
    @Autowired
    private TelegramBotService botService;

    @Autowired
    private SchemeManager schemeManager;

    public TelegramMomentPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        // 注册 Moment 扩展类型，确保本插件的 class 对象在索引系统中有对应条目。
        // 若 moments 插件已安装并使用了相同的 GVK，SchemeManager 会幂等处理重复注册。
        schemeManager.register(Moment.class);
        PluginLogger.info(log, "Telegram Moment 插件启动");
        botService.startBot();
    }

    @Override
    public void stop() {
        PluginLogger.info(log, "Telegram Moment 插件停止");
        botService.stopBot();
    }
}
