package edu.campus.print.payment;

import edu.campus.print.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks CampusPay (upi), Razorpay or Demo from PAYMENT_MODE in backend/.env. */
@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties props, UpiLedger ledger) {
        if (props.isUpi()) {
            UpiPayee payee = UpiPayee.from(props.upiId(), props.upiName(), props.upiMerchantCode());
            log.info("Payments: CampusPay - direct UPI to {} ({}){}", payee.vpa(),
                    payee.merchant() ? "business UPI ID, merchant code " + payee.merchantCode() : "personal UPI ID",
                    props.upiAlertsOn() ? ", bank messages from the CampusPay Verifier phone confirm payments by themselves"
                            : ", staff confirm payments at the counter (set UPI_ALERT_TOKEN and install the CampusPay "
                              + "Verifier app to confirm them by themselves)");
            if (!payee.merchant()) {
                log.warn("CampusPay: a personal UPI ID is set. Some UPI apps refuse payment links to personal UPI IDs "
                        + "(scanning the QR code still works). A free business UPI ID works best: docs/campuspay-upi.md.");
            }
            return new UpiGateway(payee, ledger, props.matchMinutes(), props.upiAlertsOn());
        }
        if (props.isRazorpay()) {
            log.info("Payments: Razorpay (key {})", props.razorpayKeyId());
            return new RazorpayGateway(props.razorpayKeyId(), props.razorpayKeySecret());
        }
        log.warn("==============================================================");
        log.warn(" Payments are in DEMO mode: orders print WITHOUT real payment.");
        log.warn(" Set PAYMENT_MODE=upi (CampusPay) or razorpay in backend/.env before real use.");
        log.warn("==============================================================");
        return new DemoGateway();
    }
}
