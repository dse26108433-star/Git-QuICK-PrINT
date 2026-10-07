package edu.campus.print.payment;

import edu.campus.print.common.ApiException;
import edu.campus.print.common.Secrets;
import edu.campus.print.domain.PrintOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * XeoGo Pay: the Xerox center's own UPI payment gateway (PAYMENT_MODE=upi).
 * No payment company in between and no fees: students pay the center's UPI
 * ID with GPay, PhonePe, Paytm, BHIM or any bank's UPI app.
 *
 *  1. start()  fixes the amount for this order: the price plus a few paise
 *              that no other open payment uses, so the payment can be told
 *              apart from every other one. Gives the upi://pay link (Android
 *              opens the list of UPI apps on the phone; laptops show it as a QR code).
 *  2. The student pays in their UPI app and comes back to the website or app.
 *  3. The XeoGo Pay Verifier app on the Xerox center's phone forwards the
 *     bank's "money received" SMS / the UPI app's notification
 *     (UpiAlertController); its amount or reference number proves which order
 *     was paid, and that order is paid at once: the student's screen moves
 *     on by itself. Nothing prints before that proof.
 *     Fallbacks: the student may type the UPI reference number
 *     (OrderService.claimPayment), and staff can confirm at the counter.
 *
 * The rules are in db/setup.sql (upi_* functions); UpiLedger calls them.
 */
public class UpiGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(UpiGateway.class);

    private final UpiPayee payee;
    private final UpiLedger ledger;
    private final int windowMinutes;
    private final boolean alertsOn;

    /** A Verifier phone silent for longer than this: payments are checked at the counter instead. */
    public static final int VERIFIER_ALIVE_MINUTES = 180;

    public UpiGateway(UpiPayee payee, UpiLedger ledger, int windowMinutes, boolean alertsOn) {
        this.payee = payee;
        this.ledger = ledger;
        this.windowMinutes = windowMinutes;
        this.alertsOn = alertsOn;
    }

    @Override
    public String name() {
        return "upi";
    }

    public UpiPayee payee() {
        return payee;
    }

    public int windowMinutes() {
        return windowMinutes;
    }

    /** UPI_ALERT_TOKEN is set: a XeoGo Pay Verifier phone may forward the bank's messages. */
    public boolean alertsOn() {
        return alertsOn;
    }

    /** Payments confirm by themselves right now: bank messages are on and a Verifier phone was heard from recently. */
    public boolean autoConfirm() {
        return alertsOn && ledger.verifierAlive(VERIFIER_ALIVE_MINUTES);
    }

    @Override
    public boolean savesCheckout() {
        return true;
    }

    @Override
    public Checkout start(PrintOrder order, String centerName) {
        UpiLedger.Started s = null;
        for (int attempt = 0; attempt < 3 && s == null; attempt++) {
            String reference = "CP" + order.getPickupCode() + Secrets.newPickupCode();
            try {
                s = ledger.start(order.getId(), reference, windowMinutes).orElseThrow(() -> ApiException.conflict(
                        "NOT_AWAITING_PAYMENT", "This order cannot be paid now."));
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.debug("Payment reference {} taken, trying another", reference);   // practically never
            }
        }
        if (s == null) {
            throw ApiException.conflict("PAYMENT_UNAVAILABLE", "Payment could not be started. Try again in a moment.");
        }
        order.setAmountPaise(s.amountPaise());
        order.setGatewayOrderId(s.reference());
        order.setPaymentProvider("upi");

        String payeeName = payee.displayName(centerName);
        String note = centerName + " " + order.getPickupCode();
        // A merchant payment needs a fresh transaction reference for every try (another app, a retry).
        String tr = s.reference() + Secrets.newPickupCode().substring(0, 3);
        String uri = payee.uri(payeeName, s.amountPaise(), note, tr);
        UpiCheckout upi = new UpiCheckout(uri, payee.vpa(), payeeName, UpiPayee.clean(note, 40), s.reference(),
                s.amountPaise(), UpiPayee.amountText(s.amountPaise()), s.tagPaise(), payee.merchant(), autoConfirm(),
                s.startedAt());
        return new Checkout("upi", "", s.reference(), s.amountPaise(), order.getCurrency(),
                centerName + " - print " + order.getPickupCode(), upi);
    }

    /** UPI apps do not sign their answer: what the phone says is never proof. See OrderService.claimPayment. */
    @Override
    public boolean confirm(PrintOrder order, String paymentId, String signature) {
        return false;
    }

    /** A bank message that arrived before the student typed the reference number may prove the payment. */
    @Override
    public Optional<String> findPayment(PrintOrder order) {
        if (order.getPaymentClaimRef() == null) return Optional.empty();
        Optional<String> paid = ledger.matchOrder(order.getId());
        paid.ifPresent(id -> log.info("XeoGo Pay: order {} paid, reference {} found in a bank message",
                order.getPickupCode(), id));
        return paid;
    }
}
