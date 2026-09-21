package interview.executor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports how often the demo provider was called.
 *
 * <pre>
 * GET /actuator/demo-stripe  -&gt;  {"available":true,"attempts":0,"refunds":0}
 * </pre>
 *
 * <p>Use it to prove that accepting a refund operation does not call the
 * provider. When the application supplies its own {@link Stripe} bean, the demo
 * provider is not registered and this endpoint reports {@code available:false}.
 */
@Endpoint(id = "demo-stripe")
public class DemoStripeEndpoint {
    private final ObjectProvider<DemoStripe> provider;

    public DemoStripeEndpoint(ObjectProvider<DemoStripe> provider) {
        this.provider = provider;
    }

    @ReadOperation
    public Map<String, Object> calls() {
        DemoStripe stripe = provider.getIfAvailable();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", stripe != null);
        result.put("attempts", stripe == null ? 0 : stripe.attempts());
        result.put("refunds", stripe == null ? 0 : stripe.refunds());
        return result;
    }
}
