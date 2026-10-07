package edu.campus.print.payment;

import edu.campus.print.common.ApiException;
import edu.campus.print.domain.PrintOrder;
import org.springframework.http.HttpStatus;

import java.util.Optional;

/**
 * PAYMENT_MODE is not set, or is not one of demo / upi / razorpay: paying is
 * switched off. Students can still add files and see the price, but nothing
 * can be paid, so nothing prints. A forgotten setting must never mean free
 * printing (that is what "demo" does, and demo has to be asked for by name).
 */
public class ClosedGateway implements PaymentGateway {

    @Override
    public String name() {
        return "closed";
    }

    @Override
    public Checkout start(PrintOrder order, String centerName) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_SET_UP",
                "Online payment is not set up at this Xerox center yet. Please ask at the counter.");
    }

    @Override
    public boolean confirm(PrintOrder order, String paymentId, String signature) {
        return false;
    }

    @Override
    public Optional<String> findPayment(PrintOrder order) {
        return Optional.empty();
    }
}
