package edu.campus.print.payment;

import edu.campus.print.domain.PrintOrder;

import java.util.Optional;

/** How the student pays. Razorpay for real money, Demo while testing. */
public interface PaymentGateway {

    /** "razorpay" or "demo". Sent to the apps so they know which button to show. */
    String name();

    /** What the app needs to open the payment screen. */
    record Checkout(String provider, String keyId, String gatewayOrderId,
                    int amountPaise, String currency, String description) {
    }

    /**
     * Prepares payment for this order. For Razorpay this creates a Razorpay
     * order the first time and stores its id on the order (caller saves it).
     */
    Checkout start(PrintOrder order, String centerName);

    /**
     * Checks what the app reported after paying. True only if the payment is
     * genuine, for this order, for the full amount, and captured.
     */
    boolean confirm(PrintOrder order, String paymentId, String signature);

    /**
     * Asks the gateway directly whether this order was paid (in case the
     * student closed the app right after paying). The payment id, if paid.
     */
    Optional<String> findPayment(PrintOrder order);
}
