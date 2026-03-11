package vip.mystery0.halo.telegrammoment.config;

import lombok.Data;
import java.util.Arrays;
import java.util.List;

@Data
public class TelegramSetting {

    // --- bot group ---
    private String botToken = "";
    private String apiEndpoint = "";
    private boolean debugMode = false;

    // --- channel group ---
    private boolean channelEnabled = true;
    private String channelId = "";
    private String channelFilter = "";

    // --- private group ---
    private boolean privateEnabled = false;
    private String privateSenderId = "";

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
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
