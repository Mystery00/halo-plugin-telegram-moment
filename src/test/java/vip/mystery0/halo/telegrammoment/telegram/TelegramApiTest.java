package vip.mystery0.halo.telegrammoment.telegram;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import vip.mystery0.halo.telegrammoment.config.TelegramSetting;
import static org.assertj.core.api.Assertions.*;

class TelegramApiTest {
    @Test void oversizedResponseIsBounded() {
        var settings = new TelegramSetting(); settings.setApiEndpoint("https://example.com"); settings.setBotToken("123:secret");
        var client = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
            var buffers = reactor.core.publisher.Flux.range(0, 6).map(i ->
                new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(new byte[1024 * 1024]));
            return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                .create(org.springframework.http.HttpStatus.OK).body(buffers.map(b -> (org.springframework.core.io.buffer.DataBuffer) b)).build());
        }).build();
        try (var api = new TelegramApi(settings, client)) {
            assertThatThrownBy(api::getMe).isInstanceOfSatisfying(TelegramApiException.class, error -> assertThat(error.code()).isEqualTo(413));
        }
    }

    @Test void closingCancelsPendingRequestPromptly() throws Exception {
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        var settings = new TelegramSetting(); settings.setApiEndpoint("https://example.com"); settings.setBotToken("123:secret");
        var client = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request ->
            reactor.core.publisher.Mono.<org.springframework.web.reactive.function.client.ClientResponse>never()
                .doOnSubscribe(s -> subscribed.countDown()).doOnCancel(cancelled::countDown)).build();
        try (var api = new TelegramApi(settings, client)) {
            var call = java.util.concurrent.CompletableFuture.runAsync(api::getMe);
            assertThat(subscribed.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            api.close();
            assertThat(cancelled.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> call.get(3, java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(TelegramApiException.class);
        }
    }

    @Test void redirectNeverSendsTokenToDestination() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var destinationCalls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/bot123:secret/getMe", exchange -> {
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/destination", exchange -> { destinationCalls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        var settings = new TelegramSetting(); settings.setBotToken("123:secret");
        settings.setApiEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        try (var api = testApi(settings)) {
            assertThatThrownBy(api::getMe).isInstanceOfSatisfying(TelegramApiException.class, error -> assertThat(error.code()).isEqualTo(302));
            assertThat(destinationCalls).hasValue(0);
        } finally { server.stop(0); }
    }
    private TelegramApi testApi(TelegramSetting settings) {
        var client = reactor.netty.http.client.HttpClient.create().followRedirect(false);
        return new TelegramApi(settings, org.springframework.web.reactive.function.client.WebClient.builder()
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(client)).build());
    }

    @Test void productionTransportRejectsHttpAndPrivateHttps() {
        var settings = new TelegramSetting(); settings.setBotToken("123:secret");
        settings.setApiEndpoint("http://127.0.0.1");
        assertThatThrownBy(() -> new TelegramApi(settings)).isInstanceOf(IllegalArgumentException.class);
        settings.setApiEndpoint("https://127.0.0.1");
        try (var api = new TelegramApi(settings)) {
            assertThatThrownBy(api::getMe).isInstanceOf(TelegramApiException.class).hasMessageNotContaining("secret");
        }
    }
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
        try (var api = testApi(setting)) {
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
        try (var api = testApi(setting)) {
            assertThatThrownBy(api::getMe).isInstanceOfSatisfying(TelegramApiException.class, e -> {
                assertThat(e.retryAfter()).isEqualTo(12);
                assertThat(e.getMessage()).doesNotContain("secret");
            });
        } finally { server.stop(0); }
    }
}
