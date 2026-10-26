package vip.mystery0.halo.telegrammoment.publisher;

import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.telegram.TelegramApi;
import vip.mystery0.halo.telegrammoment.sync.SyncRecord;
import vip.mystery0.halo.telegrammoment.sync.SyncRepository;
import vip.mystery0.halo.telegrammoment.sync.Jsons;
import vip.mystery0.halo.telegrammoment.telegram.MessageParser;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import run.halo.app.extension.Unstructured;

@Component
public class SyncPublisher {
    public static final String PREFIX = "telegram-moment.halo.run/";
    private final MomentGateway moments;
    private final MediaUploader media;
    private final SyncRepository repository;
    public SyncPublisher(MomentGateway moments, MediaUploader media, SyncRepository repository) {
        this.moments = moments; this.media = media; this.repository = repository;
    }

    public void publish(SyncRecord record, TelegramSetting settings, TelegramApi api) {
        var spec = record.getSpec();
        if (spec.getAction().equals("PUBLISH") && record.members().stream().anyMatch(m -> MessageParser.blocked(m, settings))) {
            spec.setState("IGNORED"); save(record); cleanup(record); return;
        }
        for (var member : record.members()) {
            if (!member.unsupported().isBlank() && !spec.getAction().equals("DELETE")) throw new IllegalArgumentException(member.unsupported());
        }
        String name = spec.getMomentName().isBlank() ? "telegram-" + record.getMetadata().getName() : spec.getMomentName();
        Unstructured existing = moments.get(name);
        if (existing == null && spec.getMomentName().isBlank()) {
            for (var member : record.members()) {
                existing = moments.findLegacy(spec.getChatId(), member.messageId());
                if (existing != null) { name = existing.getMetadata().getName(); break; }
            }
        }
        spec.setMomentName(name);
        // 找回上传成功但还未来得及写入 staged 的附件，覆盖进程中断窗口。
        addCleanup(record, media.owned(record.getMetadata().getName())); save(record);
        if (spec.getAction().equals("DELETE")) {
            var obsolete = new LinkedHashSet<>(names(readMedia(spec.getMediaJson())));
            obsolete.addAll(names(readMedia(spec.getStagedJson())));
            if (existing != null) {
                var annotations = existing.getMetadata().getAnnotations();
                if (annotations != null) {
                    obsolete.addAll(names(readMedia(annotations.getOrDefault(PREFIX + "media", "{}"))));
                    obsolete.addAll(Jsons.read(annotations.getOrDefault("attachmentNames", "[]"), new TypeReference<List<String>>() { }));
                }
            }
            // 先持久化待清理列表，只有 Moment 删除成功后才清理其附件。
            addCleanup(record, obsolete); save(record);
            if (existing != null) moments.delete(existing);
            spec.setMediaJson("{}"); spec.setStagedJson("{}"); spec.setState("DELETED"); save(record); cleanup(record); return;
        }
        if (existing != null && existing.getMetadata().getDeletionTimestamp() != null)
            throw new IllegalStateException("Moment deletion is still in progress");
        var annotations = existing == null || existing.getMetadata().getAnnotations() == null ? Map.<String,String>of() : existing.getMetadata().getAnnotations();
        if (Long.toString(spec.getRevision()).equals(annotations.get(PREFIX + "revision"))) {
            complete(record, readMedia(annotations.getOrDefault(PREFIX + "media", "{}"))); return;
        }
        Map<String, MediaUploader.UploadedMedia> prepared = new LinkedHashMap<>();
        var current = readMedia(spec.getMediaJson()); var staged = readMedia(spec.getStagedJson());
        // Moment 是已提交事实来源；上次提交后同步记录可能尚未成功保存。
        current.putAll(readMedia(annotations.getOrDefault(PREFIX + "media", "{}")));
        spec.setMediaJson(Jsons.write(current)); save(record);
        // 接管旧版本记录时，先保留旧附件映射，提交新内容后再清理。
        if (current.isEmpty() && annotations.containsKey("attachmentNames")) {
            for (String legacy : Jsons.<List<String>>read(annotations.get("attachmentNames"), new TypeReference<>() { }))
                current.put("legacy:" + legacy, new MediaUploader.UploadedMedia(legacy, "", "", ""));
            spec.setMediaJson(Jsons.write(current)); save(record);
        }
        boolean committed = false;
        try {
            for (var member : record.members()) {
                if (member.fileId().isBlank()) continue;
                String key = mediaKey(member);
                var uploaded = current.get(key);
                if (uploaded == null) uploaded = staged.get(key);
                if (uploaded == null) {
                    uploaded = media.upload(member, record.getMetadata().getName(), settings, api);
                    staged.put(key, uploaded); spec.setStagedJson(Jsons.write(staged)); save(record);
                }
                prepared.put(key, uploaded);
            }
            for (int attempt = 0; ; attempt++) {
                var latest = moments.get(name);
                try { moments.save(build(latest, name, record, settings, prepared)); committed = true; break; }
                catch (OptimisticLockingFailureException | DataIntegrityViolationException e) { if (attempt == 2) throw e; }
            }
            complete(record, prepared);
        } catch (RuntimeException e) {
            // 提交结果未知时先读取提交标记，绝不回收已被 Moment 引用的新文件。
            Unstructured verified;
            try { verified = moments.get(name); } catch (RuntimeException unknown) { throw e; }
            var marker = verified == null ? null : verified.getMetadata().getAnnotations();
            if (committed || (marker != null && Long.toString(spec.getRevision()).equals(marker.get(PREFIX + "revision")))) throw e;
            var referenced = new LinkedHashSet<String>();
            if (marker != null) {
                referenced.addAll(names(readMedia(marker.getOrDefault(PREFIX + "media", "{}"))));
                referenced.addAll(Jsons.read(marker.getOrDefault("attachmentNames", "[]"), new TypeReference<List<String>>() { }));
            }
            var unused = new LinkedHashSet<>(names(staged)); unused.removeAll(referenced);
            unused.addAll(media.owned(record.getMetadata().getName())); unused.removeAll(referenced);
            addCleanup(record, unused); spec.setStagedJson("{}"); save(record); cleanup(record); throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Unstructured build(Unstructured old, String name, SyncRecord record, TelegramSetting settings,
        Map<String, MediaUploader.UploadedMedia> uploaded) {
        Map<String,Object> data = old == null ? new LinkedHashMap<>() : Jsons.MAPPER.convertValue(old, Map.class);
        data.put("apiVersion", "moment.halo.run/v1alpha1"); data.put("kind", "Moment");
        var metadata = (Map<String,Object>) data.computeIfAbsent("metadata", ignored -> new LinkedHashMap<>());
        metadata.put("name", name);
        var annotations = (Map<String,String>) metadata.computeIfAbsent("annotations", ignored -> new LinkedHashMap<>());
        annotations.put(PREFIX + "bot-id", Long.toString(record.getSpec().getBotId()));
        annotations.put(PREFIX + "record", record.getMetadata().getName());
        annotations.put(PREFIX + "revision", Long.toString(record.getSpec().getRevision()));
        annotations.put(PREFIX + "media", Jsons.write(uploaded));
        var first = record.members().getFirst();
        annotations.put("chatId", Long.toString(first.chatId())); annotations.put("messageId", Long.toString(first.messageId()));
        annotations.put("attachmentNames", Jsons.write(names(uploaded)));
        var spec = (Map<String,Object>) data.computeIfAbsent("spec", ignored -> new LinkedHashMap<>());
        var caption = record.members().stream().filter(m -> !m.text().isBlank()).findFirst().orElse(first);
        var content = ContentBuilder.build(caption.text(), caption.entities());
        spec.put("owner", settings.getMomentOwner()); spec.put("visible", "PUBLIC"); spec.put("approved", true);
        spec.putIfAbsent("approvedTime", Instant.now().toString());
        spec.put("releaseTime", Instant.ofEpochSecond(Math.max(0, first.date())).toString());
        spec.put("tags", content.tags());
        var contentMap = (Map<String,Object>) spec.computeIfAbsent("content", ignored -> new LinkedHashMap<>());
        contentMap.put("raw", caption.text()); contentMap.put("html", content.html());
        contentMap.put("medium", record.members().stream().filter(m -> !m.fileId().isBlank())
            .map(m -> uploaded.get(mediaKey(m)))
            .map(m -> Map.of("type", m.type(), "url", m.url(), "originType", m.mimeType())).toList());
        return new Unstructured(data);
    }

    private void complete(SyncRecord record, Map<String, MediaUploader.UploadedMedia> uploaded) {
        var spec = record.getSpec(); var unused = new LinkedHashSet<>(names(readMedia(spec.getMediaJson())));
        unused.addAll(names(readMedia(spec.getStagedJson()))); unused.removeAll(names(uploaded));
        addCleanup(record, unused); spec.setMediaJson(Jsons.write(uploaded)); spec.setStagedJson("{}");
        spec.getCleanup().removeAll(names(uploaded));
        spec.setState("SUCCEEDED"); spec.setError(""); save(record); cleanup(record);
    }
    public void cleanup(SyncRecord record) {
        // 清理重试时再次读取实际引用，记录保存失败不能导致已提交附件被删除。
        var referenced = new LinkedHashSet<String>();
        try {
            var moment = record.getSpec().getMomentName().isBlank() ? null : moments.get(record.getSpec().getMomentName());
            if (moment != null && moment.getMetadata().getDeletionTimestamp() == null) {
                var annotations = moment.getMetadata().getAnnotations();
                if (annotations != null) {
                    referenced.addAll(names(readMedia(annotations.getOrDefault(PREFIX + "media", "{}"))));
                    referenced.addAll(Jsons.read(annotations.getOrDefault("attachmentNames", "[]"), new TypeReference<List<String>>() { }));
                }
            }
        } catch (RuntimeException unavailable) { return; }
        var pending = new ArrayList<>(record.getSpec().getCleanup());
        for (String name : pending) {
            if (referenced.contains(name)) continue;
            try { media.delete(name); record.getSpec().getCleanup().remove(name); save(record); }
            catch (RuntimeException e) { /* 保留待清理名称，下次工作循环继续。 */ }
        }
    }
    private void addCleanup(SyncRecord record, Collection<String> names) {
        var all = new LinkedHashSet<>(record.getSpec().getCleanup()); all.addAll(names); record.getSpec().setCleanup(new ArrayList<>(all));
    }
    private void save(SyncRecord record) { var saved = repository.save(record); record.setMetadata(saved.getMetadata()); }
    private static Map<String, MediaUploader.UploadedMedia> readMedia(String json) { return Jsons.read(json, new TypeReference<>() { }); }
    private static List<String> names(Map<String, MediaUploader.UploadedMedia> media) { return media.values().stream().map(MediaUploader.UploadedMedia::attachmentName).distinct().toList(); }
    private static String mediaKey(vip.mystery0.halo.telegrammoment.telegram.IncomingMessage member) {
        return member.mediaType() + ":" + (member.fileUniqueId().isBlank() ? member.fileId() : member.fileUniqueId());
    }
}
