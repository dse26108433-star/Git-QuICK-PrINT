package edu.campus.print;

import edu.campus.print.payment.BankAlertParser;
import edu.campus.print.payment.BankAlertParser.Kind;
import edu.campus.print.payment.BankAlertParser.Parsed;
import edu.campus.print.payment.UpiPayee;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CampusPay reads the bank's "money received" messages. Each bank words them
 * differently; these are the shapes Indian banks and UPI business apps send.
 * What must never happen: money going OUT, an OTP, or a payment request read
 * as money received.
 */
class BankAlertParserTest {

    private static void credit(String sms, int paise, String... refs) {
        Parsed p = BankAlertParser.parse(sms);
        assertThat(p.kind()).as(sms).isEqualTo(Kind.CREDIT);
        assertThat(p.amountPaise()).as(sms).isEqualTo(paise);
        assertThat(p.refs()).as(sms).containsExactly(refs);
        assertThat(p.isCredit()).isTrue();
    }

    private static void notCredit(String sms) {
        assertThat(BankAlertParser.parse(sms).isCredit()).as(sms).isFalse();
    }

    @Test
    void creditsFromManyBanks() {
        credit("Dear UPI user A/C X1234 credited by Rs.10.03 on 29Sep26 trf from RAHUL KUMAR Refno 627312345678. "
                + "If not u? call 1800111109. -SBI", 1003, "627312345678");
        credit("Money Received - INR 10.03 in HDFC Bank A/c xx1234 on 29-09-26 from VPA rahul@okaxis "
                + "(UPI 627312345678)", 1003, "627312345678");
        credit("Rs. 10.03 credited to a/c *1234 on 29/09/26 by a/c linked to VPA rahul@okicici (UPI Ref No 627312345678).",
                1003, "627312345678");
        credit("Dear Customer, Acct XX123 is credited with Rs 10.03 on 29-Sep-26 from RAHUL KUMAR. "
                + "UPI:627312345678-ICICI Bank.", 1003, "627312345678");
        credit("INR 10.03 credited to A/c no. XX1234 on 29-09-26 at 10:15:30 IST. Info- UPI/P2A/627312345678/RAHUL K/"
                + "Axis Bank. Avl Bal- INR 5,432.10", 1003, "627312345678");
        credit("Received Rs.10.03 in your Kotak Bank a/c XX1234 from rahul@ybl on 29-09-26. UPI Ref:627312345678.",
                1003, "627312345678");
        credit("A/c XX1234 credited INR 10.03 Dt 29-09-26 by UPI Ref 627312345678. Bal INR 1,00,532.50 -PNB",
                1003, "627312345678");
        credit("Your a/c no. XXXXXXXX1234 is credited by Rs.1,250.50 on 29-09-26 by UPI-627312345678 (Ref)",
                125050, "627312345678");
        credit("Rs10 deposited in A/c XX1234 via UPI Ref 627312345678", 1000, "627312345678");
    }

    @Test
    void notificationsFromUpiBusinessApps() {
        credit("₹10.03 received from Rahul Kumar", 1003);
        credit("Received ₹10.03 from RAHUL KUMAR on PhonePe Business", 1003);
        credit("Rahul paid you ₹10.03", 1003);
        credit("Rahul Kumar has sent you ₹10.03", 1003);
        credit("Payment of ₹10.03 received from Rahul Kumar", 1003);
        credit("You received ₹10.03 from rahul@okaxis. UPI Ref No. 627312345678", 1003, "627312345678");
    }

    @Test
    void moneyGoingOutIsNeverACredit() {
        Parsed p = BankAlertParser.parse("Rs.10.03 debited from A/c XX1234 on 29-09-26 and credited to rahul@ybl "
                + "(UPI Ref No 627312345678). Not you? Call 18002586161");
        assertThat(p.kind()).isEqualTo(Kind.DEBIT);
        assertThat(p.isCredit()).isFalse();
        notCredit("You have sent Rs 10.03 to Rahul Kumar. UPI Ref 627312345678");
        notCredit("You paid ₹10.03 to XEROX CENTER via UPI");
        notCredit("Dear Customer, Rs.500 spent on your card XX1234 at AMAZON");
    }

    @Test
    void otpsRequestsAndFailuresAreIgnored() {
        notCredit("123456 is your OTP to receive Rs 10.03. Do not share it with anyone.");
        notCredit("Rahul has requested money Rs 10.03 from you on Google Pay. Pay now.");
        notCredit("UPI collect request of Rs 10.03 from rahul@ybl. Approve only if you know them.");
        notCredit("Your UPI transaction of Rs 10.03 failed. Amount will be credited back in 3 days.");
        notCredit("Hi! Your Swiggy order is on the way.");
        notCredit("");
        notCredit(null);
    }

