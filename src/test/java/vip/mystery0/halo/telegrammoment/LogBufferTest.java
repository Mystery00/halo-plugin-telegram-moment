package vip.mystery0.halo.telegrammoment;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LogBufferTest {
    @Test void tokensAreRedactedBeforeLogsLeaveProcess() {
        var buffer = new LogBuffer(); buffer.setEnabled(true);
        buffer.add("ERROR", "test", "failed https://host/bot123456:secret_VALUE/getMe");
        assertThat(buffer.getLogs().getFirst().message()).doesNotContain("secret_VALUE");
    }
}
