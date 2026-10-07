package edu.campus.print.orders;

import edu.campus.print.orders.OrderDtos.*;
import edu.campus.print.payment.PaymentGateway;
import edu.campus.print.staff.StaffController;
import edu.campus.print.staff.StaffService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The student API, used by the website and the Android app.
 * No login: the X-Order-Key header proves the device created the order.
 *
 * Website, several files:
 *   POST /orders {}                                   -> order id, key, order number
 *   POST /orders/{id}/documents                       -> upload link (one per file)
 *   PUT  <upload link>                                   the file goes straight to storage
 *   POST /orders/{id}/documents/{doc}/uploaded        -> checked: pages counted (or refused)
 *   POST /orders/{id}/review                          -> every document's settings checked and priced
 *   POST /orders/{id}/payment ... /payment/confirm    -> paid, printing
 *   XeoGo Pay (direct UPI): POST /orders/{id}/payment -> UPI link + amount;
 *                           POST /orders/{id}/payment/claim -> "I have paid" (checked against the bank)
 *   GET  /orders/{id}                                 -> status, times, the files
 *   POST /orders/{id}/arrive                          -> "I am at the counter": staff see the order and its
 *                                                        files and hand the pages over (no pickup code)
 *
 * One-file apps: POST /orders with the file, PUT, POST /orders/{id}/uploaded, pay.
 *
 * College staff (the staff website and the staff app) use the same calls with
 * the X-Staff-Session header of their sign-in (see StaffController). Their
 * orders belong to the staff ID, are counted instead of priced, and go to the
 * printers with POST /orders/{id}/staff-print: no payment of any kind.
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private static final String KEY = "X-Order-Key";
    private static final String STAFF = StaffController.SESSION;

    private final OrderService service;
    private final StaffService staff;

    public OrderController(OrderService service, StaffService staff) {
        this.service = service;
        this.staff = staff;
    }

    /**
     * Who is asking: the order's key, and the staff member signed in (if the
     * request carries a sign-in). A sign-in that is no longer good is refused
     * (401), never ignored: the staff app then shows its sign-in screen.
     */
    private Caller who(String key, String session) {
        return new Caller(key, staff.optionalSession(session));
    }

    /** Name, prices, limits, the printers and what each can do. */
    @GetMapping("/shop")
    public ShopView shop() {
        return service.shop();
    }

    /** Start an order (a draft), or a one-file order with its upload link. */
    @PostMapping("/orders")
    public CreateOrderResponse create(@Valid @RequestBody(required = false) CreateOrderRequest req,
                                      @RequestHeader(value = STAFF, required = false) String session) {
        return service.create(req, who(null, session));
    }

    // ------------------------------------------------------------------ documents (website)

    /** Add a file to a draft order: returns where to upload it. */
    @PostMapping("/orders/{id}/documents")
    public UploadTicket addDocument(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                                    @RequestHeader(value = STAFF, required = false) String session,
                                    @Valid @RequestBody AddDocumentRequest req) {
        return service.addDocument(id, who(key, session), req);
    }

    /** A new upload link for the same file, to try again after a failed or cancelled upload. */
    @PostMapping("/orders/{id}/documents/{doc}/upload-url")
    public UploadTicket uploadUrl(@PathVariable UUID id, @PathVariable UUID doc,
                                  @RequestHeader(value = KEY, required = false) String key,
                                  @RequestHeader(value = STAFF, required = false) String session) {
        return service.uploadUrl(id, who(key, session), doc);
    }

    /** The upload finished: check the file and count its pages. */
    @PostMapping("/orders/{id}/documents/{doc}/uploaded")
    public DocumentView documentUploaded(@PathVariable UUID id, @PathVariable UUID doc,
                                         @RequestHeader(value = KEY, required = false) String key,
                                         @RequestHeader(value = STAFF, required = false) String session) {
        return service.documentUploaded(id, who(key, session), doc);
    }

    /** Remove a file from a draft order. */
    @DeleteMapping("/orders/{id}/documents/{doc}")
    public OrderView removeDocument(@PathVariable UUID id, @PathVariable UUID doc,
                                    @RequestHeader(value = KEY, required = false) String key,
                                    @RequestHeader(value = STAFF, required = false) String session) {
        return service.removeDocument(id, who(key, session), doc);
    }

    /** A short-lived link to the student's own file (to show a draft again after a reload). */
    @GetMapping("/orders/{id}/documents/{doc}/file-url")
    public FileUrl fileUrl(@PathVariable UUID id, @PathVariable UUID doc,
                           @RequestHeader(value = KEY, required = false) String key,
                           @RequestHeader(value = STAFF, required = false) String session) {
        return service.fileUrl(id, who(key, session), doc);
    }

    /** Check every document's settings against the file and the printers, and fix the price. */
    @PostMapping("/orders/{id}/review")
    public OrderView review(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                            @RequestHeader(value = STAFF, required = false) String session,
                            @RequestBody ReviewRequest req) {
        return service.review(id, who(key, session), req);
    }

    /** Back from the summary to change something (only before payment starts). */
    @PostMapping("/orders/{id}/edit")
    public OrderView edit(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                          @RequestHeader(value = STAFF, required = false) String session) {
        return service.edit(id, who(key, session));
    }

    // ------------------------------------------------------------------ one-file apps

    /** The upload of a one-file order finished: check the file and work out the price. */
    @PostMapping("/orders/{id}/uploaded")
    public OrderView uploaded(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                              @RequestHeader(value = STAFF, required = false) String session) {
        return service.confirmUpload(id, who(key, session));
    }

    // ------------------------------------------------------------------ payment and status

    /** Open the payment screen. */
    @PostMapping("/orders/{id}/payment")
    public PaymentGateway.Checkout startPayment(@PathVariable UUID id,
                                                @RequestHeader(value = KEY, required = false) String key,
                                                @RequestHeader(value = STAFF, required = false) String session) {
        return service.startPayment(id, who(key, session));
    }

    /** The payment screen said "success": verify it on the server. */
    @PostMapping("/orders/{id}/payment/confirm")
    public OrderView confirmPayment(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                                    @RequestHeader(value = STAFF, required = false) String session,
                                    @RequestBody ConfirmPaymentRequest body) {
        return service.confirmPayment(id, who(key, session), body);
    }

    /**
     * XeoGo Pay (PAYMENT_MODE=upi): "I have paid", with the UPI reference number if known.
     * The order is paid once a bank message or staff confirm the money arrived.
     */
    @PostMapping("/orders/{id}/payment/claim")
    public OrderView claimPayment(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                                  @RequestHeader(value = STAFF, required = false) String session,
                                  @Valid @RequestBody(required = false) ClaimPaymentRequest body) {
        return service.claimPayment(id, who(key, session), body);
    }

    /** Test mode only (PAYMENT_MODE=demo): marks the order paid without money. */
    @PostMapping("/orders/{id}/payment/demo")
    public OrderView demoPay(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                             @RequestHeader(value = STAFF, required = false) String session) {
        return service.demoPay(id, who(key, session));
    }

    /**
     * College staff: "Print". Free, counted against the staff member's pages
     * for the month; refused (409 OVER_LIMIT) when those do not cover it.
     */
    @PostMapping("/orders/{id}/staff-print")
    public OrderView staffPrint(@PathVariable UUID id, @RequestHeader(value = STAFF, required = false) String session) {
        return service.staffPrint(id, new Caller(null, staff.session(session)));
    }

    /** Status, times and files. The apps ask every few seconds. */
    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                         @RequestHeader(value = STAFF, required = false) String session) {
        return service.get(id, who(key, session));
    }

    /**
     * "I am at the counter": the paid order shows at the top of the staff screen
     * with pictures of its files. Sent again every minute while that screen is
     * open; {"here": false} when the student leaves it.
     */
    @PostMapping("/orders/{id}/arrive")
    public OrderView arrive(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                            @RequestHeader(value = STAFF, required = false) String session,
                            @RequestBody(required = false) ArriveRequest body) {
        return service.arrive(id, who(key, session), body == null || !Boolean.FALSE.equals(body.here()));
    }

    /** The picture of a file's first printed sheet, made by the Xerox PC (when the phone has no copy of its own). */
    @GetMapping("/orders/{id}/documents/{doc}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID id, @PathVariable UUID doc,
                                          @RequestHeader(value = KEY, required = false) String key,
                                          @RequestHeader(value = STAFF, required = false) String session) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(service.preview(id, who(key, session), doc));
    }

    /** Only before payment. */
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String key,
                            @RequestHeader(value = STAFF, required = false) String session) {
        return service.cancel(id, who(key, session));
    }
}
