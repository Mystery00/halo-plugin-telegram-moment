package vip.mystery0.halo.telegrammoment.publisher;

import java.util.List;

/**
 * build() 的返回结果
 */
public record ContentResult(String html, List<String> tags) {
}
