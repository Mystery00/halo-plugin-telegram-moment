package vip.mystery0.halo.telegrammoment.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import vip.mystery0.halo.telegrammoment.handler.MediaGroupAggregator;
import vip.mystery0.halo.telegrammoment.handler.MessageHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBotService {

    private static final String CONFIG_MAP_NAME = "telegram-moment-configmap";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ReactiveExtensionClient extensionClient;
    private final MessageHandler messageHandler;
    private final MediaGroupAggregator mediaGroupAggregator;

    private TelegramBotsLongPollingApplication botApplication;
    private OkHttpTelegramClient telegramClient;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return running.get();
    }

    public String getBotUsername() {
        if (telegramClient == null) return null;
        try {
            return telegramClient.execute(GetMe.builder().build()).getUserName();
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void startBot() {
        TelegramSetting setting = readSetting();
        if (!setting.hasValidToken()) {
            log.warn("Bot Token 未配置，跳过 Bot 启动。请在配置页面设置 Bot Token 后点击「重启 Bot」。");
            return;
        }

        log.info("正在启动 Telegram Bot...");

        String apiEndpoint = setting.getApiEndpoint();
        boolean hasCustomEndpoint = apiEndpoint != null && !apiEndpoint.isBlank();

        if (hasCustomEndpoint) {
            // 解析自定义 API 端点，构造 TelegramUrl
            TelegramUrl telegramUrl = buildTelegramUrl(apiEndpoint);
            telegramClient = new OkHttpTelegramClient(
                    new okhttp3.OkHttpClient(),
                    setting.getBotToken(),
                    telegramUrl
            );
        } else {
            telegramClient = new OkHttpTelegramClient(
                    new okhttp3.OkHttpClient(),
                    setting.getBotToken()
            );
        }

        TelegramUpdateHandler handler = new TelegramUpdateHandler(
                messageHandler, setting, telegramClient);

        botApplication = new TelegramBotsLongPollingApplication();
        try {
            botApplication.registerBot(setting.getBotToken(), handler);
            mediaGroupAggregator.start(setting, telegramClient);
            running.set(true);
            log.info("Telegram Bot 启动成功");
        } catch (Exception e) {
            running.set(false);
            log.error("Telegram Bot 启动失败", e);
        }
    }

    public synchronized void stopBot() {
        running.set(false);
        mediaGroupAggregator.stop();
        if (botApplication != null) {
            try {
                botApplication.close();
            } catch (Exception e) {
                log.warn("关闭 Bot Application 时出现异常（通常可忽略）", e);
            }
            botApplication = null;
        }
        telegramClient = null;
        log.info("Telegram Bot 已停止");
    }

    public void restartBot() {
        log.info("正在重启 Telegram Bot...");
        stopBot();
        startBot();
    }

    /**
     * 将自定义 API 端点字符串解析为 TelegramUrl 对象。
     * 例如 "https://my-proxy.example.com" 解析为 schema=https, host=my-proxy.example.com, port=443
     */
    private TelegramUrl buildTelegramUrl(String apiEndpoint) {
        try {
            URI uri = URI.create(apiEndpoint);
            String schema = uri.getScheme() != null ? uri.getScheme() : "https";
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(schema) ? 443 : 80;
            }
            return TelegramUrl.builder()
                    .schema(schema)
                    .host(host)
                    .port(port)
                    .build();
        } catch (Exception e) {
            log.warn("解析自定义 API 端点失败，将使用默认端点: {}", apiEndpoint, e);
            return TelegramUrl.DEFAULT_URL;
        }
    }

    private TelegramSetting readSetting() {
        try {
            ConfigMap configMap = extensionClient.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
            if (configMap == null || configMap.getData() == null) {
                log.warn("插件配置 ConfigMap 不存在或为空: {}", CONFIG_MAP_NAME);
                return new TelegramSetting();
            }

            TelegramSetting setting = new TelegramSetting();
            Map<String, String> data = configMap.getData();

            parseGroup(data.get("bot"), setting, "bot");
            parseGroup(data.get("channel"), setting, "channel");
            parseGroup(data.get("private"), setting, "private");
            parseGroup(data.get("storage"), setting, "storage");

            return setting;
        } catch (Exception e) {
            log.error("读取插件配置失败", e);
            return new TelegramSetting();
        }
    }

    private void parseGroup(String json, TelegramSetting setting, String group) {
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            switch (group) {
                case "bot" -> {
                    setting.setBotToken(str(map, "botToken"));
                    setting.setApiEndpoint(str(map, "apiEndpoint"));
                }
                case "channel" -> {
                    setting.setChannelEnabled(bool(map, "channelEnabled", true));
                    setting.setChannelId(str(map, "channelId"));
                    setting.setChannelFilter(str(map, "channelFilter"));
                }
                case "private" -> {
                    setting.setPrivateEnabled(bool(map, "privateEnabled", false));
                    setting.setPrivateSenderId(str(map, "privateSenderId"));
                }
                case "storage" -> {
                    setting.setStoragePolicy(str(map, "storagePolicy"));
                    setting.setStorageGroup(str(map, "storageGroup"));
                    Object delay = map.get("mediaDelaySeconds");
                    if (delay != null) {
                        setting.setMediaDelaySeconds(Integer.parseInt(delay.toString()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析配置 group={} 失败: {}", group, json, e);
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v.toString());
    }
}
