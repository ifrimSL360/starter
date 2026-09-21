package interview.autoconfigure;

import interview.executor.DemoStripe;
import interview.executor.DemoStripeEndpoint;
import interview.executor.Stripe;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Registers the demo provider and the application clock only when the
 * application does not supply its own.
 *
 * <p>This lives in an auto-configuration on purpose. Auto-configuration runs
 * after the application's own beans are registered, which is the only place
 * where {@code @ConditionalOnMissingBean} behaves reliably. Declare your own
 * {@link Stripe} bean and the demo provider backs off.
 */
@AutoConfiguration
public class ProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(Stripe.class)
    DemoStripe demoStripe() {
        return new DemoStripe();
    }

    @Bean
    DemoStripeEndpoint demoStripeEndpoint(ObjectProvider<DemoStripe> demoStripe) {
        return new DemoStripeEndpoint(demoStripe);
    }
}
