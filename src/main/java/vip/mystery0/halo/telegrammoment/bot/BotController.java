package vip.mystery0.halo.telegrammoment.bot;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import vip.mystery0.halo.telegrammoment.LogBuffer;

@RestController
@RequestMapping("/apis/console.api.telegram-moment.halo.run/v1alpha1")
@RequiredArgsConstructor
public class BotController {
    private final TelegramBotService bot;
    private final LogBuffer logs;
    @GetMapping("/status") public Mono<TelegramBotService.Status> status() {
        return Mono.fromCallable(bot::status).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/restart") public Mono<TelegramBotService.Status> restart() {
        return Mono.fromCallable(() -> { bot.restartBot(); return bot.status(); }).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/stop") public Mono<TelegramBotService.Status> stop() {
        return Mono.fromCallable(() -> { bot.stopBot(); return bot.status(); }).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/tasks") public Mono<List<TelegramBotService.TaskView>> tasks() {
        return Mono.fromCallable(bot::tasks).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/tasks/{name}/retry") public Mono<Void> retry(@PathVariable("name") String name) {
        return Mono.<Void>fromRunnable(() -> bot.retry(name)).subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(IllegalArgumentException.class, e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()));
    }
    @GetMapping("/logs") public List<LogBuffer.LogEntry> logs() { return logs.getLogs(); }
    @DeleteMapping("/logs") public void clearLogs() { logs.clear(); }
}
