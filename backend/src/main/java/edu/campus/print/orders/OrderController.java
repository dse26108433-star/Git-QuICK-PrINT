package edu.campus.print.orders;

import edu.campus.print.orders.OrderDtos.*;
import edu.campus.print.payment.PaymentGateway;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The student API, used by both the web app and the Android app.
 * No login: the X-Order-Key header proves the device created the order.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private static final String KEY = "X-Order-Key";

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** Name, prices, limits, and whether printers are online. */
    @GetMapping("/shop")
    public ShopView shop() {
        return service.shop();
    }

    /** 1. Start an order and get a link to upload the file to. */
    @PostMapping("/orders")
    public CreateOrderResponse create(@Valid @RequestBody CreateOrderRequest req) {
        return service.create(req);
    }

    /** 2. The upload finished: check the file and work out the price. */
    @PostMapping("/orders/{id}/uploaded")
    public OrderView uploaded(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.confirmUpload(id, key);
    }

    /** 3. Open the payment screen. */
    @PostMapping("/orders/{id}/payment")
    public PaymentGateway.Checkout startPayment(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.startPayment(id, key);
    }

    /** 4. The payment screen said "success": verify it on the server. */
    @PostMapping("/orders/{id}/payment/confirm")
    public OrderView confirmPayment(@PathVariable UUID id, @RequestHeader(KEY) String key,
                                    @RequestBody ConfirmPaymentRequest body) {
        return service.confirmPayment(id, key, body);
    }

    /** Test mode only (PAYMENT_MODE=demo): marks the order paid without money. */
    @PostMapping("/orders/{id}/payment/demo")
    public OrderView demoPay(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.demoPay(id, key);
    }

    /** Status and pickup code. The apps ask every few seconds. */
    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.get(id, key);
    }

    /** Only before payment. */
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.cancel(id, key);
    }
}
