package edu.campus.print.payment;

import edu.campus.print.common.ApiException;
import edu.campus.print.config.PaymentProperties;
import edu.campus.print.domain.PrintOrder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A forgotten or misspelt PAYMENT_MODE must never mean "print without paying". */
class PaymentModeTest {

    private static PaymentGateway gateway(String mode) {
        PaymentProperties props = new PaymentProperties(mode, "", "", "xeroxshop@okaxis", "Xerox", "", "", 60, "", "");
        return new PaymentConfig().paymentGateway(props, new UpiLedger(null, props));
    }

    @Test
    void demoHasToBeAskedForByName() {
        assertThat(gateway("demo")).isInstanceOf(DemoGateway.class);
        assertThat(gateway(" Demo ")).isInstanceOf(DemoGateway.class);
    }

    @Test
    void noModeOrAnUnknownOneSwitchesPayingOff() {
        for (String mode : new String[] {null, "", "  ", "live", "upi2", "true"}) {
            PaymentGateway g = gateway(mode);
            assertThat(g).as(String.valueOf(mode)).isInstanceOf(ClosedGateway.class);
            assertThat(g.name()).isEqualTo("closed");
            assertThat(g.confirm(PrintOrder.create(), "pay_1", "sig")).isFalse();
            assertThatThrownBy(() -> g.start(PrintOrder.create(), "Xerox")).isInstanceOf(ApiException.class)
                    .hasMessageContaining("not set up");
        }
    }

    @Test
    void theRealModesAreUnchanged() {
        assertThat(gateway("upi")).isInstanceOf(UpiGateway.class);
        assertThat(gateway(" UPI ")).isInstanceOf(UpiGateway.class);
    }
}
