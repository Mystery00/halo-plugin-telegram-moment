package vip.mystery0.halo.bot;

import lombok.Getter;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Getter
public class TelegramMessage {
    private final Message message;
    private boolean isChannel;
    private boolean isEdit;
    private boolean isDelete;

    private TelegramMessage(Message message) {
        this.message = message;
    }

    public static TelegramMessage builder(Message message) {
        return new TelegramMessage(message);
    }

    public TelegramMessage channel() {
        isChannel = true;
        return this;
    }

    public TelegramMessage edit() {
        isEdit = true;
        isDelete = false;
        return this;
    }

    public TelegramMessage delete() {
        isDelete = true;
        isEdit = false;
        return this;
    }
}
