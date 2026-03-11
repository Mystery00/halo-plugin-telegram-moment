package vip.mystery0.halo.telegrammoment.bot;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/apis/telegram-moment/v1alpha1/bot")
@RequiredArgsConstructor
public class BotController {

    private final TelegramBotService botService;

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(() -> Map.<String, Object>of(
            "running", botService.isRunning(),
            "username", botService.getBotUsername() != null ? botService.getBotUsername() : ""
        )).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/restart")
    public Mono<Map<String, String>> restart() {
        return Mono.fromRunnable(botService::restartBot)
            .subscribeOn(Schedulers.boundedElastic())
            .thenReturn(Map.of("message", "Bot 正在重启，请稍后刷新状态"));
    }
}
