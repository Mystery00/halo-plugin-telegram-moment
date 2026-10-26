package vip.mystery0.halo.telegrammoment.telegram;

import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

/** DNS 全部结果通过后直接交给连接层，不再次按主机名解析。 */
final class PublicAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
    private final AddressResolverGroup<InetSocketAddress> delegate;

    PublicAddressResolverGroup() {
        this(new DnsAddressResolverGroup(new DnsNameResolverBuilder()
            .channelType(NioDatagramChannel.class).socketChannelType(NioSocketChannel.class)
            .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED).completeOncePreferredResolved(false)
            .queryTimeoutMillis(5000).ttl(0, 60).negativeTtl(0)));
    }

    PublicAddressResolverGroup(AddressResolverGroup<InetSocketAddress> delegate) { this.delegate = delegate; }

    @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        var resolver = delegate.getResolver(executor);
        return new AddressResolver<>() {
            public boolean isSupported(SocketAddress address) { return address instanceof InetSocketAddress; }
            // 即使是已解析的字面 IP 也需要走策略检查。
            public boolean isResolved(SocketAddress address) { return false; }
            public Future<InetSocketAddress> resolve(SocketAddress address) { return resolve(address, executor.newPromise()); }
            public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
                var all = resolveAll(address);
                all.addListener(done -> {
                    if (done.isSuccess()) promise.trySuccess(all.getNow().getFirst());
                    else promise.tryFailure(done.cause());
                });
                return promise;
            }
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) { return resolveAll(address, executor.newPromise()); }
            public Future<List<InetSocketAddress>> resolveAll(SocketAddress address, Promise<List<InetSocketAddress>> promise) {
                var all = resolver.resolveAll(address);
                all.addListener(done -> {
                    if (!done.isSuccess()) { promise.tryFailure(done.cause()); return; }
                    try {
                        var values = all.getNow();
                        if (values.isEmpty()) throw new java.net.UnknownHostException();
                        for (var value : values) {
                            if (value.isUnresolved()) throw new java.net.UnknownHostException();
                            EndpointPolicy.requirePublic(value.getAddress());
                        }
                        promise.trySuccess(List.copyOf(values));
                    } catch (Exception error) { promise.tryFailure(error); }
                });
                promise.addListener(done -> { if (done.isCancelled()) all.cancel(false); });
                return promise;
            }
            public void close() { resolver.close(); }
        };
    }
    @Override public void close() { super.close(); delegate.close(); }
}
