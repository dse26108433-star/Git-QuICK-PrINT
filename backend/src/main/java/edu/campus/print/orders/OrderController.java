package edu.campus.print.orders;

import edu.campus.print.orders.OrderDtos.*;
import edu.campus.print.payment.PaymentGateway;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The student API, used by the website and the Android app.
 * No login: the X-Order-Key header proves the device created the order.
 *
 * Website, several files:
 *   POST /orders {}                                   -> order id, key, pickup code
 *   POST /orders/{id}/documents                       -> upload link (one per file)
 *   PUT  <upload link>                                   the file goes straight to storage
 *   POST /orders/{id}/documents/{doc}/uploaded        -> checked: pages counted (or refused)
 *   POST /orders/{id}/review                          -> every document's settings checked and priced
 *   POST /orders/{id}/payment ... /payment/confirm    -> paid, printing
 *
 * One-file apps: POST /orders with the file, PUT, POST /orders/{id}/uploaded, pay.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private static final String KEY = "X-Order-Key";

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /** Name, prices, limits, the printers and what each can do. */
    @GetMapping("/shop")
    public ShopView shop() {
        return service.shop();
    }

    /** Start an order (a draft), or a one-file order with its upload link. */
    @PostMapping("/orders")
    public CreateOrderResponse create(@Valid @RequestBody(required = false) CreateOrderRequest req) {
        return service.create(req);
    }

    // ------------------------------------------------------------------ documents (website)

    /** Add a file to a draft order: returns where to upload it. */
    @PostMapping("/orders/{id}/documents")
    public UploadTicket addDocument(@PathVariable UUID id, @RequestHeader(KEY) String key,
                                    @Valid @RequestBody AddDocumentRequest req) {
        return service.addDocument(id, key, req);
    }

    /** A new upload link for the same file, to try again after a failed or cancelled upload. */
    @PostMapping("/orders/{id}/documents/{doc}/upload-url")
    public UploadTicket uploadUrl(@PathVariable UUID id, @PathVariable UUID doc, @RequestHeader(KEY) String key) {
        return service.uploadUrl(id, key, doc);
    }

    /** The upload finished: check the file and count its pages. */
    @PostMapping("/orders/{id}/documents/{doc}/uploaded")
    public DocumentView documentUploaded(@PathVariable UUID id, @PathVariable UUID doc,
                                         @RequestHeader(KEY) String key) {
        return service.documentUploaded(id, key, doc);
    }

    /** Remove a file from a draft order. */
    @DeleteMapping("/orders/{id}/documents/{doc}")
    public OrderView removeDocument(@PathVariable UUID id, @PathVariable UUID doc, @RequestHeader(KEY) String key) {
        return service.removeDocument(id, key, doc);
    }

    /** A short-lived link to the student's own file (to show a draft again after a reload). */
    @GetMapping("/orders/{id}/documents/{doc}/file-url")
    public FileUrl fileUrl(@PathVariable UUID id, @PathVariable UUID doc, @RequestHeader(KEY) String key) {
        return service.fileUrl(id, key, doc);
    }

    /** Check every document's settings against the file and the printers, and fix the price. */
    @PostMapping("/orders/{id}/review")
    public OrderView review(@PathVariable UUID id, @RequestHeader(KEY) String key, @RequestBody ReviewRequest req) {
        return service.review(id, key, req);
    }

    /** Back from the summary to change something (only before payment starts). */
    @PostMapping("/orders/{id}/edit")
    public OrderView edit(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.edit(id, key);
    }

    // ------------------------------------------------------------------ one-file apps

    /** The upload of a one-file order finished: check the file and work out the price. */
    @PostMapping("/orders/{id}/uploaded")
    public OrderView uploaded(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.confirmUpload(id, key);
    }

    // ------------------------------------------------------------------ payment and status

    /** Open the payment screen. */
    @PostMapping("/orders/{id}/payment")
    public PaymentGateway.Checkout startPayment(@PathVariable UUID id, @RequestHeader(KEY) String key) {
        return service.startPayment(id, key);
    }

    /** The payment screen said "success": verify it on the server. */
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
