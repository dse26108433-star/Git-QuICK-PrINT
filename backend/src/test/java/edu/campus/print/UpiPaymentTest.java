package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.support.FakeStorage;
import edu.campus.print.support.Files;
import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * XeoGo Pay, the Xerox center's own UPI payment gateway (PAYMENT_MODE=upi),
 * against a real PostgreSQL: amounts that tell payments apart, bank messages
 * that pay exactly one order, the student's reference number, and staff
 * confirming at the counter. Above all: nothing is paid on anyone's word alone,
 * and nothing is paid by a message somebody other than the bank could have written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UpiPaymentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COUNTER = "counter-password-for-tests";
    private static final String TOKEN = "alert-token-for-tests-0123456789";
    private static String dbUrl;

    @Autowired MockMvc mvc;
    @Autowired FakeStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository orderRepo;

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        FakeStorage fakeStorage() {
            return new FakeStorage();
        }
    }

    @BeforeAll
    static void database() {
        dbUrl = TestDb.freshDatabase();
        TestDb.run(TestDb.dataSource(dbUrl), TestDb.setupSql());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> dbUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("campus.supabase.url", () -> "https://storage.test");
        r.add("campus.shop.shop-cache-millis", () -> "0");
        r.add("campus.supabase.service-key", () -> "test-key");
        r.add("campus.payment.mode", () -> "upi");
        r.add("campus.payment.upi-id", () -> "xeroxshop@okaxis");
        r.add("campus.payment.upi-name", () -> "Main Xerox Center");
        r.add("campus.payment.upi-alert-token", () -> TOKEN);
        r.add("campus.payment.upi-sms-senders", () -> "BANK");     // the test shop's bank texts as "AX-BANK"
        r.add("campus.counter.password", () -> COUNTER);
        r.add("campus.agent.token-secret", () -> "a-test-token-secret-that-is-long-enough-123");
        r.add("campus.cors.allowed-origins", () -> "http://localhost:3000");
    }

    @BeforeEach
    void shop() throws Exception {
        jdbc.update("delete from payment_alerts");
        jdbc.update("delete from payment_verifiers");
        jdbc.update("delete from payment_senders");
        jdbc.update("delete from order_events");
        jdbc.update("delete from order_documents");
        jdbc.update("delete from orders");
        jdbc.update("delete from printers");
        jdbc.update("update shop_settings set price_bw_paise = 200, price_color_paise = 1000");
        if (jdbc.queryForObject("select count(*) from printers", Integer.class) == 0) {
            String pc = counter(post("/api/v1/counter/pcs").content("{\"name\":\"Test PC\"}")).path("agentId").asText();
            ObjectNode caps = JSON.createObjectNode();
            caps.put("schema", 1);
            caps.putArray("paperSizes").addObject().put("id", "A4").putArray("marginsMm").add(4).add(4).add(4).add(4);
            caps.put("duplex", true);
            counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                    "name", "Printer 1", "windowsPrinterName", "Canon BW", "supportsColor", false, "acceptsBw", true,
                    "agentId", pc, "capabilities", caps, "capabilitiesHash", "h1"))));
        }
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void theShopSaysStudentsPayWithUpi() throws Exception {
        jdbc.update("delete from payment_verifiers");
        assertThat(json(mvc.perform(get("/api/v1/shop")).andReturn()).path("paymentMode").asText()).isEqualTo("upi");
        JsonNode s = counter(get("/api/v1/counter/summary"));
        assertThat(s.path("paymentMode").asText()).isEqualTo("upi");
        assertThat(s.path("upi").path("payeeVpa").asText()).isEqualTo("xeroxshop@okaxis");
        assertThat(s.path("upi").path("alertsConfigured").asBoolean()).isTrue();
        // No Verifier phone yet: payments are checked at the counter.
        assertThat(s.path("upi").path("autoConfirm").asBoolean()).isFalse();
        Order o = pricedOrder(1);
        assertThat(o.startPayment().path("upi").path("autoConfirm").asBoolean()).isFalse();
    }

    @Test
    void whileTheVerifierPhoneIsAlivePaymentsConfirmByThemselves() throws Exception {
        jdbc.update("delete from payment_verifiers");
        heartbeat();
        JsonNode s = counter(get("/api/v1/counter/summary"));
        assertThat(s.path("upi").path("autoConfirm").asBoolean()).isTrue();
        assertThat(s.path("upi").path("verifiers").get(0).path("device").asText()).isEqualTo("Shop phone");
        assertThat(s.path("upi").path("verifiers").get(0).path("sms").asBoolean()).isTrue();
        Order o = pricedOrder(1);
        assertThat(o.startPayment().path("upi").path("autoConfirm").asBoolean()).isTrue();
        // Silent for hours (switched off): back to checking at the counter.
        jdbc.update("update payment_verifiers set last_seen_at = now() - interval '5 hours'");
        assertThat(pricedOrder(1).startPayment().path("upi").path("autoConfirm").asBoolean()).isFalse();
        // Every message it forwards also counts as a sign of life.
        sms("Rs 9.99 credited UPI Ref 627300009999", null, "sms", "Shop phone");
        assertThat(counter(get("/api/v1/counter/summary")).path("upi").path("autoConfirm").asBoolean()).isTrue();
    }

    @Test
    void thePhoneTellingOnePaymentTwiceCountsOnce() throws Exception {
        Order a = pricedOrder(2);
        a.startPayment();
        // The UPI business app's notification comes first, without a reference number...
        JsonNode n = sms("₹4.01 received from Rahul Kumar", null, "notification:com.phonepe.app.business", "Shop phone");
        assertThat(n.path("paidOrder").asText()).isEqualTo(a.code);
        assertThat(n.path("matchMethod").asText()).isEqualTo("AMOUNT");
        // Another student starts paying meanwhile: never the amount just paid, so the late SMS cannot pay them.
        Order b = pricedOrder(2);
        assertThat(b.startPayment().path("amountPaise").asInt()).isEqualTo(402);
        // ...then the bank's SMS for a's money: linked, pays nothing more, and a gets the real reference number.
        JsonNode s2 = sms("Rs 4.01 credited to a/c XX1234 UPI Ref 627312345678", null, "sms", "Shop phone");
        assertThat(s2.path("matchMethod").asText()).isEqualTo("SAME_PAYMENT");
        assertThat(s2.path("paidOrder").asText()).isEqualTo(a.code);
        assertThat(jdbc.queryForObject("select gateway_payment_id from orders where id = ?::uuid", String.class, a.id))
                .isEqualTo("627312345678");
        assertThat(b.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // With no order waiting for that amount, the second message is clearly the same payment.
        Order c = pricedOrder(3);
        c.startPayment();                                                    // Rs 6.01
        JsonNode first = sms("Rs 6.01 credited to a/c XX1234 UPI Ref 627355555555", null, "sms", "Shop phone");
        assertThat(first.path("paidOrder").asText()).isEqualTo(c.code);
        JsonNode second = sms("₹6.01 received from Priya", null, "notification:com.phonepe.app.business", "Shop phone");
        assertThat(second.path("matchMethod").asText()).isEqualTo("SAME_PAYMENT");
        assertThat(second.path("paidOrder").asText()).isEqualTo(c.code);
        // The same SMS sent again later (another retry window) is linked too, never a second payment.
        JsonNode again = sms("Rs 6.01 credited to a/c XX1234 UPI Ref 627355555555", "later", "sms", "Shop phone");
        assertThat(again.path("matchMethod").asText()).isEqualTo("SAME_PAYMENT");
    }

    @Test
    void aStudentPaysAndTheBanksMessageConfirmsIt() throws Exception {
        Order o = pricedOrder(2);                                   // 2 pages x Rs 2 = Rs 4
        JsonNode c = o.startPayment();
        assertThat(c.path("provider").asText()).isEqualTo("upi");
        assertThat(c.path("amountPaise").asInt()).isEqualTo(401);   // + 1 paisa: this payment's own amount
        JsonNode upi = c.path("upi");
        assertThat(upi.path("payeeVpa").asText()).isEqualTo("xeroxshop@okaxis");
        assertThat(upi.path("amountText").asText()).isEqualTo("4.01");
        assertThat(upi.path("tagPaise").asInt()).isEqualTo(1);
        assertThat(upi.path("uri").asText())
                .startsWith("upi://pay?pa=xeroxshop@okaxis&pn=Main%20Xerox%20Center&tn=")
                .endsWith("&am=4.01&cu=INR")
                .doesNotContain("&mc=");                            // a personal UPI ID: no merchant fields
        // Opening the payment screen again gives the same amount.
        assertThat(o.startPayment().path("amountPaise").asInt()).isEqualTo(401);
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(o.view().path("payment").path("tagPaise").asInt()).isEqualTo(1);

        JsonNode alert = sms("Dear UPI user A/C X1234 credited by Rs.4.01 on 30Sep26 trf from RAHUL Refno 627312345678 -SBI");
        assertThat(alert.path("kind").asText()).isEqualTo("CREDIT");
        assertThat(alert.path("paidOrder").asText()).isEqualTo(o.code);

        JsonNode v = o.view();
        assertThat(v.path("status").asText()).isEqualTo("QUEUED");
        assertThat(v.path("payment").path("verifiedBy").asText()).isEqualTo("bank-alert");
        assertThat(jdbc.queryForObject("select gateway_payment_id from orders where id = ?::uuid", String.class, o.id))
                .isEqualTo("627312345678");
        assertThat(jdbc.queryForObject("select count(*) from order_documents where order_id = ?::uuid and status = 'QUEUED'",
                Integer.class, o.id)).isEqualTo(1);
    }

    @Test
    void openPaymentsNeverShareAnAmountAndWholeRupeesMatchNothing() throws Exception {
        Order a = pricedOrder(2);
        Order b = pricedOrder(2);
        Order c = pricedOrder(2);
        assertThat(List.of(a.startPayment().path("amountPaise").asInt(), b.startPayment().path("amountPaise").asInt(),
                c.startPayment().path("amountPaise").asInt())).containsExactly(401, 402, 403);

        // Someone paying the counter's QR code by hand: whole rupees, nobody's order.
        assertThat(sms("Rs 4.00 credited to a/c XX1234 UPI Ref 627300000001").path("paidOrder").isNull()).isTrue();
        // B's payment pays B, and only B.
        assertThat(sms("Rs 4.02 credited to a/c XX1234 UPI Ref 627300000002").path("paidOrder").asText()).isEqualTo(b.code);
        assertThat(a.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(b.view().path("status").asText()).isEqualTo("QUEUED");
        assertThat(c.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // B's amount is not given out again for a while: a second message about B's payment may still come.
        assertThat(pricedOrder(2).startPayment().path("amountPaise").asInt()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ the student's word is not proof

    @Test
    void nothingPrintsOnTheStudentsWordAloneUntilStaffConfirm() throws Exception {
        Order o = pricedOrder(3);
        o.startPayment();
        JsonNode claimed = o.claim("6273 1234 5678");
        assertThat(claimed.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(claimed.path("stage").asText()).isEqualTo("Checking your payment");
        assertThat(claimed.path("message").asText()).contains("627312345678");
        assertThat(claimed.path("payment").path("claimRef").asText()).isEqualTo("627312345678");

        JsonNode list = counter(get("/api/v1/counter/payments"));
        assertThat(list.path("toCheck")).hasSize(1);
        assertThat(list.path("toCheck").get(0).path("pickupCode").asText()).isEqualTo(o.code);
        assertThat(list.path("toCheck").get(0).path("paymentClaimRef").asText()).isEqualTo("627312345678");
        assertThat(counter(get("/api/v1/counter/summary")).path("paymentsToCheck").asInt()).isEqualTo(1);

        counter(post("/api/v1/counter/orders/" + o.id + "/payment/approve").content("{}"));
        JsonNode v = o.view();
        assertThat(v.path("status").asText()).isEqualTo("QUEUED");
        assertThat(v.path("payment").path("verifiedBy").asText()).isEqualTo("counter");
        assertThat(counter(get("/api/v1/counter/payments")).path("toCheck")).isEmpty();
        assertThat(counter(get("/api/v1/counter/payments")).path("paid")).hasSize(1);
    }

    @Test
    void staffCanSayTheMoneyIsNotThereAndTheStudentTriesAgain() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();
        o.claim("627312345678");
        counter(post("/api/v1/counter/orders/" + o.id + "/payment/reject").content("{\"reason\":\"not in the bank app\"}"));
        JsonNode v = o.view();
        assertThat(v.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(v.path("stage").asText()).isEqualTo("Payment not found yet");
        assertThat(v.path("message").asText()).contains("could not find your payment").contains("not in the bank app");
        assertThat(v.path("payment").path("claimRef").isNull()).isTrue();
        // The student corrects the number; the bank's message proves it.
        o.claim("627312345679");
        assertThat(o.view().path("stage").asText()).isEqualTo("Checking your payment");
        sms("Rs 2.01 credited to a/c XX1234 UPI Ref 627312345679");
        assertThat(o.view().path("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void aReferenceTypedAfterTheBanksMessageStillPaysTheOrder() throws Exception {
        Order o = pricedOrder(2);
        o.startPayment();
        // The payment screen was opened long ago: the amount alone no longer proves whose payment this is.
        jdbc.update("update orders set payment_started_at = now() - interval '2 hours' where id = ?::uuid", o.id);
        assertThat(sms("Rs 4.01 credited to a/c XX1234 UPI Ref 627312345678").path("paidOrder").isNull()).isTrue();
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // ...but the reference number in the student's UPI app does.
        JsonNode v = o.claim("627312345678");
        assertThat(v.path("status").asText()).isEqualTo("QUEUED");
        assertThat(v.path("payment").path("verifiedBy").asText()).isEqualTo("bank-alert");
        assertThat(jdbc.queryForObject("select match_method from payment_alerts", String.class)).isEqualTo("REFERENCE");
    }

    @Test
    void oneUpiPaymentPaysOneOrderNeverTwo() throws Exception {
        Order a = pricedOrder(1);
        a.startPayment();
        a.claim("627312345678");
        sms("Rs 2.01 credited to a/c XX1234 UPI Ref 627312345678");
        assertThat(a.view().path("status").asText()).isEqualTo("QUEUED");

        Order b = pricedOrder(1);
        b.startPayment();
        MvcResult r = b.claimRaw("627312345678");
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(r).path("error").asText()).isEqualTo("REFERENCE_USED");
        // Staff cannot use it twice either.
        MvcResult s = mvc.perform(post("/api/v1/counter/orders/" + b.id + "/payment/approve")
                .header("X-Counter-Password", COUNTER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"627312345678\"}")).andReturn();
        assertThat(s.getResponse().getStatus()).isEqualTo(409);
        // The same SMS forwarded again pays nothing more.
        JsonNode again = sms("Rs 2.01 credited to a/c XX1234 UPI Ref 627312345678", "1790000000000");
        JsonNode again2 = sms("Rs 2.01 credited to a/c XX1234 UPI Ref 627312345678", "1790000000000");
        assertThat(again2.path("duplicate").asBoolean()).isTrue();
        assertThat(again.path("paidOrder").isNull() || again.path("paidOrder").asText().equals(a.code)).isTrue();
        assertThat(b.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void aWronglyTypedReferenceIsRefused() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();
        MvcResult r = o.claimRaw("12345");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("error").asText()).isEqualTo("BAD_REFERENCE");
        // No reference at all is fine: staff or the bank's message decide.
        assertThat(o.claim(null).path("stage").asText()).isEqualTo("Checking your payment");
    }

    @Test
    void theOrderCannotChangeOnceThePaymentScreenOpened() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();
        MvcResult r = mvc.perform(key(post("/api/v1/orders/" + o.id + "/edit"), o)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(key(post("/api/v1/orders/" + o.id + "/payment/demo"), o)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ the bank connection

    @Test
    void onlyTheXeroxCentersPhoneCanSendBankMessages() throws Exception {
        String body = "{\"from\":\"AX-SBIUPI\",\"text\":\"Rs 2.01 credited UPI Ref 627312345678\"}";
        assertThat(mvc.perform(post("/api/v1/payments/upi/alerts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/payments/upi/alerts").header("X-Alert-Token", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/payments/upi/alerts").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/v1/payments/upi/alerts?token=" + TOKEN)
                .contentType(MediaType.TEXT_PLAIN).content("Rs 2.02 credited UPI Ref 627312345670"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void otpsMoneyGoingOutAndOtherSmsAreNeverKept() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();
        assertThat(sms("123456 is your OTP to receive Rs 2.01. Do not share it.").path("stored").asBoolean()).isFalse();
        assertThat(sms("Rs 2.01 debited from A/c XX1234 to xeroxshop@okaxis UPI Ref 627312345678").path("kind").asText())
                .isEqualTo("DEBIT");
        assertThat(sms("Your Swiggy order is on the way").path("stored").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from payment_alerts", Integer.class)).isZero();
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void staffCanPasteABankMessageByHand() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();
        JsonNode r = counter(post("/api/v1/counter/payments/alerts")
                .content("{\"text\":\"Money Received - INR 2.01 in HDFC Bank A/c xx1234 from VPA rahul@okaxis (UPI 627312345678)\"}"));
        assertThat(r.path("paidOrder").asText()).isEqualTo(o.code);
        JsonNode list = counter(get("/api/v1/counter/payments"));
        assertThat(list.path("alerts").get(0).path("pickupCode").asText()).isEqualTo(o.code);
        assertThat(list.path("alerts").get(0).path("matchMethod").asText()).isEqualTo("AMOUNT");
        assertThat(list.path("today").path("paidOrders").asInt()).isEqualTo(1);
    }

    @Test
    void aClaimedPaymentIsNotExpiredWhileStaffMayStillFindTheMoney() throws Exception {
        Order claimed = pricedOrder(1);
        claimed.startPayment();
        claimed.claim(null);
        Order forgotten = pricedOrder(1);
        forgotten.startPayment();
        jdbc.update("update orders set created_at = now() - interval '2 days'");
        orderRepo.expireUnpaid();
        assertThat(claimed.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(forgotten.view().path("status").asText()).isEqualTo("EXPIRED");
        // A payment that arrives after all is still found by staff.
        counter(post("/api/v1/counter/orders/" + claimed.id + "/payment/approve").content("{}"));
        assertThat(claimed.view().path("status").asText()).isEqualTo("QUEUED");
    }

    // ------------------------------------------------------------------ messages anyone could have written

    @Test
    void aTextFromAPhoneNumberNeverPaysAnOrder() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();                                                    // Rs 2.01
        // A student texts the shop's phone the words a bank would use.
        for (String from : List.of("+919876543210", "9876543210", "98765 43210", "")) {
            JsonNode r = from(from, "sms", "Dear customer, your a/c XX1234 is credited by Rs.2.01 on 06Oct26. UPI Ref "
                    + "62731234" + (1000 + from.length()));
            assertThat(r.path("stored").asBoolean()).isTrue();
            assertThat(r.path("trusted").asBoolean()).as("from '" + from + "'").isFalse();
            assertThat(r.path("paidOrder").isNull()).isTrue();
        }
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // Typing the reference number of that text does not help either.
        assertThat(o.claim("627312341013").path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // Staff see it, with the reason, and cannot make that number a "bank".
        JsonNode alerts = counter(get("/api/v1/counter/payments")).path("alerts");
        assertThat(alerts).hasSize(4);
        assertThat(alerts.get(0).path("trusted").asBoolean()).isFalse();
        assertThat(alerts.get(0).path("trustNote").asText()).contains("phone number");
        assertThat(alerts.get(0).path("senderKey").isNull()).isTrue();
        MvcResult t = mvc.perform(post("/api/v1/counter/payments/alerts/" + alerts.get(3).path("id").asText()
                + "/trust-sender").header("X-Counter-Password", COUNTER)).andReturn();
        assertThat(t.getResponse().getStatus()).isEqualTo(400);
        // The bank's own messages keep working next to all this.
        Order honest = pricedOrder(1);
        honest.startPayment();                                               // Rs 2.02
        assertThat(sms("Rs 2.02 credited to a/c XX1234 UPI Ref 627399990001").path("paidOrder").asText())
                .isEqualTo(honest.code);
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void aChatMessageInAUpiAppNeverPaysAnOrder() throws Exception {
        Order o = pricedOrder(1);
        o.startPayment();                                                    // Rs 2.01
        // Anyone can send the shop a message in Google Pay, PhonePe or Paytm; the phone shows it as a notification.
        for (String app : List.of("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "com.whatsapp")) {
            JsonNode r = from("Rahul", "notification:" + app, "Rs 2.01 received " + app.length());
            assertThat(r.path("trusted").asBoolean()).as(app).isFalse();
            assertThat(r.path("paidOrder").isNull()).isTrue();
        }
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // A business UPI app has no chat: its "received" notification is the payment company speaking.
        JsonNode real = from("PhonePe Business", "notification:com.phonepe.app.business", "Rs 2.01 received from Rahul");
        assertThat(real.path("trusted").asBoolean()).isTrue();
        assertThat(real.path("paidOrder").asText()).isEqualTo(o.code);
    }

    @Test
    void anUnknownSenderNameWaitsUntilStaffSayItIsTheirBank() throws Exception {
        Order o = pricedOrder(2);
        o.startPayment();                                                    // Rs 4.01
        // A ledger app texts "you received" for whatever a stranger typed in: a sender name, but not a bank.
        JsonNode khata = from("VM-KHTABK", "sms", "You received Rs 4.01 from Rahul Stores. Balance Rs 0");
        assertThat(khata.path("trusted").asBoolean()).isFalse();
        assertThat(khata.path("paidOrder").isNull()).isTrue();
        // The shop's real bank is a small one this server does not know yet.
        JsonNode bank = from("JD-RDCBNK-S", "sms", "Your a/c XX1234 is credited by Rs.4.01 UPI Ref 627312340001");
        assertThat(bank.path("trusted").asBoolean()).isFalse();
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");

        JsonNode list = counter(get("/api/v1/counter/payments"));
        JsonNode waiting = list.path("alerts").get(0);
        assertThat(waiting.path("senderKey").asText()).isEqualTo("RDCBNK");
        assertThat(waiting.path("trustNote").asText()).contains("This is our bank");
        // One press at the counter: that sender is believed from now on, and the waiting message pays its order.
        JsonNode trusted = counter(post("/api/v1/counter/payments/alerts/" + waiting.path("id").asText() + "/trust-sender"));
        assertThat(trusted.path("sender").asText()).isEqualTo("RDCBNK");
        assertThat(o.view().path("status").asText()).isEqualTo("QUEUED");
        assertThat(counter(get("/api/v1/counter/payments")).path("senders").get(0).asText()).isEqualTo("RDCBNK");
        // The ledger app's text is still not counted.
        assertThat(jdbc.queryForObject("select count(*) from payment_alerts where not trusted", Integer.class)).isEqualTo(1);

        Order next = pricedOrder(3);
        next.startPayment();                                                 // Rs 6.01
        assertThat(from("AX-RDCBNK", "sms", "Your a/c XX1234 is credited by Rs.6.01 UPI Ref 627312340002")
                .path("paidOrder").asText()).isEqualTo(next.code);
    }

    @Test
    void anAmountIsNotGivenOutAgainWhileOthersAreFree() throws Exception {
        Order a = pricedOrder(2);
        assertThat(a.startPayment().path("amountPaise").asInt()).isEqualTo(401);
        // a never pays and its payment screen is long closed.
        jdbc.update("update orders set payment_started_at = now() - interval '3 hours' where id = ?::uuid", a.id);
        // The next student gets an amount nobody had, so a late message about Rs 4.01 can only mean a.
        assertThat(pricedOrder(2).startPayment().path("amountPaise").asInt()).isEqualTo(402);
    }

    @Test
    void whilePaymentsConfirmByThemselvesIHavePaidNeedsTheReferenceNumber() throws Exception {
        jdbc.update("delete from payment_verifiers");
        heartbeat();
        Order o = pricedOrder(1);
        o.startPayment();
        ObjectNode none = JSON.createObjectNode();
        none.putNull("reference");
        MvcResult r = mvc.perform(key(post("/api/v1/orders/" + o.id + "/payment/claim"), o)
                .contentType(MediaType.APPLICATION_JSON).content(none.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("error").asText()).isEqualTo("REFERENCE_NEEDED");
        assertThat(counter(get("/api/v1/counter/summary")).path("paymentsToCheck").asInt()).isZero();
        assertThat(o.claim("627312345678").path("stage").asText()).isEqualTo("Checking your payment");
    }

    // ------------------------------------------------------------------ helpers

    /** A message as the Verifier phone forwards it, from this sender (SMS) or with this title (notification). */
    private JsonNode from(String sender, String source, String text) throws Exception {
        ObjectNode b = JSON.createObjectNode();
        if (!sender.isEmpty()) b.put("from", sender);
        b.put("text", text);
        b.put("source", source);
        MvcResult r = mvc.perform(post("/api/v1/payments/upi/alerts").header("X-Alert-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(b.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private JsonNode sms(String text) throws Exception {
        return sms(text, null);
    }

    private JsonNode sms(String text, String stamp) throws Exception {
        return sms(text, stamp, null, null);
    }

    private void heartbeat() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/payments/upi/heartbeat").header("X-Alert-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"device\":\"Shop phone\",\"version\":\"1.0.0\",\"sms\":true,\"notifications\":true}")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/v1/payments/upi/heartbeat").header("X-Alert-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    private JsonNode sms(String text, String stamp, String source, String device) throws Exception {
        ObjectNode b = JSON.createObjectNode();
        b.put("from", "AX-BANK");
        b.put("text", text);
        if (stamp != null) b.put("sentStamp", stamp);
        if (source != null) b.put("source", source);
        if (device != null) b.put("device", device);
        MvcResult r = mvc.perform(post("/api/v1/payments/upi/alerts").header("X-Alert-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(b.toString())).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    /** A priced one-file order: a PDF of this many pages, black & white. */
    private Order pricedOrder(int pages) throws Exception {
        JsonNode c = json(mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn());
        Order o = new Order(c.path("orderId").asText(), c.path("accessKey").asText(), c.path("pickupCode").asText());
        JsonNode t = json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents"), o)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("fileName", "Notes.pdf", "fileType", "PDF",
                        "fileSizeBytes", Files.pdf(pages).length)))).andReturn());
        storage.upload(t.path("uploadUrl").asText(), Files.pdf(pages));
        String doc = t.path("document").path("id").asText();
        json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents/" + doc + "/uploaded"), o)).andReturn());
        MvcResult r = mvc.perform(key(post("/api/v1/orders/" + o.id + "/review"), o)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documents\":[{\"id\":\"" + doc + "\",\"settings\":{}}]}")).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(r).path("amountPaise").asInt()).isEqualTo(pages * 200);
        return o;
    }

    private final class Order {
        final String id;
        final String key;
        final String code;

        Order(String id, String key, String code) {
            this.id = id;
            this.key = key;
            this.code = code;
        }

        JsonNode startPayment() throws Exception {
            MvcResult r = mvc.perform(key(post("/api/v1/orders/" + id + "/payment"), this)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        MvcResult claimRaw(String reference) throws Exception {
            ObjectNode b = JSON.createObjectNode();
            b.put("reference", reference);
            return mvc.perform(key(post("/api/v1/orders/" + id + "/payment/claim"), this)
                    .contentType(MediaType.APPLICATION_JSON).content(b.toString())).andReturn();
        }

        JsonNode claim(String reference) throws Exception {
            MvcResult r = claimRaw(reference);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        JsonNode view() throws Exception {
            return json(mvc.perform(key(get("/api/v1/orders/" + id), this)).andReturn());
        }
    }

    private static MockHttpServletRequestBuilder key(MockHttpServletRequestBuilder b, Order o) {
        return b.header("X-Order-Key", o.key);
    }

    private JsonNode counter(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(b.header("X-Counter-Password", COUNTER).contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private static JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? JSON.nullNode() : JSON.readTree(s);
    }
}
