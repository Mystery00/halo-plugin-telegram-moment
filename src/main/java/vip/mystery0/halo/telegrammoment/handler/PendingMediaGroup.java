package vip.mystery0.halo.telegrammoment.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import org.telegram.telegrambots.meta.api.objects.message.Message;

@Getter
public class PendingMediaGroup {
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong expectedExecuteTimeMs;
    private final boolean isPrivate;
    /** 发送"正在处理"回复后的回复消息 ID，null 表示未发送回复 */
    private volatile Integer replyMessageId;
    private volatile long replyChatId;

    public PendingMediaGroup(long executeTimeMs, boolean isPrivate) {
        this.expectedExecuteTimeMs = new AtomicLong(executeTimeMs);
        this.isPrivate = isPrivate;
    }

    public void setReply(long chatId, int messageId) {
        this.replyChatId = chatId;
        this.replyMessageId = messageId;
    }

    public void add(Message message, long newExecuteTimeMs) {
        messages.add(message);
        expectedExecuteTimeMs.set(newExecuteTimeMs);
    }

    public boolean isReady() {
        return System.currentTimeMillis() >= expectedExecuteTimeMs.get();
    }
}
