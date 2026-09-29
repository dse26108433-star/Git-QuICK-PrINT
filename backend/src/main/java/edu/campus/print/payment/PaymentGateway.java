package edu.campus.print.payment;

import edu.campus.print.domain.PrintOrder;

import java.time.Instant;
import java.util.Optional;

/**
 * How the student pays: CampusPay (direct UPI to the Xerox center, "upi"),
 * Razorpay, or Demo while testing.
 */
public interface PaymentGateway {

    /** "upi", "razorpay" or "demo". Sent to the apps so they know which payment screen to show. */
    String name();

    /** What the app needs to open the payment screen. upi: only for CampusPay. */
    record Checkout(String provider, String keyId, String gatewayOrderId,
                    int amountPaise, String currency, String description, UpiCheckout upi) {

        public Checkout(String provider, String keyId, String gatewayOrderId,
                        int amountPaise, String currency, String description) {
            this(provider, keyId, gatewayOrderId, amountPaise, currency, description, null);
        }
    }

    /**
     * CampusPay: pay this amount to this UPI ID with any UPI app.
     *
     * uri        the upi://pay link: opens the UPI app chooser on Android, and is the QR code
     * tagPaise   the few paise added to the price so the bank's message points to this order
     * merchant   the UPI ID is a business one (payment links work in every UPI app)
     * autoConfirm the Xerox center's phone forwards bank messages, so payments confirm by themselves
     */
    record UpiCheckout(String uri, String payeeVpa, String payeeName, String note, String reference,
                       int amountPaise, String amountText, int tagPaise, boolean merchant, boolean autoConfirm,
                       Instant startedAt) {}

    /**
     * Prepares payment for this order. For Razorpay this creates a Razorpay
     * order the first time and stores its id on the order (caller saves it).
     */
    Checkout start(PrintOrder order, String centerName);

    /** True when start() already saved the payment's id and amount on the order row itself. */
    default boolean savesCheckout() {
        return false;
    }

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
