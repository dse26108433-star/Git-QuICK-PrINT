package edu.campus.print.payment;

import edu.campus.print.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks XeoGo Pay (upi), Razorpay or Demo from PAYMENT_MODE in backend/.env.
 * No PAYMENT_MODE (or a misspelt one) means no paying at all, never demo.
 */
@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties props, UpiLedger ledger) {
        if (props.isUpi()) {
            UpiPayee payee = UpiPayee.from(props.upiId(), props.upiName(), props.upiMerchantCode());
            log.info("Payments: XeoGo Pay - direct UPI to {} ({}){}", payee.vpa(),
                    payee.merchant() ? "business UPI ID, merchant code " + payee.merchantCode() : "personal UPI ID",
                    props.upiAlertsOn() ? ", bank messages from the XeoGo Pay Verifier phone confirm payments by themselves"
                            : ", staff confirm payments at the counter (set UPI_ALERT_TOKEN and install the XeoGo Pay "
                              + "Verifier app to confirm them by themselves)");
            if (!payee.merchant()) {
                log.warn("XeoGo Pay: a personal UPI ID is set. Some UPI apps refuse payment links to personal UPI IDs "
                        + "(scanning the QR code still works). A free business UPI ID works best: docs/campuspay-upi.md.");
            }
            return new UpiGateway(payee, ledger, props.matchMinutes(), props.upiAlertsOn());
        }
        if (props.isRazorpay()) {
            log.info("Payments: Razorpay (key {})", props.razorpayKeyId());
            return new RazorpayGateway(props.razorpayKeyId(), props.razorpayKeySecret());
        }
        if (props.isDemo()) {
            log.warn("==============================================================");
            log.warn(" Payments are in DEMO mode: orders print WITHOUT real payment.");
            log.warn(" Set PAYMENT_MODE=upi (XeoGo Pay) or razorpay in backend/.env before real use.");
            log.warn("==============================================================");
            return new DemoGateway();
        }
        log.error("==============================================================");
        log.error(" PAYMENT_MODE is \"{}\": students CANNOT PAY, so nothing will print.", props.mode() == null ? "" : props.mode());
        log.error(" Set PAYMENT_MODE=upi (XeoGo Pay) or razorpay in backend/.env (on Render: Environment).");
        log.error(" PAYMENT_MODE=demo only for tests on your own computer: demo prints without payment.");
        log.error("==============================================================");
        return new ClosedGateway();
    }
}
