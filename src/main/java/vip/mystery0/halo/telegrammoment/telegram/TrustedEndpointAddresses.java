package vip.mystery0.halo.telegrammoment.telegram;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 管理员显式授权的主机与 IP 对；解析配置时绝不发起 DNS 查询。 */
public final class TrustedEndpointAddresses {
    private final Map<String, Set<InetAddress>> rules;

    private TrustedEndpointAddresses(Map<String, Set<InetAddress>> rules) {
        this.rules = rules;
    }

    public static TrustedEndpointAddresses parse(String value) {
        var rules = new HashMap<String, Set<InetAddress>>();
        if (value == null || value.isBlank()) return new TrustedEndpointAddresses(Map.of());
        if (value.length() > 8192) throw invalid();
        var entries = value.split("[,\\r\\n]+");
        if (entries.length > 32) throw invalid();
        for (String entry : entries) {
            var pair = entry.trim().split("=", -1);
            if (pair.length != 2) throw invalid();
            String host = pair[0].trim().toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) throw invalid();
            for (String label : host.split("\\.", -1)) {
                if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) throw invalid();
            }
            String literal = pair[1].trim();
            // 不接受带作用域、括号或缩写的地址，更不接受主机名和网段。
            if (!NetUtil.isValidIpV4Address(literal) && !NetUtil.isValidIpV6Address(literal)) throw invalid();
            if (literal.contains("%") || literal.contains("[") || literal.contains("]")) throw invalid();
            try {
                var address = InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
                if (!isPrivateProxyAddress(address)) {
                    try { EndpointPolicy.requirePublic(address); }
                    catch (IllegalArgumentException rejected) { throw invalid(); }
                }
                rules.computeIfAbsent(host, ignored -> new HashSet<>()).add(address);
            } catch (UnknownHostException invalidAddress) { throw invalid(); }
        }
        var immutable = new HashMap<String, Set<InetAddress>>();
        rules.forEach((host, addresses) -> immutable.put(host, Set.copyOf(addresses)));
        return new TrustedEndpointAddresses(Map.copyOf(immutable));
    }

    public void requireAllowed(String host, InetAddress address) {
        if (rules.getOrDefault(host.toLowerCase(Locale.ROOT), Set.of()).contains(address)) return;
        EndpointPolicy.requirePublic(address);
    }

    private static boolean isPrivateProxyAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 16) return (first & 254) == 252;
        return first == 10 || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168) || (first == 100 && second >= 64 && second <= 127);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid trusted endpoint addresses: use up to 32 exact host=IP rules; only public, private or CGNAT addresses are supported");
    }
}
