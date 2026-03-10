package vip.mystery0.halo.telegrammoment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import run.halo.app.plugin.PluginContext;
import vip.mystery0.halo.telegrammoment.bot.TelegramBotService;

@ExtendWith(MockitoExtension.class)
class TelegramMomentPluginTest {

    @Mock
    PluginContext context;

    @Mock
    TelegramBotService botService;

    TelegramMomentPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new TelegramMomentPlugin(context);
        ReflectionTestUtils.setField(plugin, "botService", botService);
    }

    @Test
    void contextLoads() {
        plugin.start();
        plugin.stop();
    }
}
