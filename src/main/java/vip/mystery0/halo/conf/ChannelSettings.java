package vip.mystery0.halo.conf;

import java.util.List;

/**
 * @author Mystery0
 * Create at 2024/9/9
 */
public record ChannelSettings(boolean enabled, List<Item> idList) {
    public static final String GROUP = "channel";

    public record Item(Long id) {
    }
}
