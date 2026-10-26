package vip.mystery0.halo.telegrammoment.telegram;

import java.net.InetAddress;
import java.net.URI;

/** 默认拒绝非公网及协议转换地址，校验结果必须直接用于连接。 */
public final class EndpointPolicy {
    private EndpointPolicy() { }

    public static String endpoint(String value) {
        String endpoint = value == null || value.isBlank() ? "https://api.telegram.org" : value.trim();
        try {
            var uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !uri.getRawPath().matches("(/[A-Za-z0-9_-]+)*/?")) throw new IllegalArgumentException();
            return endpoint.replaceAll("/+$", "");
        } catch (Exception ignored) {
            throw new IllegalArgumentException("API endpoint must be public HTTPS on port 443 with a simple path prefix");
        }
    }

    public static void requirePublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean allowed;
        if (bytes.length == 4) {
            long ip = 0; for (byte value : bytes) ip = (ip << 8) | (value & 255);
            allowed = !range(ip, 0x00000000L, 8) && !range(ip, 0x0a000000L, 8)
                && !range(ip, 0x64400000L, 10) && !range(ip, 0x7f000000L, 8)
                && !range(ip, 0xa9fe0000L, 16) && !range(ip, 0xac100000L, 12)
                && !range(ip, 0xc0000000L, 24) && !range(ip, 0xc0000200L, 24)
                && !range(ip, 0xc0586300L, 24) && !range(ip, 0xc0a80000L, 16)
                && !range(ip, 0xc6120000L, 15) && !range(ip, 0xc6336400L, 24)
                && !range(ip, 0xcb007100L, 24) && !range(ip, 0xe0000000L, 3);
        } else {
            int first = (bytes[0] & 255) << 8 | (bytes[1] & 255);
            // 只接受全球单播；排除 IETF 特殊用途、文档、6to4 等转换前缀。
            allowed = (bytes[0] & 0xe0) == 0x20
                && !(first == 0x2001 && (bytes[2] & 254) == 0)
                && !(first == 0x2001 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                && first != 0x2002 && !(first == 0x3fff && (bytes[2] & 0xf0) == 0);
        }
        if (!allowed) throw new IllegalArgumentException("API endpoint resolved to a non-public address");
    }

    private static boolean range(long address, long network, int bits) {
        long mask = (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return (address & mask) == network;
    }
}
