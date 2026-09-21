package interview.executor;

public record RefundRequest(
        String paymentId,
        long amountMinor,
        String currency) {
}
