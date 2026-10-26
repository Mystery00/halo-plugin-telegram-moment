package vip.mystery0.halo.telegrammoment.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import vip.mystery0.halo.telegrammoment.telegram.IncomingMessage;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "telegram-moment.halo.run", version = "v1alpha1", kind = "TelegramSyncRecord",
    plural = "telegramsyncrecords", singular = "telegramsyncrecord")
public class SyncRecord extends AbstractExtension {
    private Spec spec = new Spec();

    @Data
    public static class Spec {
        private long botId;
        private long chatId;
        private long lastUpdate = -1;
        private long revision;
        private long readyAt;
        private int attempts;
        private String state = "PENDING";
        private String action = "PUBLISH";
        private String error = "";
        private String membersJson = "[]";
        private String mediaJson = "{}";
        private String stagedJson = "{}";
        private String momentName = "";
        private List<String> cleanup = new ArrayList<>();
    }

    public List<IncomingMessage> members() { return Jsons.read(spec.membersJson, new TypeReference<>() { }); }
    public void members(List<IncomingMessage> messages) { spec.membersJson = Jsons.write(messages); }
}
