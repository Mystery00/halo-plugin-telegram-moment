package vip.mystery0.halo.telegrammoment.bot;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import vip.mystery0.halo.telegrammoment.LogBuffer;

@RestController
@RequestMapping("/apis/telegram-moment/v1alpha1/bot")
@RequiredArgsConstructor
public class BotController {

    private final TelegramBotService botService;
    private final LogBuffer logBuffer;

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

    @GetMapping("/logs")
    public Mono<List<LogBuffer.LogEntry>> getLogs() {
        return Mono.just(logBuffer.getLogs());
    }

    @DeleteMapping("/logs")
    public Mono<Void> clearLogs() {
        logBuffer.clear();
        return Mono.empty();
    }
}
