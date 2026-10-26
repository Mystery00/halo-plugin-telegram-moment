package vip.mystery0.halo.telegrammoment.publisher;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.extension.*;

@Component
@RequiredArgsConstructor
public class HaloMomentGateway implements MomentGateway {
    public static final GroupVersionKind GVK = new GroupVersionKind("moment.halo.run", "v1alpha1", "Moment");
    private final ReactiveExtensionClient client;
    private final SchemeManager schemes;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public Unstructured get(String name) { return client.fetch(GVK, name).block(TIMEOUT); }
    public Unstructured findLegacy(long chat, long message) {
        return client.listAllNames(schemes.get(GVK).type(), new ListOptions(), Sort.unsorted())
            .concatMap(name -> client.fetch(GVK, name)).filter(value -> {
                var annotations = value.getMetadata().getAnnotations();
                return annotations != null && !annotations.containsKey(SyncPublisher.PREFIX + "bot-id")
                    && Long.toString(chat).equals(annotations.get("chatId"))
                    && Long.toString(message).equals(annotations.get("messageId"));
            }).next().block(TIMEOUT);
    }
    public Unstructured save(Unstructured moment) {
        return (moment.getMetadata().getVersion() == null ? client.create(moment) : client.update(moment)).block(TIMEOUT);
    }
    public void delete(Unstructured moment) {
        if (moment.getMetadata().getDeletionTimestamp() == null) client.delete(moment).block(TIMEOUT);
    }
}
