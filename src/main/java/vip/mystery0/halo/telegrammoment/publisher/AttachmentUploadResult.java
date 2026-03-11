package vip.mystery0.halo.telegrammoment.publisher;

import lombok.AllArgsConstructor;
import lombok.Data;
import vip.mystery0.halo.telegrammoment.model.Moment;

@Data
@AllArgsConstructor
public class AttachmentUploadResult {
    /** Halo 附件的 metadata.name，用于删除时关联 */
    private String attachmentName;
    /** 可公开访问的 permalink URL */
    private String permalink;
    /** MIME 类型，如 "image/jpeg" */
    private String mimeType;
    /** "PHOTO" 或 "VIDEO" */
    private Moment.MomentMediaType mediaType;
}
