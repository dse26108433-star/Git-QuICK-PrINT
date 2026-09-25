package edu.campus.print.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.common.ApiException;
import edu.campus.print.common.Secrets;
import edu.campus.print.domain.PrintOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Razorpay Standard Checkout.
 *
 *  1. start():   the server creates a Razorpay order for the exact amount.
 *  2. The app opens Razorpay's payment screen (UPI, cards, wallets).
 *  3. confirm(): the app sends back payment id + signature. The server checks
 *     the signature with the key secret (which never leaves the server), then
 *     asks Razorpay for the payment and captures it if needed.
 *  4. findPayment(): a background check, for students who close the app the
 *     moment they have paid.
 */
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);
    private static final String API = "https://api.razorpay.com/v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String keyId;
    private final String keySecret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RazorpayGateway(String keyId, String keySecret) {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "PAYMENT_MODE is razorpay but RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET are empty. Set them in backend/.env");
        }
        this.keyId = keyId.trim();
        this.keySecret = keySecret.trim();
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public Checkout start(PrintOrder order, String centerName) {
        if (order.getGatewayOrderId() == null) {
            ObjectNode body = JSON.createObjectNode();
            body.put("amount", order.getAmountPaise());
            body.put("currency", order.getCurrency());
            body.put("receipt", order.getPickupCode());
            body.putObject("notes").put("order_id", order.getId().toString());
            JsonNode created = call("POST", "/orders", body.toString());
            String id = created.path("id").asText("");
            if (id.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_UNAVAILABLE",
                        "Payment could not be started. Try again in a moment.");
            }
            order.setGatewayOrderId(id);
            order.setPaymentProvider("razorpay");
        }
        return new Checkout("razorpay", keyId, order.getGatewayOrderId(), order.getAmountPaise(),
                order.getCurrency(), centerName + " - print " + order.getPickupCode());
    }

    @Override
    public boolean confirm(PrintOrder order, String paymentId, String signature) {
        if (order.getGatewayOrderId() == null || paymentId == null || signature == null) {
            return false;
        }
        // Always use OUR order id, never the one the app sends back.
        String expected = hmacHex(order.getGatewayOrderId() + "|" + paymentId);
        if (!Secrets.sameText(expected, signature.trim())) {
            log.warn("Razorpay signature mismatch for order {}", order.getId());
            return false;
        }
        JsonNode payment = call("GET", "/payments/" + paymentId, null);
        return captured(order, payment);
    }

    @Override
    public Optional<String> findPayment(PrintOrder order) {
        if (order.getGatewayOrderId() == null) {
            return Optional.empty();
        }
        JsonNode list = call("GET", "/orders/" + order.getGatewayOrderId() + "/payments", null);
        for (JsonNode p : list.path("items")) {
            if (captured(order, p)) {
                return Optional.of(p.path("id").asText());
            }
        }
        return Optional.empty();
    }

    /** Checks the payment belongs to this order and amount, and captures it if only authorised. */
    private boolean captured(PrintOrder order, JsonNode p) {
        if (!order.getGatewayOrderId().equals(p.path("order_id").asText())
                || p.path("amount").asInt(-1) != order.getAmountPaise()) {
            return false;
        }
        String status = p.path("status").asText("");
        if ("captured".equals(status)) {
            return true;
        }
        if ("authorized".equals(status)) {
            ObjectNode body = JSON.createObjectNode();
            body.put("amount", order.getAmountPaise());
            body.put("currency", order.getCurrency());
            JsonNode after = call("POST", "/payments/" + p.path("id").asText() + "/capture", body.toString());
            return "captured".equals(after.path("status").asText(""));
        }
        return false;
    }

    private JsonNode call(String method, String path, String jsonBody) {
        try {
            String basic = Base64.getEncoder().encodeToString(
                    (keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .header("Authorization", "Basic " + basic)
                    .timeout(Duration.ofSeconds(20));
            if ("POST".equals(method)) {
                b.header("Content-Type", "application/json")
                 .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
            } else {
                b.GET();
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("Razorpay {} {} -> {} {}", method, path, res.statusCode(), res.body());
                if (res.statusCode() == 401) {
                    log.error("Razorpay rejected the keys. Check RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET "
                            + "(test keys start with rzp_test_).");
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_UNAVAILABLE",
                        "The payment service did not answer properly. Try again in a moment.");
            }
            return JSON.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_UNAVAILABLE", "Payment service interrupted.");
        } catch (Exception e) {
            log.error("Razorpay {} {} failed: {}", method, path, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_UNAVAILABLE",
                    "Could not reach the payment service. Try again in a moment.");
        }
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
