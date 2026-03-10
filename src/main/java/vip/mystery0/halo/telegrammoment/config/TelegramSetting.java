package vip.mystery0.halo.telegrammoment.config;

import lombok.Data;

@Data
public class TelegramSetting {

    // --- bot group ---
    private String botToken = "";
    private String apiEndpoint = "";

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
            return java.util.List.of();
        }
        return java.util.Arrays.stream(channelFilter.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
