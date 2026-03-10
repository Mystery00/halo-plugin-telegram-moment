package vip.mystery0.halo.telegrammoment.handler;

import lombok.Getter;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class PendingMediaGroup {
    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong expectedExecuteTimeMs;
    private final boolean isPrivate;

    public PendingMediaGroup(long executeTimeMs, boolean isPrivate) {
        this.expectedExecuteTimeMs = new AtomicLong(executeTimeMs);
        this.isPrivate = isPrivate;
    }

    public void add(Message message, long newExecuteTimeMs) {
        messages.add(message);
        expectedExecuteTimeMs.set(newExecuteTimeMs);
    }

    public boolean isReady() {
        return System.currentTimeMillis() >= expectedExecuteTimeMs.get();
    }
}
