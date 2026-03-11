package vip.mystery0.halo.telegrammoment.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.PluginLogger;
import run.halo.moments.Moment;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MomentPublisher {
    /**
     * 注解键常量
     */
    public static final String ANNOTATION_MESSAGE_ID = "messageId";
    public static final String ANNOTATION_CHAT_ID = "chatId";
    public static final String ANNOTATION_ATTACHMENT_NAMES = "attachmentNames";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ReactiveExtensionClient extensionClient;

    private static final DateTimeFormatter RELEASE_TIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * 创建新 Moment。
     */
    public void publish(String content,
        List<String> tags,
        List<Moment.MomentMedia> medium,
        List<String> attachNames,
        long messageId,
        long chatId,
        Instant releaseTime) {
        Moment moment = buildMoment(content, tags, medium, attachNames, messageId, chatId, releaseTime);
        extensionClient.create(moment)
            .subscribeOn(Schedulers.boundedElastic())
            .block();
        PluginLogger.info(log, "发布 Moment 成功，messageId={}, chatId={}", messageId, chatId);
    }

    /**
     * 编辑消息：删除旧 Moment + 创建新 Moment。
     */
    public void update(String content,
        List<String> tags,
        List<Moment.MomentMedia> medium,
        List<String> attachNames,
        long messageId,
        long chatId,
        Instant releaseTime) {
        delete(messageId, chatId);
        publish(content, tags, medium, attachNames, messageId, chatId, releaseTime);
    }

    /**
     * 按 messageId + chatId 查找并删除对应 Moment 及其附件。
     */
    public void delete(long messageId, long chatId) {
        Optional<Moment> existing = findByMessageId(messageId, chatId);
        if (existing.isEmpty()) {
            PluginLogger.warn(log, "未找到对应 Moment，跳过删除。messageId={}, chatId={}", messageId, chatId);
            return;
        }
        Moment moment = existing.get();

        // 删除关联附件
        String attachNamesJson = moment.getMetadata().getAnnotations().getOrDefault(ANNOTATION_ATTACHMENT_NAMES, "[]");
        try {
            List<String> attachmentNames = objectMapper.readValue(attachNamesJson, new TypeReference<>() {
            });
            for (String name : attachmentNames) {
                deleteAttachment(name);
            }
        } catch (JsonProcessingException e) {
            PluginLogger.warn(log, "解析附件名称失败，跳过附件删除: {}", attachNamesJson, e);
        }

        // 删除 Moment
        extensionClient.delete(moment)
            .subscribeOn(Schedulers.boundedElastic())
            .block();
        PluginLogger.info(log, "删除 Moment 成功，messageId={}, chatId={}", messageId, chatId);
    }

    /**
     * 按 messageId + chatId 注解查找 Moment。
     */
    public Optional<Moment> findByMessageId(long messageId, long chatId) {
        String msgIdStr = String.valueOf(messageId);
        String chatIdStr = String.valueOf(chatId);

        return extensionClient.listAll(Moment.class, new ListOptions(), null)
            .filter(m -> {
                Map<String, String> annotations = m.getMetadata().getAnnotations();
                if (annotations == null) {
                    return false;
                }
                return msgIdStr.equals(annotations.get(ANNOTATION_MESSAGE_ID)) && chatIdStr.equals(
                    annotations.get(ANNOTATION_CHAT_ID));
            })
            .next()
            .subscribeOn(Schedulers.boundedElastic())
            .blockOptional();
    }

    private void deleteAttachment(String attachmentName) {
        try {
            extensionClient.fetch(run.halo.app.core.extension.attachment.Attachment.class, attachmentName)
                .flatMap(extensionClient::delete)
                .subscribeOn(Schedulers.boundedElastic())
                .block();
            PluginLogger.debug(log, "删除附件成功: {}", attachmentName);
        } catch (Exception e) {
            PluginLogger.warn(log, "删除附件失败，忽略: {}", attachmentName, e);
        }
    }

    private Moment buildMoment(String html,
        List<String> tags,
        List<Moment.MomentMedia> medium,
        List<String> attachNames,
        long messageId,
        long chatId,
        Instant releaseTime) {
        Moment moment = new Moment();

        Metadata metadata = new Metadata();
        metadata.setGenerateName("telegram-moment-");
        Map<String, String> annotations = new HashMap<>();
        annotations.put(ANNOTATION_MESSAGE_ID, String.valueOf(messageId));
        annotations.put(ANNOTATION_CHAT_ID, String.valueOf(chatId));
        try {
            annotations.put(ANNOTATION_ATTACHMENT_NAMES, objectMapper.writeValueAsString(attachNames));
        } catch (JsonProcessingException e) {
            annotations.put(ANNOTATION_ATTACHMENT_NAMES, "[]");
        }
        metadata.setAnnotations(annotations);
        moment.setMetadata(metadata);

        Moment.MomentSpec spec = new Moment.MomentSpec();
        spec.setOwner("");
        spec.setReleaseTime(releaseTime);
        spec.setTags(new HashSet<>(tags));
        spec.setVisible(Moment.MomentVisible.PUBLIC);

        Moment.MomentContent content = new Moment.MomentContent();
        content.setRaw(html);
        content.setHtml(html);
        content.setMedium(new ArrayList<>(medium));
        spec.setContent(content);

        moment.setSpec(spec);
        return moment;
    }
}
