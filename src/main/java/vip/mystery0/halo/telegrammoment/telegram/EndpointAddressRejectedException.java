package vip.mystery0.halo.telegrammoment.telegram;

import java.net.InetAddress;

/** 只记录已解析的数字地址，避免请求 URL 或凭据进入异常信息。 */
final class EndpointAddressRejectedException extends IllegalArgumentException {
    EndpointAddressRejectedException(InetAddress address) {
        super("Connection blocked by endpoint address policy: " + address.getHostAddress()
            + "; check DNS or configure an exact trusted endpoint host=IP rule for your private proxy");
    }
}
