package vip.mystery0.halo.telegrammoment.bot;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import vip.mystery0.halo.telegrammoment.LogBuffer;
import static org.mockito.Mockito.*;

class BotControllerTest {
    @Test void retryBindsTaskNameFromHttpPath() {
        var bot = mock(TelegramBotService.class);
        WebTestClient.bindToController(new BotController(bot, new LogBuffer())).build()
            .post().uri("/apis/console.api.telegram-moment.halo.run/v1alpha1/tasks/tm-test/retry")
            .exchange().expectStatus().is2xxSuccessful();
        verify(bot).retry("tm-test");
    }
}
