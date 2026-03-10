package vip.mystery0.halo.telegrammoment.publisher;

import lombok.Data;
import java.util.List;

/** build() 的返回结果 */
@Data
public class ContentResult {
    private final String html;
    private final List<String> tags;
}
