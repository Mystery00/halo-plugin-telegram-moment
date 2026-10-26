package vip.mystery0.halo.telegrammoment.sync;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "telegram-moment.halo.run", version = "v1alpha1", kind = "TelegramBotCursor",
    plural = "telegrambotcursors", singular = "telegrambotcursor")
public class BotCursor extends AbstractExtension {
    private Spec spec = new Spec();
    @Data public static class Spec {
        private long nextOffset;
        private String repliesJson = "{}";
    }
}
