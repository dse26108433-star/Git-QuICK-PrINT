package edu.campus.print.payment;

import edu.campus.print.domain.PrintOrder;

import java.util.Optional;

/**
 * No real money. The app shows a "Pay (demo)" button and the order is marked
 * paid straight away. Only for testing: set PAYMENT_MODE=razorpay for real use.
 */
public class DemoGateway implements PaymentGateway {

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public Checkout start(PrintOrder order, String centerName) {
        return new Checkout("demo", "", "", order.getAmountPaise(), order.getCurrency(),
                "DEMO - no real money is taken");
    }

    @Override
    public boolean confirm(PrintOrder order, String paymentId, String signature) {
        return false;   // demo payments use the separate demo endpoint
    }

    @Override
    public Optional<String> findPayment(PrintOrder order) {
        return Optional.empty();
    }
}
