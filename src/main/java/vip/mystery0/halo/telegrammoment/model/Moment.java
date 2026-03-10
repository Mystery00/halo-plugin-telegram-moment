package vip.mystery0.halo.telegrammoment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "moment.halo.run",
        version = "v1alpha1",
        kind = "Moment",
        plural = "moments",
        singular = "moment")
public class Moment extends AbstractExtension {

    @JsonProperty("spec")
    private MomentSpec spec = new MomentSpec();

    @Data
    public static class MomentSpec {
        private Content content = new Content();
        /** ISO 8601 格式，例如 "2024-01-01T00:00:00.000Z" */
        private String releaseTime;
        private List<String> tags = new ArrayList<>();
        private String visible = "PUBLIC";
        private String owner = "";
    }

    @Data
    public static class Content {
        private String raw = "";
        private String html = "";
        private List<MediumItem> medium = new ArrayList<>();
    }

    @Data
    public static class MediumItem {
        /** "PHOTO" 或 "VIDEO" */
        private String type;
        /** Halo 附件的 permalink URL */
        private String url;
        /** MIME 类型，如 "image/jpeg" */
        @JsonProperty("originType")
        private String originType;
    }

    /** 注解键常量 */
    public static final String ANNOTATION_MESSAGE_ID = "messageId";
    public static final String ANNOTATION_CHAT_ID = "chatId";
    public static final String ANNOTATION_ATTACHMENT_NAMES = "attachmentNames";
}
