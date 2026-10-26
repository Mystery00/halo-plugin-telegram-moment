package vip.mystery0.halo.telegrammoment.telegram;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import static org.assertj.core.api.Assertions.*;

class TelegramApiTest {
    @Test void transportDiagnosisUsesSafeCategoriesInsteadOfRawExceptionMessages() {
        assertThat(TelegramApiException.transportFailure(new java.net.http.HttpTimeoutException("123:secret")).getMessage())
            .contains("timed out").doesNotContain("secret");
        assertThat(TelegramApiException.transportFailure(new java.util.concurrent.ExecutionException(new java.net.UnknownHostException("123:secret"))).getMessage())
            .contains("DNS").doesNotContain("secret");
        assertThat(TelegramApiException.transportFailure(new javax.net.ssl.SSLException("123:secret")).getMessage())
            .contains("TLS handshake").doesNotContain("secret");
    }
    @Test void sameEndpointHandlesPollingAndFiles() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/bot123:secret/", exchange -> {
            String method = exchange.getRequestURI().getPath();
            String response = method.endsWith("getFile")
                ? "{\"ok\":true,\"result\":{\"file_path\":\"photos/test.jpg\"}}"
                : "{\"ok\":true,\"result\":[]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/proxy/file/bot123:secret/photos/test.jpg", exchange -> {
            byte[] bytes = {1, 2, 3}; exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var setting = new TelegramSetting(); setting.setBotToken("123:secret");
        setting.setApiEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/proxy");
        try (var api = new TelegramApi(setting)) {
            assertThat(api.updates(7).isArray()).isTrue();
            try (var file = api.download("id")) { assertThat(Files.readAllBytes(file.path())).containsExactly(1, 2, 3); }
        } finally { server.stop(0); }
    }

    @Test void rateLimitCarriesRetryDelayButNeverRemoteSecret() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = "{\"ok\":false,\"error_code\":429,\"description\":\"123:secret\",\"parameters\":{\"retry_after\":12}}".getBytes();
            exchange.sendResponseHeaders(429, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var setting = new TelegramSetting(); setting.setBotToken("123:secret");
        setting.setApiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        try (var api = new TelegramApi(setting)) {
            assertThatThrownBy(api::getMe).isInstanceOfSatisfying(TelegramApiException.class, e -> {
                assertThat(e.retryAfter()).isEqualTo(12);
                assertThat(e.getMessage()).doesNotContain("secret");
            });
        } finally { server.stop(0); }
    }
}