    @Test
    void theBalanceIsNeverTakenForTheAmountAndIsHidden() {
        Parsed p = BankAlertParser.parse("Avl Bal Rs 5,432.10. A/c XX1234 credited with Rs 10.03 UPI Ref 627312345678");
        assertThat(p.amountPaise()).isEqualTo(1003);
        assertThat(p.maskedText()).doesNotContain("5,432.10").contains("10.03");
        assertThat(BankAlertParser.parse("A/c credited Rs 10.03. Avl Bal: INR 999.00").maskedText())
                .doesNotContain("999.00");
    }

    @Test
    void referencesAreTwelveDigitsOnly() {
        Parsed p = BankAlertParser.parse("Credited Rs 10.03 UPI Ref 627312345678, call +919876543210 or 1800111109");
        assertThat(p.refs()).containsExactly("627312345678");
        assertThat(BankAlertParser.parse("Rs 10 credited, ref 62731234567").refs()).isEmpty();          // 11 digits
        assertThat(BankAlertParser.parse("Rs 10 credited, ref 6273123456789").refs()).isEmpty();        // 13 digits
    }

    @Test
    void wordsThatOnlyLookLikeAmounts() {
        // "users 10" is not "Rs 10"
        notCredit("Dear users 10 new offers received today");
        credit("Dear UPI users, Rs 10.03 received in a/c XX1 UPI Ref 627312345678", 1003, "627312345678");
    }

    // ------------------------------------------------------------------ the payee and the UPI link

    @Test
    void aUpiIdOrTheTextOfTheShopsQrCode() {
        UpiPayee plain = UpiPayee.from("XeroxShop@OKAXIS", "Main Xerox Center", null);
        assertThat(plain.vpa()).isEqualTo("xeroxshop@okaxis");
        assertThat(plain.merchant()).isFalse();

        UpiPayee qr = UpiPayee.from("upi://pay?pa=paytmqr2810050501011abcd@paytm&pn=Main%20Xerox%20Center&mc=7338"
                + "&mode=02&orgid=000000&sign=MEUCIQDabc", null, null);
        assertThat(qr.vpa()).isEqualTo("paytmqr2810050501011abcd@paytm");
        assertThat(qr.name()).isEqualTo("Main Xerox Center");
        assertThat(qr.merchantCode()).isEqualTo("7338");
        assertThat(qr.merchant()).isTrue();

        // what personal UPI QR codes carry is not a merchant code
        assertThat(UpiPayee.from("upi://pay?pa=9876543210@ybl&pn=VEDANT&mc=0000&mode=02&purpose=00", null, null)
                .merchant()).isFalse();

        assertThatThrownBy(() -> UpiPayee.from("", null, null)).hasMessageContaining("UPI_ID");
        assertThatThrownBy(() -> UpiPayee.from("1234567890 SBIN0001234", null, null)).hasMessageContaining("UPI_ID");
    }

    @Test
    void theLinkEveryUpiAppUnderstands() {
        UpiPayee personal = UpiPayee.from("xeroxshop@okaxis", "Main Xerox Center", null);
        assertThat(personal.uri(personal.displayName("x"), 1003, "Main Xerox Center K7M4X", "CPK7M4XAAAAA"))
                .isEqualTo("upi://pay?pa=xeroxshop@okaxis&pn=Main%20Xerox%20Center&tn=Main%20Xerox%20Center%20K7M4X"
                        + "&am=10.03&cu=INR");
        // a business UPI ID also carries its merchant code and a transaction reference
        UpiPayee merchant = UpiPayee.from("shop@ybl", "Shop & Co", "7338");
        assertThat(merchant.uri("Shop & Co", 200, "Print #K7M4X!", "CPK7M4XAAAAA"))
                .isEqualTo("upi://pay?pa=shop@ybl&pn=Shop%20%26%20Co&mc=7338&tr=CPK7M4XAAAAA&tn=Print%20K7M4X&am=2.00&cu=INR");
        assertThat(UpiPayee.amountText(5)).isEqualTo("0.05");
        assertThat(UpiPayee.amountText(125050)).isEqualTo("1250.50");
        assertThat(List.of(personal.displayName("fallback"), UpiPayee.from("a1@ok", null, null).displayName("Campus X")))
                .containsExactly("Main Xerox Center", "Campus X");
    }
}
