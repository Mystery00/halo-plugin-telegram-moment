package vip.mystery0.halo.bot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
@Slf4j
@Component
public class Bot {
    @PostConstruct
    public void init() {
        log.info("Bot init");
    }
}
