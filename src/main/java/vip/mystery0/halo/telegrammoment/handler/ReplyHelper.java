package vip.mystery0.halo.telegrammoment.handler;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import vip.mystery0.halo.telegrammoment.PluginLogger;

/**
 * 处理 Telegram 回复消息的发送、编辑和延迟删除。
 */
@Slf4j
@Component
public class ReplyHelper {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-reply-delete-scheduler");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void destroy() {
        scheduler.shutdownNow();
    }

    /**
     * 向指定消息发送静音回复"检测到消息，正在处理……"。
     * 返回成功发送的回复消息 ID，失败时返回 empty。
     */
    public Optional<Integer> sendProcessingReply(OkHttpTelegramClient client, long chatId, int replyToMessageId) {
        try {
            ReplyParameters replyParameters = new ReplyParameters();
            replyParameters.setMessageId(replyToMessageId);

            SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("检测到消息，正在处理……")
                .replyParameters(replyParameters)
                .disableNotification(true)
                .build();
            Message sent = client.execute(msg);
            return Optional.of(sent.getMessageId());
        } catch (Exception e) {
            PluginLogger.warn(log, "发送处理中回复失败，chatId={}: {}", chatId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 将回复消息编辑为处理完成提示，并按配置延迟删除。
     * deleteSeconds=0 表示不自动删除。
     */
    public void editAndScheduleDelete(OkHttpTelegramClient client, long chatId, int replyMessageId,
        int deleteSeconds) {
        String text = deleteSeconds > 0
            ? "消息处理完成，本消息 " + deleteSeconds + " 秒后删除"
            : "消息处理完成";
        try {
            EditMessageText edit = EditMessageText.builder()
                .chatId(String.valueOf(chatId))
                .messageId(replyMessageId)
                .text(text)
                .build();
            client.execute(edit);
        } catch (Exception e) {
            PluginLogger.warn(log, "编辑回复消息失败，chatId={}, msgId={}: {}", chatId, replyMessageId, e.getMessage());
        }

        if (deleteSeconds > 0) {
            scheduler.schedule(() -> {
                try {
                    DeleteMessage del = DeleteMessage.builder()
                        .chatId(String.valueOf(chatId))
                        .messageId(replyMessageId)
                        .build();
                    client.execute(del);
                } catch (Exception e) {
                    PluginLogger.warn(log, "删除回复消息失败，chatId={}, msgId={}: {}", chatId, replyMessageId,
                        e.getMessage());
                }
            }, deleteSeconds, TimeUnit.SECONDS);
        }
    }
}
