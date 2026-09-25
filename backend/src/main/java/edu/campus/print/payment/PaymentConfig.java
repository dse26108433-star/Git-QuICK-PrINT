package edu.campus.print.payment;

import edu.campus.print.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks Razorpay or Demo from PAYMENT_MODE in backend/.env. */
@Configuration
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties props) {
        if (props.isRazorpay()) {
            log.info("Payments: Razorpay (key {})", props.razorpayKeyId());
            return new RazorpayGateway(props.razorpayKeyId(), props.razorpayKeySecret());
        }
        log.warn("==============================================================");
        log.warn(" Payments are in DEMO mode: orders print WITHOUT real payment.");
        log.warn(" Set PAYMENT_MODE=razorpay in backend/.env before real use.");
        log.warn("==============================================================");
        return new DemoGateway();
    }
}
