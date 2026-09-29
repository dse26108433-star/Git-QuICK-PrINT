package edu.campus.print.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.print.common.ApiException;
import edu.campus.print.common.Secrets;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.ShopProperties;
import edu.campus.print.domain.DocumentStatus;
import edu.campus.print.domain.FileType;
import edu.campus.print.domain.OrderDocument;
import edu.campus.print.domain.OrderStatus;
import edu.campus.print.domain.PrintOrder;
import edu.campus.print.domain.Printer;
import edu.campus.print.domain.ShopSettings;
import edu.campus.print.orders.OrderDtos.*;
import edu.campus.print.payment.DemoGateway;
import edu.campus.print.payment.PaymentGateway;
import edu.campus.print.printing.Finishing;
import edu.campus.print.printing.PaperSize;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.printing.Pricing;
import edu.campus.print.printing.PrinterRules;
import edu.campus.print.printing.SettingsChecker;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.repo.PrinterRepository;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.storage.FileInspector;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The student's journey:
 *
 *   create()            start an order (a draft) -> pickup code + private key
 *   addDocument()       one per file -> an upload link (files go straight to storage)
 *   documentUploaded()  file arrived: check what it really is, count its pages
 *   review()            the student's settings for every document: checked against the
 *                       real files and the real printers, priced -> AWAITING_PAYMENT
 *   startPayment()      open Razorpay (or demo)
 *   confirmPayment()    payment verified -> QUEUED -> the Xerox PC prints each document
 *   get()               status + pickup code, polled by the app
 *
 * One-file apps (Android) still send the file and choices with create() and
 * then call confirmUpload(), which checks and prices in one go.
 *
 * There is no login. Each order has a random access key that only the
 * student's device holds; without it an order cannot be seen or paid.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RAZORPAY_MINIMUM_PAISE = 100;
    private static final long MAX_ORDER_PAISE = 10_000_000;

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final PrinterRepository printers;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final FileInspector inspector;
    private final PaymentGateway gateway;
    private final ShopProperties limits;
    private final AgentProperties agentProps;
    private final TransactionTemplate tx;
    private final Semaphore checking;

    public OrderService(OrderRepository orders, OrderDocumentRepository documents, PrinterRepository printers,
                        ShopSettingsRepository settings, SupabaseStorage storage, FileInspector inspector,
                        PaymentGateway gateway, ShopProperties limits, AgentProperties agentProps,
                        PlatformTransactionManager txManager) {
        this.orders = orders;
        this.documents = documents;
        this.printers = printers;
        this.settings = settings;
        this.storage = storage;
        this.inspector = inspector;
        this.gateway = gateway;
        this.limits = limits;
        this.agentProps = agentProps;
        this.tx = new TransactionTemplate(txManager);
        this.checking = new Semaphore(limits.maxParallelChecks(), true);
    }

    // ------------------------------------------------------------------ shop

    public ShopView shop() {
        ShopSettings s = settings.current();
        List<Printer> all = printers.findAll();
        var fresh = agentProps.offlineAfter();
        List<Printer> usable = all.stream().filter(Printer::isEnabled)
                .sorted(Comparator.comparing(Printer::getName)).toList();

        List<PrinterOption> options = new ArrayList<>();
        Set<String> paperIds = new HashSet<>();
        Map<String, String> finishing = new LinkedHashMap<>();
        Map<String, String> media = new LinkedHashMap<>();
        for (Printer p : usable) {
            var f = p.features();
            options.add(new PrinterOption(p.getId(), p.getName(), p.isOnline(fresh), p.isSupportsColor(),
                    p.isAcceptsBw(), f.paperSizes(), f.duplex(), f.finishing(), f.mediaTypes(), f.borderless(),
                    f.highQuality()));
            paperIds.addAll(f.paperSizes());
            media.putAll(f.mediaTypeNames());
        }
        Finishing.LABELS.forEach((id, label) -> {
            if (usable.stream().anyMatch(p -> p.features().finishing().contains(id))) finishing.put(id, label);
        });
        List<PaperOption> paper = PaperSize.ALL.stream()
                .filter(ps -> paperIds.contains(ps.id()))
                .map(ps -> new PaperOption(ps.id(), ps.label(), ps.widthMm(), ps.heightMm()))
                .toList();

        return new ShopView(
                s.getCenterName(), s.getPriceBwPaise(), s.getPriceColorPaise(), s.getCurrency(),
                limits.maxFileSizeBytes(), limits.maxPages(), limits.maxFilePages(), limits.maxCopies(),
                limits.maxDocuments(), gateway.name(),
                usable.stream().anyMatch(p -> p.canPrint(false)),
                usable.stream().anyMatch(p -> p.canPrint(true)),
                all.stream().anyMatch(p -> p.canPrint(false) && p.isOnline(fresh)),
                all.stream().anyMatch(p -> p.canPrint(true) && p.isOnline(fresh)),
                orders.countByStatusIn(OrderStatus.IN_PROGRESS),
                new Printing(options, paper, finishing, media, s.getPricing(), PrintSettings.defaults()));
    }

    // ------------------------------------------------------------------ create

    public CreateOrderResponse create(CreateOrderRequest req) {
        PrintOrder o = PrintOrder.create();
        o.setPickupCode(uniquePickupCode());
        String accessKey = Secrets.newKey();
        o.setAccessKeyHash(Secrets.sha256Hex(accessKey));
        o.setCurrency(settings.current().getCurrency());

        if (req == null || !req.singleFile()) {
            orders.save(o);
            log.info("Order {} started", o.getPickupCode());
            return new CreateOrderResponse(o.getId(), accessKey, o.getPickupCode(), null, null, null);
        }

        // One-file app: the file and the choices come now.
        int copies = req.copies() == null ? 1 : req.copies();
        if (copies < 1 || copies > limits.maxCopies()) {
            throw ApiException.badRequest("BAD_COPIES", "Choose between 1 and " + limits.maxCopies() + " copies.");
        }
        boolean color = Boolean.TRUE.equals(req.color());
        FileType type = requireType(req.fileType());
        long size = req.fileSizeBytes() == null ? 0 : req.fileSizeBytes();
        checkSize(size);
        // A picture is one page, so only PDFs can have a page choice.
        String pages = type == FileType.PDF ? PageRanges.checkSyntax(req.pages()) : null;
        if (printers.findByEnabledTrueOrderByName().stream().noneMatch(p -> p.canPrint(color))) {
            throw ApiException.conflict(color ? "NO_COLOR_PRINTER" : "NO_BW_PRINTER",
                    color ? "Colour printing is not available right now. Choose black & white."
                          : "Black & white printing is not available right now.");
        }
        orders.save(o);
        OrderDocument d = OrderDocument.create(o.getId(), 1, cleanName(req.fileName()), type, size);
        PrintSettings def = PrintSettings.defaults();
        d.setSettings(new PrintSettings(copies, color, pages, def.duplex(), def.paperSize(), def.orientation(),
                def.scaling(), def.scalePercent(), def.pagesPerSheet(), def.marginMm(), def.rotation(), def.center(),
                def.collate(), null, null, null, null, def.quality()));
        documents.save(d);

        SupabaseStorage.SignedUpload upload = storage.createSignedUpload(d.getStoragePath());
        log.info("Order {} created ({} {} x{}{})", o.getPickupCode(), type, color ? "colour" : "B/W",
                copies, pages == null ? "" : ", pages " + pages);
        return new CreateOrderResponse(o.getId(), accessKey, o.getPickupCode(), upload.url(), type.mimeType(),
                d.getId());
    }

    // ------------------------------------------------------------------ documents

    /** Adds one file to a draft order and returns where to upload it. */
    public UploadTicket addDocument(UUID orderId, String key, AddDocumentRequest req) {
        owned(orderId, key);
        if (req == null || req.fileName() == null || req.fileName().isBlank()) {
            throw ApiException.badRequest("BAD_FILE", "The file has no name.");
        }
        FileType type = requireType(req.fileType());
        checkSize(req.fileSizeBytes());

        OrderDocument d = tx.execute(t -> {
            PrintOrder o = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("That order"));
            requireDraft(o);
            if (documents.countByOrderId(orderId) >= limits.maxDocuments()) {
                throw ApiException.badRequest("TOO_MANY_DOCUMENTS",
                        "One order can have up to " + limits.maxDocuments() + " files. Start a second order for the rest.");
            }
            OrderDocument doc = OrderDocument.create(orderId, documents.maxPosition(orderId) + 1,
                    cleanName(req.fileName()), type, req.fileSizeBytes());
            documents.save(doc);
            orders.touch(orderId);
            return doc;
        });
        SupabaseStorage.SignedUpload upload = storage.createSignedUpload(d.getStoragePath());
        return new UploadTicket(documentView(d, null), upload.url(), type.mimeType());
    }

    /** A fresh upload link for the same document: to try again after a failed or cancelled upload. */
    public UploadTicket uploadUrl(UUID orderId, String key, UUID documentId) {
        PrintOrder o = owned(orderId, key);
        requireDraft(o);
        OrderDocument d = ownedDocument(o, documentId);
        if (d.getStatus() != DocumentStatus.UPLOADING) {
            throw ApiException.conflict("ALREADY_UPLOADED", "This file is already uploaded.");
        }
        SupabaseStorage.SignedUpload upload = storage.createSignedUpload(d.getStoragePath());
        return new UploadTicket(documentView(d, null), upload.url(), d.getFileType().mimeType());
    }

    /**
     * The upload finished: read the file, check what it really is, count its
     * pages. READY, or REJECTED with the reason (the file is then removed).
     */
    public DocumentView documentUploaded(UUID orderId, String key, UUID documentId) {
        PrintOrder o = owned(orderId, key);
        OrderDocument d = ownedDocument(o, documentId);
        if (d.getStatus() != DocumentStatus.UPLOADING) {
            return documentView(d, null);            // a repeated request is harmless
        }
        requireDraft(o);
        Inspection ins = inspect(d);

        OrderDocument saved = tx.execute(t -> {
            PrintOrder locked = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("That order"));
            requireDraft(locked);
            OrderDocument doc = documents.findByIdAndOrderId(documentId, orderId)
                    .orElseThrow(() -> ApiException.notFound("That file"));
            if (doc.getStatus() != DocumentStatus.UPLOADING) return doc;
            if (ins.problemCode != null) {
                doc.setStatus(DocumentStatus.REJECTED);
                doc.setErrorCode(ins.problemCode);
                doc.setErrorMessage(ins.problem);
            } else {
                doc.setStatus(DocumentStatus.READY);
                doc.setFileType(ins.result.type());       // the real type, whatever the file was called
                doc.setPageCount(ins.result.pages());
                doc.setSha256(ins.result.sha256());
                doc.setFileSizeBytes(ins.size);
                doc.setImageInfo(ins.result.image());
            }
            documents.save(doc);
            orders.touch(orderId);
            return doc;
        });
        if (saved.getStatus() == DocumentStatus.REJECTED) {
            deleteFile(saved);
            log.info("Order {}: \"{}\" refused ({})", o.getPickupCode(), saved.getFileName(), saved.getErrorCode());
        }
        return documentView(saved, null);
    }

    /** Takes a document out of a draft order. */
    public OrderView removeDocument(UUID orderId, String key, UUID documentId) {
        owned(orderId, key);
        OrderDocument removed = tx.execute(t -> {
            PrintOrder o = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("That order"));
            requireDraft(o);
            OrderDocument d = documents.findByIdAndOrderId(documentId, orderId).orElse(null);
            if (d == null) return null;                   // already gone: harmless
            documents.delete(d);
            orders.touch(orderId);
            return d;
        });
        if (removed != null && !removed.isFileDeleted()) {
            storage.delete(removed.getStoragePath());
        }
        return view(reload(orderId));
    }

    /** A short-lived link to the student's own file, to show a draft again after the page was reloaded. */
    public FileUrl fileUrl(UUID orderId, String key, UUID documentId) {
        PrintOrder o = owned(orderId, key);
        OrderDocument d = ownedDocument(o, documentId);
        if (d.isFileDeleted() || d.getStatus() == DocumentStatus.UPLOADING || d.getStatus() == DocumentStatus.REJECTED) {
            throw ApiException.conflict("NO_FILE", "This file is not available.");
        }
        return new FileUrl(storage.createSignedDownload(d.getStoragePath()));
    }

    // ------------------------------------------------------------------ review (price)

    /**
     * Checks every document's settings against its real file and the real
     * printers, stores them exactly as they will print, and fixes the price.
     * Nothing is saved unless every document is fine; problems come back per
     * document ("documents": [{"id", "fileName", "message"}]).
     */
    public OrderView review(UUID orderId, String key, ReviewRequest req) {
        owned(orderId, key);
        if (req == null || req.documents() == null || req.documents().isEmpty()) {
            throw ApiException.badRequest("NO_DOCUMENTS", "Add at least one file to print.");
        }
        PrintOrder result = tx.execute(t -> {
            PrintOrder o = orders.lockById(orderId).orElseThrow(() -> ApiException.notFound("That order"));
            boolean repricing = o.getStatus() == OrderStatus.AWAITING_PAYMENT && o.getGatewayOrderId() == null;
            if (o.getStatus() != OrderStatus.AWAITING_UPLOAD && !repricing) {
                throw ApiException.conflict("BAD_STATE", o.getStatus().isPaid() ? "This order is already paid."
                        : "This order can no longer be changed. Start a new one.");
            }
            List<OrderDocument> docs = documents.findByOrderIdOrderByPosition(orderId);
            priceDocuments(o, docs, req.documents());
            if (o.getStatus() == OrderStatus.AWAITING_UPLOAD) o.setStatus(OrderStatus.AWAITING_PAYMENT);
            orders.save(o);
            return o;
        });
        if (result.getAmountPaise() == 0) {
            orders.markPaid(orderId, "free", "free");       // prices set to 0: nothing to pay
        }
        log.info("Order {} priced: {} document(s), {} paise", result.getPickupCode(), result.getDocumentCount(),
                result.getAmountPaise());
        return view(reload(orderId));
    }

    /** Runs inside the order's lock. Throws (and saves nothing) if any document has a problem. */
    private void priceDocuments(PrintOrder o, List<OrderDocument> docs, List<DocumentChoice> choices) {
        Map<UUID, OrderDocument> byId = new LinkedHashMap<>();
        for (OrderDocument d : docs) byId.put(d.getId(), d);

        List<Map<String, Object>> problems = new ArrayList<>();
        for (OrderDocument d : docs) {
            if (d.getStatus() == DocumentStatus.UPLOADING) {
                throw ApiException.conflict("STILL_UPLOADING",
                        "\"" + d.getFileName() + "\" is still uploading. Wait until every file shows Ready.");
            }
            if (d.getStatus() == DocumentStatus.REJECTED) {
                problems.add(problem(d, d.getErrorMessage() + " Remove it to continue."));
            }
        }
        if (!problems.isEmpty()) throw settingsProblems(problems);
        Set<UUID> seen = new HashSet<>();
        for (DocumentChoice c : choices) {
            if (c == null || c.id() == null || !byId.containsKey(c.id()) || !seen.add(c.id())) {
                throw ApiException.conflict("DOCUMENTS_CHANGED",
                        "Your list of files changed. Refresh the page and try again.");
            }
        }
        long ready = docs.stream().filter(d -> d.getStatus() == DocumentStatus.READY).count();
        if (seen.size() != ready) {
            throw ApiException.conflict("DOCUMENTS_CHANGED", "Your list of files changed. Refresh the page and try again.");
        }

        ShopSettings shop = settings.current();
        List<PrinterRules.Candidate> candidates = printers.findByEnabledTrueOrderByName().stream()
                .map(Printer::candidate).toList();
        SettingsChecker.Limits lim = new SettingsChecker.Limits(limits.maxCopies(), limits.maxPages());

        long total = 0;
        int position = 0;
        List<Runnable> apply = new ArrayList<>();
        for (DocumentChoice c : choices) {
            OrderDocument d = byId.get(c.id());
            if (d.getStatus() != DocumentStatus.READY) continue;
            int pos = ++position;
            try {
                SettingsChecker.Checked checked = SettingsChecker.check(c.settings(),
                        new SettingsChecker.Facts(d.getFileType(), d.getPageCount()), candidates, lim);
                Pricing.Quote q = Pricing.quote(checked.settings(), checked.plan(), shop.getPriceBwPaise(),
                        shop.getPriceColorPaise(), shop.getPricing());
                total += q.amountPaise();
                apply.add(() -> {
                    d.setPosition(pos);
                    d.setSettings(checked.settings());
                    d.setRequirements(checked.requirements());
                    d.setLegacyOk(checked.legacyOk());
                    d.setPrintPages(checked.plan().printPages());
                    d.setSides(checked.plan().sides());
                    d.setSheets(checked.plan().sheets());
                    d.setAmountPaise((int) q.amountPaise());
                });
            } catch (SettingsChecker.Problem e) {
                problems.add(problem(d, e.getMessage()));
            }
        }
        if (!problems.isEmpty()) throw settingsProblems(problems);
        if (total > MAX_ORDER_PAISE) {
            throw ApiException.badRequest("TOO_EXPENSIVE", "This order is too large. Print fewer copies or split it.");
        }
        if (total > 0 && total < RAZORPAY_MINIMUM_PAISE && !(gateway instanceof DemoGateway)) {
            total = RAZORPAY_MINIMUM_PAISE;        // online payments start at Rs 1
        }
        apply.forEach(Runnable::run);
        documents.saveAll(byId.values());
        o.setAmountPaise((int) total);
        o.setDocumentCount(position);
    }

    private static ApiException settingsProblems(List<Map<String, Object>> problems) {
        Map<String, Object> first = problems.get(0);
        return new ApiException(HttpStatus.BAD_REQUEST, "CHECK_SETTINGS",
                problems.size() == 1 ? first.get("fileName") + ": " + first.get("message")
                        : problems.size() + " files need a change before you can pay.",
                Map.of("documents", problems));
    }

    private static Map<String, Object> problem(OrderDocument d, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("fileName", d.getFileName());
        m.put("message", message);
        return m;
    }

    /** Back from the summary to change something. Only before a payment was started. */
    public OrderView edit(UUID orderId, String key) {
        PrintOrder o = owned(orderId, key);
        if (o.getStatus() == OrderStatus.AWAITING_UPLOAD) return view(o);
        if (orders.backToEditing(orderId) == 0) {
            throw ApiException.conflict("CANNOT_EDIT", o.getStatus().isPaid() ? "This order is already paid."
                    : "Payment was already started for this order, so it cannot be changed. Cancel it and start again.");
        }
        return view(reload(orderId));
    }

    // ------------------------------------------------------------------ one-file apps

    /** One-file order (Android): the upload finished: check the file and price it in one go. */
    public OrderView confirmUpload(UUID id, String key) {
        PrintOrder o = owned(id, key);
        if (o.getStatus() != OrderStatus.AWAITING_UPLOAD) {
            if (o.getStatus() == OrderStatus.AWAITING_PAYMENT || o.getStatus().isPaid()) {
                return view(o);          // a repeated request is harmless
            }
            throw ApiException.conflict("BAD_STATE", "This order can no longer be changed. Start a new one.");
        }
        List<OrderDocument> docs = documents.findByOrderIdOrderByPosition(id);
        if (docs.size() != 1) {
            throw ApiException.conflict("BAD_STATE", "This order has several files. Use the website to pay for it.");
        }
        OrderDocument d = docs.get(0);
        DocumentView dv = documentUploaded(id, key, d.getId());
        if ("REJECTED".equals(dv.status())) {
            String code = documents.findById(d.getId()).map(OrderDocument::getErrorCode).orElse("BAD_FILE");
            failOrder(id, d, code, dv.problem());
            throw ApiException.badRequest(code, dv.problem());
        }
        JsonNode choices = JSON.valueToTree(d.getSettings() == null ? PrintSettings.defaults() : d.getSettings());
        try {
            return review(id, key, new ReviewRequest(List.of(new DocumentChoice(d.getId(), choices))));
        } catch (ApiException e) {
            if ("CHECK_SETTINGS".equals(e.code())) {
                String message = messageOf(e);
                String code = message.toLowerCase().contains("page") ? "BAD_PAGES" : "BAD_SETTINGS";
                failOrder(id, d, code, message);
                throw ApiException.badRequest(code, message);
            }
            throw e;
        }
    }

    /** One-file order whose file or choices cannot be printed: FAILED before payment, as before. */
    private void failOrder(UUID orderId, OrderDocument d, String code, String message) {
        tx.executeWithoutResult(t -> {
            PrintOrder o = orders.lockById(orderId).orElseThrow();
            if (o.getStatus() != OrderStatus.AWAITING_UPLOAD) return;
            o.setStatus(OrderStatus.FAILED);
            o.setErrorCode(code);
            o.setErrorMessage(message);
            orders.save(o);
            documents.cancelDraftDocuments(orderId);
        });
        documents.findById(d.getId()).ifPresent(this::deleteFile);
    }

    @SuppressWarnings("unchecked")
    private static String messageOf(ApiException e) {
        Object list = e.details() == null ? null : e.details().get("documents");
        if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            return String.valueOf(((Map<String, Object>) m).get("message"));
        }
        return e.getMessage();
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

    /** Only before payment. */
    public OrderView cancel(UUID id, String key) {
        PrintOrder o = owned(id, key);
        Boolean done = tx.execute(t -> {
            if (orders.cancelUnpaid(o.getId()) == 0) return false;
            documents.cancelDraftDocuments(o.getId());
            return true;
        });
        if (!Boolean.TRUE.equals(done)) {
            throw ApiException.conflict("CANNOT_CANCEL", reload(id).getStatus().isPaid()
                    ? "This order is already paid. Please ask at the Xerox counter."
                    : "This order can no longer be cancelled.");
        }
        for (OrderDocument d : documents.findByOrderIdOrderByPosition(id)) deleteFile(d);
        return view(reload(id));
    }

    // ------------------------------------------------------------------ views

    public OrderView view(PrintOrder o) {
        List<OrderDocument> docs = documents.findByOrderIdOrderByPosition(o.getId());
        Map<UUID, String> printerNames = new HashMap<>();
        if (docs.stream().anyMatch(d -> d.getPrinterId() != null)) {
            printers.findAll().forEach(p -> printerNames.put(p.getId(), p.getName()));
        }
        List<DocumentView> views = docs.stream().map(d -> documentView(d, printerNames)).toList();
        List<OrderDocument> live = docs.stream().filter(d -> d.getStatus() != DocumentStatus.CANCELLED).toList();
        int done = (int) live.stream().filter(d -> d.getStatus() == DocumentStatus.COMPLETED).count();
        boolean anyFailed = live.stream().anyMatch(d -> d.getStatus() == DocumentStatus.FAILED);
        Integer refundDue = o.getPaidAt() == null ? null : docs.stream()
                .filter(d -> d.getStatus() == DocumentStatus.CANCELLED && d.getAmountPaise() != null)
                .mapToInt(OrderDocument::getAmountPaise).sum();
        if (refundDue != null && refundDue == 0) refundDue = null;
        Integer sheets = live.stream().allMatch(d -> d.getSheets() != null && d.getSettings() != null) && !live.isEmpty()
                ? live.stream().mapToInt(d -> d.getSheets() * d.getSettings().copies()).sum() : null;

        Integer ahead = (o.getStatus() == OrderStatus.QUEUED && o.getPaidAt() != null)
                ? (int) orders.countQueuedBefore(o.getPaidAt()) : null;

        String stage;
        String message = null;
        int count = live.size();
        switch (o.getStatus()) {
            case AWAITING_UPLOAD -> stage = "Adding files";
            case AWAITING_PAYMENT -> stage = "Waiting for payment";
            case QUEUED -> {
                stage = "Paid – waiting for a printer";
                if (ahead != null && ahead > 0) message = ahead + (ahead == 1 ? " order" : " orders") + " ahead of you.";
            }
            case PRINTING -> {
                stage = count > 1 ? "Printing – " + done + " of " + count + " files done" : "Printing now";
                if (anyFailed) {
                    message = "One file had a problem. Your payment is safe: show pickup code " + o.getPickupCode()
                            + " at the counter.";
                }
            }
            case COMPLETED -> {
                stage = o.getCollectedAt() != null ? "Collected" : "Ready – collect at the counter";
                if (o.getCollectedAt() == null) {
                    message = "Show pickup code " + o.getPickupCode() + " at the Xerox counter. The same code is "
                            + "printed small at the bottom of the first page of each file, so staff can find your pages.";
                }
            }
            case FAILED -> {
                stage = o.getPaidAt() != null ? "Problem – please visit the counter" : "Could not use this file";
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
        if (refundDue != null && o.getStatus() != OrderStatus.CANCELLED) {
            message = (message == null ? "" : message + " ") + "A refund of " + rupees(refundDue)
                    + " is due for a file cancelled at the counter.";
        }

        OrderDocument first = docs.isEmpty() ? null : docs.get(0);
        PrintSettings fs = first == null ? null : first.getSettings();
        String printerName = first == null || first.getPrinterId() == null ? null : printerNames.get(first.getPrinterId());
        return new OrderView(o.getId(), o.getPickupCode(), o.getStatus().name(), stage, message,
                first == null ? null : first.getFileName(),
                first == null ? null : first.getFileType().name(),
                first == null ? null : first.getPageCount(),
                fs == null ? null : fs.pages(),
                first == null ? null : first.getPrintPages(),
                fs == null ? 1 : fs.copies(),
                fs != null && fs.color(),
                o.getAmountPaise(), o.getCurrency(), printerName, ahead, o.getCollectedAt() != null,
                o.getCreatedAt(), o.getPaidAt(), o.getCompletedAt(),
                views, o.getStatus() == OrderStatus.AWAITING_UPLOAD
                        || (o.getStatus() == OrderStatus.AWAITING_PAYMENT && o.getGatewayOrderId() == null),
                done, sheets, refundDue);
    }

    public DocumentView documentView(OrderDocument d, Map<UUID, String> printerNames) {
        String printer = d.getPrinterId() == null || printerNames == null ? null : printerNames.get(d.getPrinterId());
        String stage = switch (d.getStatus()) {
            case UPLOADING -> "Uploading";
            case READY -> "Ready";
            case REJECTED -> "Cannot be printed";
            case QUEUED -> "Waiting for a printer";
            case CLAIMED, DOWNLOADING -> "Getting ready to print";
            case SUBMITTED -> printer == null ? "Printing now" : "Printing on " + printer;
            case COMPLETED -> printer == null ? "Printed" : "Printed on " + printer;
            case FAILED -> "Problem – ask at the counter";
            case CANCELLED -> "CANCELLED_AT_COUNTER".equals(d.getErrorCode())
                    ? "Cancelled at the counter - refund due" : "Cancelled";
        };
        String problem = d.getStatus() == DocumentStatus.REJECTED ? d.getErrorMessage() : null;
        return new DocumentView(d.getId(), d.getPosition(), d.getFileName(), d.getFileType().name(),
                d.getFileSizeBytes(), d.getStatus().name(), stage, problem, d.getPageCount(), d.getImageInfo(),
                d.getRequirements() == null ? null : d.getSettings(),
                pagesText(d), d.getPrintPages(), d.getSides(), d.getSheets(), d.getAmountPaise(), printer);
    }

    /** "3, 7, 10–12", "All 24 pages", "1 page" or "Picture". */
    static String pagesText(OrderDocument d) {
        if (d.getFileType() != FileType.PDF) return "Picture";
        Integer total = d.getPageCount();
        PrintSettings s = d.getSettings();
        if (s != null && s.pages() != null) return s.pages().replace(",", ", ").replace("-", "–");
        if (total == null) return null;
        return total == 1 ? "1 page" : "All " + total + " pages";
    }

    // ------------------------------------------------------------------ helpers

    private record Inspection(FileInspector.Result result, long size, String problemCode, String problem) {}

    /** Reads the uploaded file (bounded) and checks it. At most a few at a time: each is held in memory. */
    private Inspection inspect(OrderDocument d) {
        boolean got;
        try {
            got = checking.tryAcquire(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            got = false;
        }
        if (!got) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BUSY",
                    "Many files are being checked right now. Try again in a moment.");
        }
        try {
            int readLimit = (int) Math.min(Integer.MAX_VALUE - 16L, limits.maxFileSizeBytes() + 1);
            SupabaseStorage.Probe probe = storage.probe(d.getStoragePath(), readLimit)
                    .orElseThrow(() -> ApiException.badRequest("UPLOAD_MISSING",
                            "The file did not arrive. Check your internet and try again."));
            if (probe.totalSize() > limits.maxFileSizeBytes()) {
                return new Inspection(null, probe.totalSize(), "FILE_TOO_LARGE",
                        "Files must be smaller than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB.");
            }
            FileInspector.Result r = inspector.inspect(probe.prefix());
            if (!r.ok()) return new Inspection(r, probe.totalSize(), r.problemCode(), r.problem());
            if (r.pages() > limits.maxFilePages()) {
                return new Inspection(r, probe.totalSize(), "TOO_MANY_PAGES",
                        "This file has " + r.pages() + " pages. Files can have up to " + limits.maxFilePages() + ".");
            }
            return new Inspection(r, probe.totalSize(), null, null);
        } finally {
            checking.release();
        }
    }

    private void deleteFile(OrderDocument d) {
        if (d.isFileDeleted()) return;
        storage.delete(d.getStoragePath());
        documents.markFileDeleted(d.getId());
    }

    private PrintOrder owned(UUID id, String key) {
        PrintOrder o = orders.findById(id).orElse(null);
        if (o == null || key == null || !Secrets.sameText(o.getAccessKeyHash(), Secrets.sha256Hex(key.trim()))) {
            throw ApiException.notFound("That order");
        }
        return o;
    }

    private OrderDocument ownedDocument(PrintOrder o, UUID documentId) {
        return documents.findByIdAndOrderId(documentId, o.getId())
                .orElseThrow(() -> ApiException.notFound("That file"));
    }

    private static void requireDraft(PrintOrder o) {
        if (o.getStatus() == OrderStatus.AWAITING_UPLOAD) return;
        if (o.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.conflict("PRICED", "This order was already priced. Go back to editing to change files.");
        }
        throw ApiException.conflict("BAD_STATE", o.getStatus().isPaid() ? "This order is already paid."
                : "This order can no longer be changed. Start a new one.");
    }

    private PrintOrder reload(UUID id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
    }

    private void checkSize(long size) {
        if (size < 0) throw ApiException.badRequest("BAD_FILE", "The file size is not valid.");
        if (size > limits.maxFileSizeBytes()) {
            throw ApiException.badRequest("FILE_TOO_LARGE",
                    "Files must be smaller than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB.");
        }
    }

    private static FileType requireType(FileType t) {
        if (t == null) throw ApiException.badRequest("NOT_SUPPORTED", "Only PDF, PNG and JPG files can be printed.");
        return t;
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

    static String rupees(int paise) {
        return "₹" + (paise % 100 == 0 ? String.valueOf(paise / 100) : String.format("%.2f", paise / 100.0));
    }

    /** Keeps only the file name part, without folders or control characters. */
    static String cleanName(String name) {
        String base = name.replaceAll(".*[/\\\\]", "").replaceAll("\\p{Cntrl}", "").trim();
        if (base.isEmpty()) base = "document";
        return base.length() > 150 ? base.substring(0, 150) : base;
    }
}
