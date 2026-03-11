package vip.mystery0.halo.telegrammoment.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import vip.mystery0.halo.telegrammoment.PluginLogger;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.handler.MessageHandler;

@Slf4j
@RequiredArgsConstructor
public class TelegramUpdateHandler implements LongPollingSingleThreadUpdateConsumer {

    private static final String CMD_RM = "/rm";
    private static final String CMD_REDO = "/redo";

    private final MessageHandler messageHandler;
    private final TelegramSetting setting;
    private final OkHttpTelegramClient telegramClient;

    @Override
    public void consume(Update update) {
        try {
            if (update.hasChannelPost()) {
                PluginLogger.debug(log, "处理 Telegram Channel Post，fromUsername={}", update.getChannelPost().getFrom().getUserName());
                handleChannelPost(update.getChannelPost());
            } else if (update.hasEditedChannelPost()) {
                PluginLogger.debug(log, "处理 Telegram Channel Post 编辑，fromUsername={}", update.getEditedChannelPost().getFrom().getUserName());
                messageHandler.handleMessage(update.getEditedChannelPost(), true, true, setting, telegramClient);
            } else if (update.hasMessage()) {
                PluginLogger.debug(log, "处理 Telegram Private Message，fromUsername={}", update.getMessage().getFrom().getUserName());
                messageHandler.handleMessage(update.getMessage(), false, false, setting, telegramClient);
            } else if (update.hasEditedMessage()) {
                PluginLogger.debug(log, "处理 Telegram Private Message 编辑，fromUsername={}", update.getEditedMessage().getFrom().getUserName());
                messageHandler.handleMessage(update.getEditedMessage(), true, false, setting, telegramClient);
            }
        } catch (Exception e) {
            PluginLogger.error(log, "处理 Telegram Update 异常，updateId={}", update.getUpdateId(), e);
        }
    }

    private void handleChannelPost(Message message) {
        String text = message.getText();

        if (CMD_RM.equals(text)) {
            Message target = message.isReply() ? message.getReplyToMessage() : null;
            if (target != null) {
                messageHandler.handleDelete(target);
            } else {
                PluginLogger.warn(log, "/rm 指令无回复目标，忽略");
            }
        } else if (CMD_REDO.equals(text)) {
            Message target = message.getReplyToMessage();
            if (target != null) {
                messageHandler.handleMessage(target, false, true, setting, telegramClient);
            } else {
                PluginLogger.warn(log, "/redo 指令无回复目标，忽略");
            }
        } else {
            messageHandler.handleMessage(message, false, true, setting, telegramClient);
        }
    }
}
