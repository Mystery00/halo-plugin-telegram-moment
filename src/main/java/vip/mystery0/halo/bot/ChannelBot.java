package vip.mystery0.halo.bot;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Slf4j
public class ChannelBot implements LongPollingUpdateConsumer {
    private final TelegramClient telegramClient;

    public ChannelBot(String botToken) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public void consume(List<Update> list) {
        for (Update update : list) {
            handleUpdate(update);
        }
    }

    private void handleUpdate(Update update) {
        if (update.hasChannelPost()) {
            handleChannelPost(update.getChannelPost());
        }
    }

    private void handleChannelPost(Message message) {
        TelegramMessage telegramMessage = TelegramMessage.builder(message)
            .channel();
    }
}
