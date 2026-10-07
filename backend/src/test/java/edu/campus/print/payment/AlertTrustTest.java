package edu.campus.print.payment;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which "money received" messages may pay an order by themselves: only those
 * nobody but the bank or the payment company can have written.
 */
class AlertTrustTest {

    private static AlertTrust.Verdict check(String source, String sender) {
        return AlertTrust.check(source, sender, Set.of(), Set.of());
    }

    @Test
    void aBanksSenderNameIsReadWithoutTheOperatorsPrefixAndSuffix() {
        assertThat(AlertTrust.senderKey("AX-SBIUPI")).isEqualTo("SBIUPI");
        assertThat(AlertTrust.senderKey("jd-sbiupi-s")).isEqualTo("SBIUPI");
        assertThat(AlertTrust.senderKey(" VM-HDFCBK ")).isEqualTo("HDFCBK");
        assertThat(AlertTrust.senderKey("HDFCBK")).isEqualTo("HDFCBK");
        assertThat(AlertTrust.senderKey("BZ-PAYTMB-T")).isEqualTo("PAYTMB");
    }

    @Test
    void aPhoneNumberIsNeverASenderName() {
        for (String from : new String[] {"+919876543210", "9876543210", "919876543210", "98765 43210", "+91-98765-43210",
                "56767", "", "  ", null, "AB", "A1", "12AB34567890123", "AX-", "evil sender", "SBIUPI;drop", "५६७६७"}) {
            assertThat(AlertTrust.senderKey(from)).as(String.valueOf(from)).isNull();
        }
    }

    @Test
    void anSmsCountsOnlyFromAKnownBank() {
        assertThat(check("sms", "AX-SBIUPI").trusted()).isTrue();
        assertThat(check("sms", "VM-HDFCBK-S").trusted()).isTrue();
        assertThat(check(null, "AD-ICICIB").trusted()).isTrue();                    // a forwarder that names no source
        // From a phone: anyone can type "Rs 20.01 credited".
        AlertTrust.Verdict phone = check("sms", "+919876543210");
        assertThat(phone.trusted()).isFalse();
        assertThat(phone.senderKey()).isNull();
        assertThat(phone.note()).contains("phone number");
        // A sender name, but a ledger or shopping app's, or a look-alike of a bank's.
        for (String from : new String[] {"VM-KHTABK", "AX-OKCRDT", "JD-AMAZON", "AX-SBIUPl", "AX-SBIUP1", "XSBIUPI"}) {
            AlertTrust.Verdict v = check("sms", from);
            assertThat(v.trusted()).as(from).isFalse();
            assertThat(v.senderKey()).as(from).isNotNull();                         // staff could confirm it, if it is their bank
        }
        assertThat(check("sms", null).trusted()).isFalse();
    }

    @Test
    void theShopsOwnBankCanBeAdded() {
        Set<String> extra = AlertTrust.senderKeys("svcbnk, AX-TJSBBK ; +919876543210, 12");
        assertThat(extra).containsExactly("SVCBNK", "TJSBBK");                      // never a phone number
        assertThat(AlertTrust.check("sms", "JD-SVCBNK-S", extra, Set.of()).trusted()).isTrue();
        assertThat(AlertTrust.check("sms", "JD-OTHERB", extra, Set.of()).trusted()).isFalse();
    }

    @Test
    void aNotificationCountsOnlyFromABusinessUpiApp() {
        for (String app : new String[] {"com.phonepe.app.business", "com.paytm.business",
                "com.google.android.apps.nbu.paisa.merchant", "com.bharatpe.app"}) {
            assertThat(check("notification:" + app, "Payment received").trusted()).as(app).isTrue();
        }
        // Apps with chat: a stranger's message is a notification too.
        for (String app : new String[] {"com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "in.org.npci.upiapp", "com.whatsapp", "org.telegram.messenger", "com.phonepe.app.business.evil", ""}) {
            AlertTrust.Verdict v = check("notification:" + app, "AX-SBIUPI");       // a bank-like title changes nothing
            assertThat(v.trusted()).as(app).isFalse();
            assertThat(v.senderKey()).as(app).isNull();
        }
        assertThat(check("notification", "x").trusted()).isFalse();
        assertThat(AlertTrust.check("notification:com.example.shopupi", "x", Set.of(), AlertTrust.apps("com.example.shopupi"))
                .trusted()).isTrue();
    }

    @Test
    void whatStaffPasteAtTheCounterCounts() {
        assertThat(check("counter", "counter").trusted()).isTrue();
    }
}
