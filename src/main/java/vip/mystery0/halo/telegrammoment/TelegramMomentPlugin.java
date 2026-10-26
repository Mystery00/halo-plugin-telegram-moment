package vip.mystery0.halo.telegrammoment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.index.IndexSpecs;
import vip.mystery0.halo.telegrammoment.bot.TelegramBotService;
import vip.mystery0.halo.telegrammoment.sync.SyncRecord;
import vip.mystery0.halo.telegrammoment.sync.BotCursor;

@Component
public class TelegramMomentPlugin extends BasePlugin {
    @Autowired private TelegramBotService botService;
    @Autowired private SchemeManager schemeManager;
    private Disposable startup;
    public TelegramMomentPlugin(PluginContext context) { super(context); }
    @Override public void start() {
        schemeManager.register(SyncRecord.class, indexes -> indexes.add(IndexSpecs.<SyncRecord, String>single("spec.botId", String.class)
            .indexFunc(record -> Long.toString(record.getSpec().getBotId())).build()));
        schemeManager.register(BotCursor.class);
        startup = Schedulers.boundedElastic().schedule(botService::startBot);
    }
    @Override public void stop() {
        if (startup != null) startup.dispose();
        botService.stopBot();
        // 只注销自身模型，保留持久化数据，绝不注销瞬间插件的模型。
        schemeManager.fetch(new GroupVersionKind("telegram-moment.halo.run", "v1alpha1", "TelegramSyncRecord")).ifPresent(schemeManager::unregister);
        schemeManager.fetch(new GroupVersionKind("telegram-moment.halo.run", "v1alpha1", "TelegramBotCursor")).ifPresent(schemeManager::unregister);
    }
}
