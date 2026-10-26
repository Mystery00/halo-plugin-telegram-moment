package vip.mystery0.halo.telegrammoment.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;

public class TelegramApi implements AutoCloseable {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final String token;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TelegramApi(TelegramSetting settings) { base = settings.endpoint(); token = settings.getBotToken(); }
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
        byte[] bytes = send(HttpRequest.newBuilder(URI.create(base + "/file/bot" + token + "/" + path))
            .timeout(Duration.ofSeconds(60)).GET().build(), MAX_FILE_BYTES, 60).body();
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
            var request = HttpRequest.newBuilder(URI.create(base + "/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            var response = send(request, 4 * 1024 * 1024, 30);
            JsonNode body = json.readTree(response.body());
            if (body == null || !body.path("ok").asBoolean()) {
                throw new TelegramApiException(body == null ? 502 : body.path("error_code").asInt(response.statusCode()),
                    body == null ? 0 : body.path("parameters").path("retry_after").asInt());
            }
            return body.path("result");
        } catch (TelegramApiException e) { throw e; }
        catch (Exception e) { throw TelegramApiException.transportFailure(e); }
    }

    private HttpResponse<byte[]> send(HttpRequest request, long limit, int seconds) {
        if (closed) throw new TelegramApiException(0, 0);
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, ignored -> new LimitedBody(limit));
        pending.add(future);
        try {
            if (closed) future.cancel(true);
            var result = future.get(seconds + 2L, TimeUnit.SECONDS);
            if (request.method().equals("GET") && result.statusCode() != 200)
                throw new TelegramApiException(result.statusCode(), 0);
            return result;
        } catch (TelegramApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw TelegramApiException.transportFailure(e); }
        catch (Exception e) { throw TelegramApiException.transportFailure(e); }
        finally { pending.remove(future); if (!future.isDone()) future.cancel(true); }
    }

    @Override public void close() {
        closed = true;
        pending.forEach(future -> future.cancel(true));
        http.shutdownNow();
    }

    /** 在响应累计超过上限时取消订阅，避免无界读取媒体或错误响应。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private Flow.Subscription subscription;
        private boolean done;
        LimitedBody(long limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > limit) { done = true; subscription.cancel(); delegate.onError(new IOException("Response exceeds size limit")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!done) { done = true; delegate.onError(new IOException("Response failed")); } }
        public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
    public record Download(Path path, String filename) implements AutoCloseable {
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}
