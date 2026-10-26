package vip.mystery0.halo.telegrammoment.telegram;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EndpointPolicyTest {
    @Test void onlyHttpsWithoutCredentialsAndWithStandardPortIsAllowed() {
        assertThat(EndpointPolicy.endpoint("https://proxy.example/api/")).isEqualTo("https://proxy.example/api");
        for (String value : new String[]{"http://api.telegram.org", "https://user:password@example.com", "https://example.com:8443", "https://example.com?token=x", "https://example.com/#x", "https://example.com/a/../b", "https://example.com/%2e%2e"}) {
            assertThatThrownBy(() -> EndpointPolicy.endpoint(value)).as(value).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void deniesNonPublicAddressesIncludingTransitionAndDocumentationRanges() throws Exception {
        for (String ip : new String[]{"0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002:7f00:1::", "64:ff9b::7f00:1", "::ffff:127.0.0.1"}) {
            var address = InetAddress.getByName(ip);
            assertThatThrownBy(() -> EndpointPolicy.requirePublic(address)).as(ip).isInstanceOf(IllegalArgumentException.class);
        }
        EndpointPolicy.requirePublic(InetAddress.getByName("149.154.167.220"));
        EndpointPolicy.requirePublic(InetAddress.getByName("2001:4860:4860::8888"));
    }
}
