package vip.mystery0.halo.telegrammoment.config;

import java.util.Arrays;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import run.halo.app.extension.ConfigMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TelegramSetting {

    // --- bot group ---
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String botToken = "";
    private String botTokenSecretName = "";
    private String apiEndpoint = "";
    private boolean debugMode = false;
    private boolean enableLogBuffer = false;

    // --- channel group ---
    private boolean channelEnabled = true;
    private String channelId = "";
    private String channelFilter = "";
    private boolean channelReplyEnabled = false;
    private int channelReplyDeleteSeconds = 3;

    // --- private group ---
    private boolean privateEnabled = false;
    private String privateSenderId = "";
    private boolean privateReplyEnabled = false;
    private int privateReplyDeleteSeconds = 3;

    // --- moment group ---
    private String momentOwner = "";

    // --- storage group ---
    private String storagePolicy = "";
    private String storageGroup = "";
    private int mediaDelaySeconds = 3;

    public boolean hasValidToken() {
        return botToken != null && !botToken.isBlank();
    }

    /** 将逗号分隔的 channelFilter 解析为 List */
    public java.util.List<String> getChannelFilterList() {
        if (channelFilter == null || channelFilter.isBlank()) {
            return List.of();
        }
        return Arrays.stream(channelFilter.split(","))
                .map(String::trim)
                .map(s -> s.startsWith("#") ? s.substring(1) : s)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    public static TelegramSetting from(ConfigMap config) {
        if (config == null || config.getData() == null) return new TelegramSetting();
        var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var combined = new LinkedHashMap<String, Object>();
        for (String group : List.of("bot", "channel", "private", "moment", "storage")) {
            String value = config.getData().get(group);
            if (value == null || value.isBlank()) continue;
            try { combined.putAll(mapper.readValue(value, Map.class)); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid configuration group: " + group); }
        }
        try { return mapper.convertValue(combined, TelegramSetting.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid configuration field types"); }
    }

    public void validate() {
        if (!hasValidToken()) throw new IllegalArgumentException("Bot token is required");
        if (!botToken.matches("[0-9]+:[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid bot token format");
        if (momentOwner == null || momentOwner.isBlank()) throw new IllegalArgumentException("Moment owner is required");
        if (storagePolicy == null || storagePolicy.isBlank()) throw new IllegalArgumentException("Storage policy is required");
        validateId(channelId, false);
        validateId(privateSenderId, privateEnabled);
        if (privateEnabled && Long.parseLong(privateSenderId.trim()) <= 0)
            throw new IllegalArgumentException("Private sender must be a positive user ID");
        if (mediaDelaySeconds < 1 || mediaDelaySeconds > 60) throw new IllegalArgumentException("Media delay must be 1 to 60 seconds");
        if (channelReplyDeleteSeconds < 0 || channelReplyDeleteSeconds > 3600
            || privateReplyDeleteSeconds < 0 || privateReplyDeleteSeconds > 3600)
            throw new IllegalArgumentException("Reply retention must be 0 to 3600 seconds");
        endpoint();
    }

    private static void validateId(String id, boolean required) {
        if (id == null || id.isBlank()) {
            if (required) throw new IllegalArgumentException("Private sender ID is required when private messages are enabled");
            return;
        }
        try { if (Long.parseLong(id.trim()) == 0) throw new NumberFormatException(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid chat or sender ID"); }
    }

    public String endpoint() {
        return vip.mystery0.halo.telegrammoment.telegram.EndpointPolicy.endpoint(apiEndpoint);
    }

    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(new ObjectMapper().writeValueAsBytes(this));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest((botToken == null ? "" : botToken).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint settings"); }
    }
}
