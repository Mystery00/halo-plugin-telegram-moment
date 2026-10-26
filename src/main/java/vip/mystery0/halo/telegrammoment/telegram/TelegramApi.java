package vip.mystery0.halo.telegrammoment.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.LoopResources;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;

public class TelegramApi implements AutoCloseable {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final WebClient http;
    private final String base;
    private final String token;
    private final Runnable cleanup;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TelegramApi(TelegramSetting settings) {
        base = EndpointPolicy.endpoint(settings.getApiEndpoint()); token = settings.getBotToken();
        var resolver = new PublicAddressResolverGroup(TrustedEndpointAddresses.parse(settings.getTrustedEndpointAddresses()));
        var loops = LoopResources.create("telegram-moment-http", 1, true);
        // 独立 NIO 事件循环与解析器匹配；不继承系统代理，不跟随重定向，不复用旧连接。
        var transport = HttpClient.newConnection().runOn(loops, false).resolver(resolver)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000).followRedirect(false)
            .responseTimeout(Duration.ofSeconds(60));
        http = WebClient.builder().clientConnector(new ReactorClientHttpConnector(transport)).build();
        cleanup = () -> { resolver.close(); loops.dispose(); };
    }

    /** 仅供同包协议测试注入连接器，不提供运行时绕过安全策略的配置。 */
    TelegramApi(TelegramSetting settings, WebClient transport) {
        base = settings.getApiEndpoint().replaceAll("/+$", ""); token = settings.getBotToken();
        http = transport; cleanup = () -> { };
    }

    public JsonNode getMe() { return call("getMe", Map.of()); }
    public JsonNode updates(long offset) {
        JsonNode updates = call("getUpdates", Map.of("offset", offset, "timeout", 20, "limit", 100,
            "allowed_updates", List.of("message", "edited_message", "channel_post", "edited_channel_post")));
        if (!updates.isArray()) throw new TelegramApiException(502, 0);
        return updates;
    }
    public Download download(String fileId) {
        JsonNode info = call("getFile", Map.of("file_id", fileId));
        String path = info.path("file_path").asText();
        if (!path.matches("[A-Za-z0-9_./-]+") || path.contains("..") || path.startsWith("/")
            || info.path("file_size").asLong() > MAX_FILE_BYTES) throw new TelegramApiException(413, 0);
        byte[] bytes = send(HttpMethod.GET, base + "/file/bot" + token + "/" + path, null, (int) MAX_FILE_BYTES, 60).body();
        try {
            Path temp = Files.createTempFile("telegram-moment-", ".media");
            try { Files.write(temp, bytes); }
            catch (IOException e) { Files.deleteIfExists(temp); throw e; }
            return new Download(temp, path.substring(path.lastIndexOf('/') + 1));
        } catch (IOException e) { throw new TelegramApiException(0, 0); }
    }
    public long reply(long chat, long message, String text) {
        return call("sendMessage", Map.of("chat_id", chat, "text", text, "disable_notification", true,
            "reply_parameters", Map.of("message_id", message))).path("message_id").asLong();
    }
    public void deleteReply(long chat, long message) { call("deleteMessage", Map.of("chat_id", chat, "message_id", message)); }

    private JsonNode call(String method, Map<String, Object> payload) {
        try {
            var response = send(HttpMethod.POST, base + "/bot" + token + "/" + method,
                json.writeValueAsBytes(payload), 4 * 1024 * 1024, 30);
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.path("ok").asBoolean() || response.code() != 200) {
                throw new TelegramApiException(body == null ? 502 : body.path("error_code").asInt(response.code()),
                    body == null ? 0 : body.path("parameters").path("retry_after").asInt());
            }
            return body.path("result");
        } catch (TelegramApiException e) { throw e; }
        catch (Exception e) { throw TelegramApiException.transportFailure(e); }
    }

    private Response send(HttpMethod method, String url, byte[] payload, int limit, int seconds) {
        if (closed) throw new TelegramApiException(0, 0);
        var request = http.method(method).uri(URI.create(url));
        var body = payload == null ? request : request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
        var future = body.exchangeToMono(response -> {
            int code = response.statusCode().value();
            if (code >= 300 && code < 400 || method == HttpMethod.GET && code != 200)
                return response.releaseBody().then(Mono.<Response>error(new TelegramApiException(code, 0)));
            return DataBufferUtils.join(response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), limit)
                .map(buffer -> {
                    try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return new Response(code, bytes); }
                    finally { DataBufferUtils.release(buffer); }
                }).defaultIfEmpty(new Response(code, new byte[0]));
        }).timeout(Duration.ofSeconds(seconds)).toFuture();
        pending.add(future);
        try {
            if (closed) future.cancel(true);
            return future.get(seconds + 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw TelegramApiException.transportFailure(e); }
        catch (Exception e) {
            Throwable cause = e;
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
                if (cause instanceof TelegramApiException apiError) throw apiError;
                if (cause instanceof org.springframework.core.io.buffer.DataBufferLimitException) throw new TelegramApiException(413, 0);
            }
            throw TelegramApiException.transportFailure(e);
        } finally { pending.remove(future); if (!future.isDone()) future.cancel(true); }
    }

    @Override public void close() {
        closed = true; pending.forEach(future -> future.cancel(true)); cleanup.run();
    }
    private record Response(int code, byte[] body) { }
    public record Download(Path path, String filename) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}
