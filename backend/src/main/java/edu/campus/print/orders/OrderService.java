package edu.campus.print.orders;

import edu.campus.print.common.ApiException;
import edu.campus.print.common.Secrets;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.ShopProperties;
import edu.campus.print.domain.FileType;
import edu.campus.print.domain.OrderStatus;
import edu.campus.print.domain.PrintOrder;
import edu.campus.print.domain.Printer;
import edu.campus.print.domain.ShopSettings;
import edu.campus.print.orders.OrderDtos.*;
import edu.campus.print.payment.DemoGateway;
import edu.campus.print.payment.PaymentGateway;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.repo.PrinterRepository;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.storage.FileInspector;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The student's journey:
 *
 *   create()          choose file + colour/B&W + copies  -> upload link
 *   confirmUpload()   file arrived: check it, count pages, work out the price
 *   startPayment()    open Razorpay (or demo)
 *   confirmPayment()  payment verified -> QUEUED -> the Xerox PC prints it
 *   get()             status + pickup code, polled by the app
 *
 * There is no login. Each order has a random access key that only the
 * student's device holds; without it an order cannot be seen or paid.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final int RAZORPAY_MINIMUM_PAISE = 100;

    private final OrderRepository orders;
    private final PrinterRepository printers;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final FileInspector inspector;
    private final PaymentGateway gateway;
    private final ShopProperties limits;
    private final AgentProperties agentProps;

    public OrderService(OrderRepository orders, PrinterRepository printers, ShopSettingsRepository settings,
                        SupabaseStorage storage, FileInspector inspector, PaymentGateway gateway,
                        ShopProperties limits, AgentProperties agentProps) {
        this.orders = orders;
        this.printers = printers;
        this.settings = settings;
        this.storage = storage;
        this.inspector = inspector;
        this.gateway = gateway;
        this.limits = limits;
        this.agentProps = agentProps;
    }

    // ------------------------------------------------------------------ shop

    public ShopView shop() {
        ShopSettings s = settings.current();
        List<Printer> all = printers.findAll();
        Duration fresh = Duration.ofSeconds(agentProps.offlineAfterSeconds());
        return new ShopView(
                s.getCenterName(), s.getPriceBwPaise(), s.getPriceColorPaise(), s.getCurrency(),
                limits.maxFileSizeBytes(), limits.maxPages(), limits.maxFilePages(), limits.maxCopies(),
                gateway.name(),
                all.stream().anyMatch(p -> p.isEnabled() && p.canPrint(false)),
                all.stream().anyMatch(p -> p.isEnabled() && p.canPrint(true)),
                all.stream().anyMatch(p -> p.canPrint(false) && p.isOnline(fresh)),
                all.stream().anyMatch(p -> p.canPrint(true) && p.isOnline(fresh)),
                orders.countByStatusIn(OrderStatus.IN_PROGRESS));
    }

    // ------------------------------------------------------------------ create

    public CreateOrderResponse create(CreateOrderRequest req) {
        if (req.copies() < 1 || req.copies() > limits.maxCopies()) {
            throw ApiException.badRequest("BAD_COPIES", "Choose between 1 and " + limits.maxCopies() + " copies.");
        }
        if (req.fileSizeBytes() > limits.maxFileSizeBytes()) {
            throw ApiException.badRequest("FILE_TOO_LARGE",
                    "Files must be smaller than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB.");
        }
        // A picture is one page, so only PDFs can have a page choice.
        String pages = req.fileType() == FileType.PDF
                ? PageRanges.checkSyntax(req.pages()) : null;
        boolean anyPrinter = printers.findAll().stream().anyMatch(p -> p.isEnabled() && p.canPrint(req.color()));
        if (!anyPrinter) {
            throw ApiException.conflict(req.color() ? "NO_COLOR_PRINTER" : "NO_BW_PRINTER",
                    req.color() ? "Colour printing is not available right now. Choose black & white."
                                : "Black & white printing is not available right now.");
        }

        PrintOrder o = PrintOrder.create();
        o.setPickupCode(uniquePickupCode());
        String accessKey = Secrets.newKey();
        o.setAccessKeyHash(Secrets.sha256Hex(accessKey));
        o.setFileName(cleanName(req.fileName()));
        o.setFileType(req.fileType());
        o.setStoragePath("orders/" + o.getId() + req.fileType().extension());
        o.setFileSizeBytes(req.fileSizeBytes());
        o.setColor(req.color());
        o.setCopies(req.copies());
        o.setPageRanges(pages);
        o.setCurrency(settings.current().getCurrency());
        orders.save(o);

        SupabaseStorage.SignedUpload upload = storage.createSignedUpload(o.getStoragePath());
        log.info("Order {} created ({} {} x{}{})", o.getPickupCode(), req.fileType(), req.color() ? "colour" : "B/W",
                req.copies(), pages == null ? "" : ", pages " + pages);
        return new CreateOrderResponse(o.getId(), accessKey, o.getPickupCode(), upload.url(),
                req.fileType().mimeType());
    }

    // ------------------------------------------------------------------ upload

    public OrderView confirmUpload(UUID id, String key) {
        PrintOrder o = owned(id, key);
        if (o.getStatus() != OrderStatus.AWAITING_UPLOAD) {
            if (o.getStatus() == OrderStatus.AWAITING_PAYMENT || o.getStatus().isPaid()) {
                return view(o);          // a repeated request is harmless
            }
            throw ApiException.conflict("BAD_STATE", "This order can no longer be changed. Start a new one.");
        }

        int readLimit = (int) Math.min(Integer.MAX_VALUE - 16L, limits.maxFileSizeBytes() + 1);
        SupabaseStorage.Probe probe = storage.probe(o.getStoragePath(), readLimit)
                .orElseThrow(() -> ApiException.badRequest("UPLOAD_MISSING",
                        "The file did not arrive. Check your internet and try again."));

        if (probe.totalSize() > limits.maxFileSizeBytes()) {
            throw reject(o, "FILE_TOO_LARGE",
                    "Files must be smaller than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB.");
        }
        FileInspector.Result r = inspector.inspect(probe.prefix());
        if (!r.ok()) {
            throw reject(o, r.problemCode(), r.problem());
        }
        if (r.pages() > limits.maxFilePages()) {
            throw reject(o, "TOO_MANY_PAGES",
                    "This file has " + r.pages() + " pages. Files can have up to " + limits.maxFilePages() + ".");
        }
        // The student's page choice, checked against the REAL page count.
        PageRanges.Selection chosen;
        try {
            chosen = PageRanges.resolve(r.type() == FileType.PDF ? o.getPageRanges() : null,
                    r.pages());
        } catch (ApiException e) {
            throw reject(o, "BAD_PAGES", e.getMessage());
        }
        if (chosen.count() > limits.maxPages()) {
            throw reject(o, "TOO_MANY_PAGES", (chosen.spec() == null ? "This file has " : "You chose ")
                    + chosen.count() + " pages. Up to " + limits.maxPages()
                    + " pages can be printed per order: choose the pages you need.");
        }

        ShopSettings s = settings.current();
        long amount = (long) chosen.count() * o.getCopies() * s.pricePerPage(o.isColor());
        if (amount > 0 && amount < RAZORPAY_MINIMUM_PAISE && !(gateway instanceof DemoGateway)) {
            amount = RAZORPAY_MINIMUM_PAISE;        // online payments start at Rs 1
        }
        if (amount > 10_000_000) {
            throw reject(o, "TOO_EXPENSIVE", "This order is too large. Print fewer copies or split the file.");
        }

        o.setFileType(r.type());           // the real type, whatever the file was called
        o.setPageCount(r.pages());
        o.setPageRanges(chosen.spec());
        o.setPrintPages(chosen.count());
        o.setSha256(r.sha256());
        o.setFileSizeBytes(probe.totalSize());
        o.setAmountPaise((int) amount);
        o.setStatus(OrderStatus.AWAITING_PAYMENT);
        orders.save(o);

        if (amount == 0) {
            orders.markPaid(o.getId(), "free", "free");    // prices set to 0: nothing to pay
        }
        log.info("Order {} uploaded: {} page(s){}, {} paise", o.getPickupCode(), r.pages(),
                chosen.spec() == null ? "" : ", printing " + chosen.count() + " (" + chosen.spec() + ")", amount);
        return view(reload(o.getId()));
    }

    // ------------------------------------------------------------------ payment

    public PaymentGateway.Checkout startPayment(UUID id, String key) {
        PrintOrder o = owned(id, key);
        if (o.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT",
                    o.getStatus().isPaid() ? "This order is already paid." : "This order cannot be paid now.");
        }
        String centerName = settings.current().getCenterName();
        boolean hadGatewayOrder = o.getGatewayOrderId() != null;
        PaymentGateway.Checkout checkout = gateway.start(o, centerName);
        if (!hadGatewayOrder && o.getGatewayOrderId() != null
                && orders.setGatewayOrder(o.getId(), o.getGatewayOrderId(), gateway.name()) == 0) {
            // A second click got there first: use the Razorpay order it created.
            checkout = gateway.start(reload(id), centerName);
        }
        return checkout;
    }

    public OrderView confirmPayment(UUID id, String key, ConfirmPaymentRequest req) {
        PrintOrder o = owned(id, key);
        if (o.getStatus().isPaid()) {
            return view(o);
        }
        if (o.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT", "This order cannot be paid now.");
        }
        if (req == null || !gateway.confirm(o, req.paymentId(), req.signature())) {
            throw ApiException.badRequest("PAYMENT_NOT_CONFIRMED",
                    "We could not confirm the payment yet. If money was taken, wait one minute: "
                            + "it is checked again automatically.");
        }
        if (orders.markPaid(o.getId(), gateway.name(), req.paymentId()) == 1) {
            log.info("Order {} paid ({})", o.getPickupCode(), req.paymentId());
        }
        return view(reload(id));
    }

    public OrderView demoPay(UUID id, String key) {
        if (!(gateway instanceof DemoGateway)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DEMO", "Demo payment is switched off.");
        }
        PrintOrder o = owned(id, key);
        if (o.getStatus().isPaid()) {
            return view(o);
        }
        if (orders.markPaid(o.getId(), "demo", "demo_" + Secrets.newKey().substring(0, 12)) == 0) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT", "This order cannot be paid now.");
        }
        log.info("Order {} marked paid (DEMO)", o.getPickupCode());
        return view(reload(id));
    }

    /** Asks the gateway if an unpaid order was actually paid. Used by the app poll and the background job. */
    public void reconcile(PrintOrder o) {
        if (gateway instanceof DemoGateway || o.getGatewayOrderId() == null) {
            return;
        }
        orders.touchPaymentCheck(o.getId());
        try {
            gateway.findPayment(o).ifPresent(paymentId -> {
                if (orders.markPaid(o.getId(), gateway.name(), paymentId) == 1) {
                    log.info("Order {} found paid by background check ({})", o.getPickupCode(), paymentId);
                }
            });
        } catch (ApiException e) {
            log.debug("Payment check for {} failed: {}", o.getPickupCode(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ status

    public OrderView get(UUID id, String key) {
        PrintOrder o = owned(id, key);
        if (o.getStatus() == OrderStatus.AWAITING_PAYMENT && o.getGatewayOrderId() != null
                && (o.getPaymentCheckedAt() == null
                    || o.getPaymentCheckedAt().isBefore(Instant.now().minusSeconds(10)))) {
            reconcile(o);
            o = reload(id);
        }
        return view(o);
    }

    public OrderView cancel(UUID id, String key) {
        PrintOrder o = owned(id, key);
        if (orders.cancelUnpaid(o.getId()) == 0) {
            throw ApiException.conflict("CANNOT_CANCEL", o.getStatus().isPaid()
                    ? "This order is already paid. Please ask at the Xerox counter."
                    : "This order can no longer be cancelled.");
        }
        storage.delete(o.getStoragePath());
        orders.markFileDeleted(o.getId());
        return view(reload(id));
    }

    // ------------------------------------------------------------------ view

    public OrderView view(PrintOrder o) {
        String printerName = o.getPrinterId() == null ? null
                : printers.findById(o.getPrinterId()).map(Printer::getName).orElse(null);
        Integer ahead = (o.getStatus() == OrderStatus.QUEUED && o.getPaidAt() != null)
                ? (int) orders.countQueuedBefore(o.getPaidAt()) : null;

        String stage;
        String message = null;
        switch (o.getStatus()) {
            case AWAITING_UPLOAD -> stage = "Uploading";
            case AWAITING_PAYMENT -> stage = "Waiting for payment";
            case QUEUED -> {
                stage = "Paid - waiting for a printer";
                if (ahead != null && ahead > 0) message = ahead + (ahead == 1 ? " order" : " orders") + " ahead of you.";
            }
            case CLAIMED, DOWNLOADING -> stage = "Getting ready to print";
            case SUBMITTED -> stage = printerName == null ? "Printing now" : "Printing on " + printerName;
            case COMPLETED -> {
                stage = o.getCollectedAt() != null ? "Collected" : "Ready - collect at the counter";
                if (o.getCollectedAt() == null) {
                    message = "Show pickup code " + o.getPickupCode() + " at the Xerox counter. The same code is "
                            + "printed small at the bottom of your first page, so staff can find your pages.";
                }
            }
            case FAILED -> {
                stage = o.getPaidAt() != null ? "Problem - please visit the counter" : "Could not use this file";
                message = o.getPaidAt() != null
                        ? "Your payment is safe. Show pickup code " + o.getPickupCode()
                          + " at the counter and they will print it again or refund you."
                        : o.getErrorMessage();
            }
            case CANCELLED -> {
                stage = "Cancelled";
                message = o.getErrorMessage();
            }
            case EXPIRED -> stage = "Expired - not paid";
            default -> stage = o.getStatus().name();
        }

        return new OrderView(o.getId(), o.getPickupCode(), o.getStatus().name(), stage, message,
                o.getFileName(), o.getFileType().name(), o.getPageCount(), o.getPageRanges(), o.getPrintPages(),
                o.getCopies(), o.isColor(),
                o.getAmountPaise(), o.getCurrency(), printerName, ahead, o.getCollectedAt() != null,
                o.getCreatedAt(), o.getPaidAt(), o.getCompletedAt());
    }

    // ------------------------------------------------------------------ helpers

    private PrintOrder owned(UUID id, String key) {
        PrintOrder o = orders.findById(id).orElse(null);
        if (o == null || key == null || !Secrets.sameText(o.getAccessKeyHash(), Secrets.sha256Hex(key.trim()))) {
            throw ApiException.notFound("That order");
        }
        return o;
    }

    private PrintOrder reload(UUID id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
    }

    /** Marks the order failed, removes the file, and returns the error to throw. */
    private ApiException reject(PrintOrder o, String code, String message) {
        o.setStatus(OrderStatus.FAILED);
        o.setErrorCode(code);
        o.setErrorMessage(message);
        orders.save(o);
        storage.delete(o.getStoragePath());
        orders.markFileDeleted(o.getId());
        return ApiException.badRequest(code, message);
    }

    private String uniquePickupCode() {
        for (int i = 0; i < 20; i++) {
            String code = Secrets.newPickupCode();
            if (!orders.existsByPickupCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find a free pickup code");
    }

    /** Keeps only the file name part, without folders or control characters. */
    static String cleanName(String name) {
        String base = name.replaceAll(".*[/\\\\]", "").replaceAll("\\p{Cntrl}", "").trim();
        if (base.isEmpty()) base = "document";
        return base.length() > 150 ? base.substring(0, 150) : base;
    }
}
