package vip.mystery0.halo.telegrammoment.telegram;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TrustedEndpointAddressesTest {
    @Test void permissionIsBoundToExactHostAndAddress() throws Exception {
        var policy = TrustedEndpointAddresses.parse("Proxy.Example=100.64.0.7\nproxy.example=fd00::7,other.example=10.0.0.2");
        policy.requireAllowed("proxy.example", InetAddress.getByName("100.64.0.7"));
        policy.requireAllowed("PROXY.EXAMPLE", InetAddress.getByName("fd00::7"));
        policy.requireAllowed("other.example", InetAddress.getByName("10.0.0.2"));
        assertThatThrownBy(() -> policy.requireAllowed("other.example", InetAddress.getByName("100.64.0.7")))
            .isInstanceOf(EndpointAddressRejectedException.class);
        assertThatThrownBy(() -> policy.requireAllowed("proxy.example", InetAddress.getByName("100.64.0.8")))
            .isInstanceOf(EndpointAddressRejectedException.class);
        policy.requireAllowed("unlisted.example", InetAddress.getByName("149.154.167.220"));
    }

    @Test void defaultsDenyPrivateAddressesAndMalformedRulesNeverBecomePermissions() throws Exception {
        assertThatThrownBy(() -> TrustedEndpointAddresses.parse("").requireAllowed("proxy.example", InetAddress.getByName("100.64.0.7")))
            .isInstanceOf(EndpointAddressRejectedException.class);
        for (String rule : new String[]{"*.example=100.64.0.7", "https://proxy.example=100.64.0.7", "proxy.example=100.64.0.0/10",
            "proxy.example=localhost", "proxy.example=127.0.0.1", "proxy.example=169.254.169.254", "proxy.example=::1",
            "proxy.example=fe80::1", "proxy.example=224.0.0.1", "proxy.example=198.18.0.1", "proxy.example=0.0.0.0",
            "proxy.example=::ffff:127.0.0.1", "proxy.example=10.0.0.1=extra"}) {
            assertThatThrownBy(() -> TrustedEndpointAddresses.parse(rule)).as(rule).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectedAddressProducesSafeActionableDiagnosis() throws Exception {
        try {
            TrustedEndpointAddresses.parse("").requireAllowed("proxy.example", InetAddress.getByName("100.64.0.7"));
            fail("Expected address rejection");
        } catch (EndpointAddressRejectedException rejected) {
            var error = TelegramApiException.transportFailure(new RuntimeException("bot123:secret", rejected));
            assertThat(error.getMessage()).contains("blocked by endpoint address policy", "100.64.0.7", "trusted endpoint")
                .doesNotContain("secret");
            assertThat(error.getCause()).isNull();
        }
    }
}
