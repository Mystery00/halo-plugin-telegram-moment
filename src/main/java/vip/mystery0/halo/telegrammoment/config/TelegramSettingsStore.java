package vip.mystery0.halo.telegrammoment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/** 仅在启动时迁移旧凭据；状态查询保持只读。异常不携带凭据或底层响应。 */
public final class TelegramSettingsStore {
    private static final String CONFIG = "telegram-moment-configmap";
    private static final String MIGRATED = "telegram-moment-bot-token";
    private static final String OWNER = "telegram-moment.halo.run/managed";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final ReactiveExtensionClient client;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public TelegramSettingsStore(ReactiveExtensionClient client) { this.client = client; }

    public TelegramSetting load(boolean migrate) {
        String stage = "read configuration";
        try {
            var config = client.fetch(ConfigMap.class, CONFIG).block(TIMEOUT);
            var settings = TelegramSetting.from(config);
            if (config == null || config.getData() == null) return settings;
            var bot = (ObjectNode) json.readTree(config.getData().getOrDefault("bot", "{}"));
            String reference = settings.getBotTokenSecretName();
            if (reference != null && !reference.isBlank()) {
                stage = "read referenced Secret";
                settings.setBotToken(readToken(client.fetch(Secret.class, reference).block(TIMEOUT)));
                if (migrate && bot.has("botToken")) saveReference(config, bot, reference);
                return settings;
            }
            String legacy = bot.path("botToken").asText("");
            if (!migrate || legacy.isBlank()) return settings;
            stage = "read migration Secret";
            var existing = client.fetch(Secret.class, MIGRATED).block(TIMEOUT);
            if (existing == null) {
                stage = "create migration Secret";
                var secret = new Secret();
                var metadata = new Metadata(); metadata.setName(MIGRATED); metadata.setLabels(Map.of(OWNER, "true"));
                secret.setMetadata(metadata); secret.setType(Secret.SECRET_TYPE_OPAQUE);
                secret.setStringData(Map.of("token", legacy));
                client.create(secret).block(TIMEOUT);
            } else if (existing.getMetadata() == null || existing.getMetadata().getLabels() == null
                || !"true".equals(existing.getMetadata().getLabels().get(OWNER)) || !legacy.equals(readToken(existing))) {
                throw new IllegalArgumentException("Secret migration name is already in use");
            }
            // 创建成功后重新读取，确认持久化内容再删除旧字段；更新保留版本用于并发检查。
            stage = "verify migration Secret";
            String verified = readToken(client.fetch(Secret.class, MIGRATED).block(TIMEOUT));
            if (!legacy.equals(verified)) throw new IllegalArgumentException("Secret migration verification failed");
            stage = "save Secret reference";
            saveReference(config, bot, MIGRATED);
            settings.setBotTokenSecretName(MIGRATED); settings.setBotToken(verified);
            return settings;
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot load or migrate Bot Secret at stage: " + stage
                + " (" + error.getClass().getSimpleName() + "); check Secret key token and configuration permissions");
        }
    }

    private String readToken(Secret secret) {
        String value = null;
        if (secret != null && secret.getStringData() != null) value = secret.getStringData().get("token");
        if (value == null && secret != null && secret.getData() != null && secret.getData().get("token") != null)
            value = new String(secret.getData().get("token"), StandardCharsets.UTF_8);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Secret key token is missing");
        return value;
    }

    private void saveReference(ConfigMap original, ObjectNode bot, String name) throws Exception {
        var copy = json.readValue(json.writeValueAsBytes(original), ConfigMap.class);
        var groups = new LinkedHashMap<>(original.getData());
        bot.remove("botToken"); bot.put("botTokenSecretName", name);
        groups.put("bot", json.writeValueAsString(bot)); copy.setData(groups);
        if (client.update(copy).block(TIMEOUT) == null) throw new IllegalStateException("Configuration update failed");
    }
}
