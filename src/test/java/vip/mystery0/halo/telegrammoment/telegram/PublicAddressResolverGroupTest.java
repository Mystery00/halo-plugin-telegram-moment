package vip.mystery0.halo.telegrammoment.telegram;

import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicAddressResolverGroupTest {
    @Test void mixedDnsResultsFailAndPublicResultsPassThroughWithoutResolvingAgain() throws Exception {
        var executor = new DefaultEventExecutor();
        @SuppressWarnings("unchecked") var delegate = (AddressResolver<InetSocketAddress>) mock(AddressResolver.class);
        var source = new AddressResolverGroup<InetSocketAddress>() {
            protected AddressResolver<InetSocketAddress> newResolver(io.netty.util.concurrent.EventExecutor ignored) { return delegate; }
        };
        try (var group = new PublicAddressResolverGroup(source)) {
            var resolver = group.getResolver(executor);
            var target = InetSocketAddress.createUnresolved("proxy.example", 443);
            var publicIp = new InetSocketAddress("149.154.167.220", 443);
            when(delegate.resolveAll(target)).thenReturn(executor.newSucceededFuture(List.of(publicIp, new InetSocketAddress("127.0.0.1", 443))));
            assertThatThrownBy(() -> resolver.resolve(target).get()).hasCauseInstanceOf(IllegalArgumentException.class);
            when(delegate.resolveAll(target)).thenReturn(executor.newSucceededFuture(List.of(publicIp)));
            assertThat(resolver.resolve(target).get()).isSameAs(publicIp);
            assertThat(resolver.isResolved(new InetSocketAddress("127.0.0.1", 443))).isFalse();
            verify(delegate, times(2)).resolveAll(target);
        } finally { executor.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync(); }
    }
}
