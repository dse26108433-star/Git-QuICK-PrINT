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
import edu.campus.print.payment.UpiGateway;
import edu.campus.print.payment.UpiLedger;
import edu.campus.print.printing.Finishing;
import edu.campus.print.printing.PaperSize;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.printing.Pricing;
import edu.campus.print.printing.PrinterRules;
import edu.campus.print.printing.SettingsChecker;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.repo.PreviewStore;
import edu.campus.print.repo.PrinterRepository;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.staff.StaffService;
import edu.campus.print.storage.FileInspector;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The student's journey:
 *
 *   create()            start an order (a draft) -> order number + private key
 *   addDocument()       one per file -> an upload link (files go straight to storage)
 *   documentUploaded()  file arrived: check what it really is, count its pages
 *   review()            the student's settings for every document: checked against the
 *                       real files and the real printers, priced -> AWAITING_PAYMENT
 *   startPayment()      open the payment screen: XeoGo Pay (direct UPI), Razorpay or demo
 *   confirmPayment()    payment verified -> QUEUED -> the Xerox PC prints each document
 *   claimPayment()      XeoGo Pay: "I have paid" (+ UPI reference); paid once a bank message or staff confirm it
 *   get()               status, times and the files, polled by the app
 *   arrive()            "I am at the counter": the order shows on the staff screen with pictures of
 *                       its files; staff hand the pages over. No pickup code is shown or typed.
 *
 * One-file apps (Android) still send the file and choices with create() and
 * then call confirmUpload(), which checks and prices in one go.
 *
 * There is no login for students. Each order has a random access key that
 * only the student's device holds; without it an order cannot be seen or paid.
 *
 * College staff print for free, signed in with a staff ID (StaffService). Their
 * orders take the same steps, except the payment: review() counts the pages
 * instead of pricing them, and staffPrint() sends the order to the printers if
 * the staff member's free pages for the month cover it. A staff order answers
 * to its staff ID, not to a key (see owned()).
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int RAZORPAY_MINIMUM_PAISE = 100;
    private static final long MAX_ORDER_PAISE = 10_000_000;
    /** How long "I am at the counter" counts; the phone repeats it while that screen is open. */
    public static final Duration AT_COUNTER = Duration.ofMinutes(3);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final PrinterRepository printers;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final FileInspector inspector;
    private final PaymentGateway gateway;
    private final UpiLedger upi;
    private final ShopProperties limits;
    private final AgentProperties agentProps;
    private final PreviewStore previews;
    private final ReadyEstimate estimate;
    private final StaffService staff;
    private final WordFiles word;
    private final TransactionTemplate tx;
    /**
     * Uploaded files are read into memory to be checked. This is the memory for that, in megabytes: a file
     * takes as many as it is big (and never less than a fair share), so one huge file and many small ones
     * cannot fill the memory together. Whoever does not fit waits.
     */
    private final Semaphore checking;
    private final int checkBudgetMb;
    private final int checkShareMb;
    /** The shop as students see it, kept for a moment: every open page asks for it, and it rarely changes. */
    private volatile ShopView shopCache;
    private volatile long shopCacheAt;
    /** The printers, kept for the same moment: every "how is my order" needs their names. */
    private volatile List<Printer> printersCache;
    private volatile long printersCacheAt;
    /** How long "how is my order" takes lately, in milliseconds (a running average): see pollSeconds(). */
    private volatile double answerMillis = 20;

    public OrderService(OrderRepository orders, OrderDocumentRepository documents, PrinterRepository printers,
                        ShopSettingsRepository settings, SupabaseStorage storage, FileInspector inspector,
                        PaymentGateway gateway, UpiLedger upi, ShopProperties limits, AgentProperties agentProps,
                        PreviewStore previews, ReadyEstimate estimate, StaffService staff, WordFiles word,
                        PlatformTransactionManager txManager) {
        this.orders = orders;
        this.documents = documents;
        this.printers = printers;
        this.settings = settings;
        this.storage = storage;
        this.inspector = inspector;
        this.gateway = gateway;
        this.upi = upi;
        this.limits = limits;
        this.agentProps = agentProps;
        this.previews = previews;
        this.estimate = estimate;
        this.staff = staff;
        this.word = word;
        this.tx = new TransactionTemplate(txManager);
        this.checkBudgetMb = limits.checkMemoryMb();
        this.checkShareMb = Math.max(1, checkBudgetMb / limits.maxParallelChecks());
        this.checking = new Semaphore(checkBudgetMb, true);
    }

    // ------------------------------------------------------------------ shop

    public ShopView shop() {
        ShopView cached = shopCache;
        long ttl = limits.shopCacheMillis();
        if (cached != null && ttl > 0 && System.currentTimeMillis() - shopCacheAt < ttl) return cached;
        ShopView fresh = readShop();
        shopCache = fresh;
        shopCacheAt = System.currentTimeMillis();
        return fresh;
    }

    private ShopView readShop() {
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
                new Printing(options, paper, finishing, media, s.getPricing(), PrintSettings.defaults()),
                word.available());
    }

    // ------------------------------------------------------------------ create

    /** who: a signed-in staff member makes a staff order (free, counted against their month); else an ordinary one. */
    public CreateOrderResponse create(CreateOrderRequest req, Caller who) {
        PrintOrder o = PrintOrder.create();
        o.setPickupCode(uniquePickupCode());
        String accessKey = Secrets.newKey();
        o.setAccessKeyHash(Secrets.sha256Hex(accessKey));
        o.setCurrency(settings.current().getCurrency());
        if (who != null && who.staff() != null) o.setStaffId(who.staff().getId());

        if (req == null || !req.singleFile()) {
            orders.save(o);
            log.info("Order {} started{}", o.getPickupCode(), o.getStaffId() == null ? "" : " (staff)");
            return new CreateOrderResponse(o.getId(), accessKey, o.getPickupCode(), null, null, null);
        }

        // One-file app: the file and the choices come now.
        int copies = req.copies() == null ? 1 : req.copies();
        if (copies < 1 || copies > limits.maxCopies()) {
            throw ApiException.badRequest("BAD_COPIES", "Choose between 1 and " + limits.maxCopies() + " copies.");
        }
        boolean color = Boolean.TRUE.equals(req.color());
        FileType type = requireType(req.fileType());
        if (type == FileType.DOCX) {
            throw ApiException.badRequest("NOT_SUPPORTED", "Update the app to print Word files, or save the file as PDF.");
        }
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
    public UploadTicket addDocument(UUID orderId, Caller who, AddDocumentRequest req) {
        owned(orderId, who);
        if (req == null || req.fileName() == null || req.fileName().isBlank()) {
            throw ApiException.badRequest("BAD_FILE", "The file has no name.");
        }
        FileType type = requireType(req.fileType());
        checkSize(req.fileSizeBytes());
        if (type == FileType.DOCX && !word.available()) {      // said before the upload, not after it
            throw ApiException.conflict(WordFiles.NOT_AVAILABLE, WordFiles.NOT_AVAILABLE_TEXT);
        }

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
    public UploadTicket uploadUrl(UUID orderId, Caller who, UUID documentId) {
        PrintOrder o = owned(orderId, who);
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
     * A Word file that passed the check waits (CONVERTING) for the Xerox PC to
     * turn it into a PDF; the device asks again until it is READY.
     */
    public DocumentView documentUploaded(UUID orderId, Caller who, UUID documentId) {
        PrintOrder o = owned(orderId, who);
        OrderDocument d = ownedDocument(o, documentId);
        if (d.getStatus() != DocumentStatus.UPLOADING) {
            return documentView(d, null);            // a repeated request is harmless
        }
        requireDraft(o);
        Inspection first = inspect(d);
        boolean isWord = first.problemCode == null && first.result.type() == FileType.DOCX;
        // whatever the file was called: a Word file needs a Xerox PC with Word, now
        Inspection ins = isWord && !word.available()
                ? new Inspection(first.result, first.size, WordFiles.NOT_AVAILABLE, WordFiles.NOT_AVAILABLE_TEXT) : first;

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
            } else if (isWord) {
                doc.setStatus(DocumentStatus.CONVERTING);
                doc.setFileType(FileType.DOCX);
                doc.setSha256(ins.result.sha256());       // the Xerox PC opens exactly this file, or nothing
                doc.setFileSizeBytes(ins.size);
                doc.setConvertRequestedAt(Instant.now());
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
    public OrderView removeDocument(UUID orderId, Caller who, UUID documentId) {
        owned(orderId, who);
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
    public FileUrl fileUrl(UUID orderId, Caller who, UUID documentId) {
        PrintOrder o = owned(orderId, who);
        OrderDocument d = ownedDocument(o, documentId);
        if (d.isFileDeleted() || d.getStatus() == DocumentStatus.UPLOADING || d.getStatus() == DocumentStatus.CONVERTING
                || d.getStatus() == DocumentStatus.REJECTED) {
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
    public OrderView review(UUID orderId, Caller who, ReviewRequest req) {
        owned(orderId, who);
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
        if (result.getStaffId() != null) {
            // A staff order waits for "Print" (staffPrint), where the month's free pages are checked.
            log.info("Order {} reviewed: {} document(s), {} free staff page(s)", result.getPickupCode(),
                    result.getDocumentCount(), result.getStaffPages());
            return view(reload(orderId));
        }
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

        // A staff order: nothing is paid; the printed sides are counted against the staff member's month instead.
        boolean free = o.getStaffId() != null;
        List<Map<String, Object>> problems = new ArrayList<>();
        for (OrderDocument d : docs) {
            if (d.getStatus() == DocumentStatus.UPLOADING) {
                throw ApiException.conflict("STILL_UPLOADING",
                        "\"" + d.getFileName() + "\" is still uploading. Wait until every file shows Ready.");
            }
            if (d.getStatus() == DocumentStatus.CONVERTING) {
                throw ApiException.conflict("STILL_UPLOADING",
                        "\"" + d.getFileName() + "\" is still being prepared. Wait until every file shows Ready.");
            }
            if (d.getStatus() == DocumentStatus.REJECTED) {
                problems.add(problem(d, d.getErrorMessage() + " Remove it to continue."));
            }
        }
        if (!problems.isEmpty()) throw settingsProblems(problems, free);
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
        int freePages = 0;
        int position = 0;
        List<Runnable> apply = new ArrayList<>();
        for (DocumentChoice c : choices) {
            OrderDocument d = byId.get(c.id());
            if (d.getStatus() != DocumentStatus.READY) continue;
            int pos = ++position;
            try {
                SettingsChecker.Checked checked = SettingsChecker.check(c.settings(),
                        new SettingsChecker.Facts(d.getFileType(), d.getPageCount()), candidates, lim);
                if (free && !shop.isStaffColor()) {
                    if (checked.settings().color()) {
                        throw new SettingsChecker.Problem("Free staff printing is black & white here. Choose B/W.");
                    }
                    if (checked.settings().mediaType() != null) {
                        throw new SettingsChecker.Problem("Free staff printing is on plain paper here. Choose the usual paper.");
                    }
                }
                Pricing.Quote q = free ? new Pricing.Quote(0, 0, 0)
                        : Pricing.quote(checked.settings(), checked.plan(), shop.getPriceBwPaise(),
                                shop.getPriceColorPaise(), shop.getPricing());
                total += q.amountPaise();
                freePages += checked.plan().sides() * checked.settings().copies();
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
        if (!problems.isEmpty()) throw settingsProblems(problems, free);
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
        o.setStaffPages(free ? freePages : null);
    }

    private static ApiException settingsProblems(List<Map<String, Object>> problems, boolean free) {
        Map<String, Object> first = problems.get(0);
        return new ApiException(HttpStatus.BAD_REQUEST, "CHECK_SETTINGS",
                problems.size() == 1 ? first.get("fileName") + ": " + first.get("message")
                        : problems.size() + " files need a change before you can " + (free ? "print." : "pay."),
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
    public OrderView edit(UUID orderId, Caller who) {
        PrintOrder o = owned(orderId, who);
        if (o.getStatus() == OrderStatus.AWAITING_UPLOAD) return view(o);
        if (orders.backToEditing(orderId) == 0) {
            throw ApiException.conflict("CANNOT_EDIT", o.getStatus().isPaid() ? "This order is already paid."
                    : "Payment was already started for this order, so it cannot be changed. Cancel it and start again.");
        }
        return view(reload(orderId));
    }

    // ------------------------------------------------------------------ one-file apps

    /** One-file order (Android): the upload finished: check the file and price it in one go. */
    public OrderView confirmUpload(UUID id, Caller who) {
        PrintOrder o = owned(id, who);
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
        DocumentView dv = documentUploaded(id, who, d.getId());
        if ("REJECTED".equals(dv.status())) {
            String code = documents.findById(d.getId()).map(OrderDocument::getErrorCode).orElse("BAD_FILE");
            failOrder(id, d, code, dv.problem());
            throw ApiException.badRequest(code, dv.problem());
        }
        JsonNode choices = JSON.valueToTree(d.getSettings() == null ? PrintSettings.defaults() : d.getSettings());
        try {
            return review(id, who, new ReviewRequest(List.of(new DocumentChoice(d.getId(), choices))));
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

    /** A staff order is never paid for, in any way: it is sent with staffPrint(). (The database refuses it too.) */
    private static void requirePaying(PrintOrder o) {
        if (o.getStaffId() != null) {
            throw ApiException.conflict("STAFF_ORDER", "This is a free staff order: there is nothing to pay. Press Print.");
        }
    }

    public PaymentGateway.Checkout startPayment(UUID id, Caller who) {
        PrintOrder o = owned(id, who);
        requirePaying(o);
        if (o.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT",
                    o.getStatus().isPaid() ? "This order is already paid." : "This order cannot be paid now.");
        }
        String centerName = settings.current().getCenterName();
        boolean hadGatewayOrder = o.getGatewayOrderId() != null;
        PaymentGateway.Checkout checkout = gateway.start(o, centerName);
        if (!hadGatewayOrder && o.getGatewayOrderId() != null && !gateway.savesCheckout()
                && orders.setGatewayOrder(o.getId(), o.getGatewayOrderId(), gateway.name()) == 0) {
            // A second click got there first: use the Razorpay order it created.
            checkout = gateway.start(reload(id), centerName);
        }
        return checkout;
    }

    public OrderView confirmPayment(UUID id, Caller who, ConfirmPaymentRequest req) {
        PrintOrder o = owned(id, who);
        requirePaying(o);
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

    /**
     * XeoGo Pay: the student says "I have paid", with the 12-digit UPI
     * reference number from their UPI app if they have it. This proves
     * nothing by itself: the order is paid when a bank message shows the money
     * arrived (possibly one that came in already), or when staff confirm it.
     */
    public OrderView claimPayment(UUID id, Caller who, ClaimPaymentRequest req) {
        PrintOrder o = owned(id, who);
        requirePaying(o);
        if (!(gateway instanceof UpiGateway)) {
            throw ApiException.conflict("NOT_UPI", "This shop does not take direct UPI payments.");
        }
        if (o.getStatus().isPaid()) {
            return view(o);
        }
        String ref = upiReference(req == null ? null : req.reference());
        if (ref == null && gateway instanceof UpiGateway g && g.autoConfirm()) {
            // Payments confirm by themselves here: "I have paid" alone would only fill the counter's list.
            throw ApiException.badRequest("REFERENCE_NEEDED", "Type the 12-digit UPI reference number from your UPI "
                    + "app's receipt (UTR / UPI Ref No.), so your payment can be found.");
        }
        String result = upi.claim(id, ref);
        switch (result) {
            case "OK" -> { }
            case "REF_USED" -> throw ApiException.conflict("REFERENCE_USED",
                    "This UPI reference number was already used for another order. Check the number in your UPI app.");
            case "NOT_STARTED" -> throw ApiException.conflict("NOT_STARTED", "Open the payment screen first.");
            default -> {
                PrintOrder now = reload(id);
                if (now.getStatus().isPaid()) return view(now);
                throw ApiException.conflict("NOT_AWAITING_PAYMENT", "This order cannot be paid now.");
            }
        }
        log.info("Order {}: student says paid via UPI{}", o.getPickupCode(), ref == null ? "" : " (ref " + ref + ")");
        if (ref != null) reconcile(reload(id));
        return view(reload(id));
    }

    /** A UPI reference number (UTR) is 12 digits. Spaces and dashes are fine; anything else is refused. */
    static String upiReference(String typed) {
        if (typed == null || typed.isBlank()) return null;
        String digits = typed.replaceAll("[\\s-]", "");
        if (!digits.matches("[0-9]{12}")) {
            throw ApiException.badRequest("BAD_REFERENCE",
                    "The UPI reference number has 12 digits (it may be called UTR, UPI Ref No. or Transaction ID). "
                            + "You can also leave it empty.");
        }
        return digits;
    }

    public OrderView demoPay(UUID id, Caller who) {
        if (!(gateway instanceof DemoGateway)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_DEMO", "Demo payment is switched off.");
        }
        PrintOrder o = owned(id, who);
        requirePaying(o);
        if (o.getStatus().isPaid()) {
            return view(o);
        }
        if (orders.markPaid(o.getId(), "demo", "demo_" + Secrets.newKey().substring(0, 12)) == 0) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT", "This order cannot be paid now.");
        }
        log.info("Order {} marked paid (DEMO)", o.getPickupCode());
        return view(reload(id));
    }

    /**
     * "Print" on a staff order: free, counted against the staff member's pages
     * for the month. The check and the sending are one database step
     * (staff_print in db/setup.sql), so two presses, two devices or two orders
     * at the same moment cannot go over the limit or print twice.
     */
    public OrderView staffPrint(UUID id, Caller who) {
        PrintOrder o = owned(id, who);
        if (o.getStaffId() == null || who.staff() == null) {
            throw ApiException.conflict("NOT_STAFF_ORDER", "This order was not made with a staff ID: it has to be paid for.");
        }
        StaffService.PrintResult r = staff.print(id, who.staff());
        switch (r.result()) {
            case "OK" -> log.info("Order {} sent to print by staff {}: {} free page(s), {} of {} used this month",
                    o.getPickupCode(), who.staff().getUsername(), r.pages(), r.used(), r.limit());
            case "ALREADY" -> { }                              // a second press: it is printing already
            case "OVER" -> {
                int left = Math.max(0, r.limit() - r.used());
                StaffService.Month m = staff.month();
                throw new ApiException(HttpStatus.CONFLICT, "OVER_LIMIT", "This order takes "
                        + r.pages() + (r.pages() == 1 ? " page" : " pages") + ", but "
                        + (left == 0 ? "none" : "only " + left) + " of your " + r.limit() + " free pages "
                        + (left == 1 ? "is" : "are") + " left for " + m.label() + ". Print fewer pages or copies, "
                        + "or wait until " + DAY.format(m.next()) + ".",
                        Map.of("pages", r.pages(), "used", r.used(), "limit", r.limit(), "left", left,
                                "resetsOn", m.next().toString()));
            }
            case "COLOR" -> throw ApiException.conflict("STAFF_NO_COLOR",
                    "Free staff printing is black & white on plain paper here. Go back and change that file.");
            case "OFF" -> throw new ApiException(HttpStatus.FORBIDDEN, "STAFF_OFF",
                    "This staff ID is switched off. Ask at the Xerox center.");
            case "NOT_FOUND" -> throw ApiException.notFound("That order");
            default -> throw ApiException.conflict("NOT_READY",
                    reload(id).getStatus() == OrderStatus.AWAITING_UPLOAD ? "Review this order first."
                            : "This order can no longer be printed. Start a new one.");
        }
        return view(reload(id));
    }

    /** Asks the gateway if an unpaid order was actually paid. Used by the app poll and the background job. */
    public void reconcile(PrintOrder o) {
        if (gateway instanceof DemoGateway || o.getGatewayOrderId() == null) {
            return;
        }
        if (gateway instanceof UpiGateway && o.getPaymentClaimRef() == null) {
            return;       // XeoGo Pay: bank messages pay orders as they arrive; only a typed reference is looked up
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

    public OrderView get(UUID id, Caller who) {
        long started = System.nanoTime();
        try {
            PrintOrder o = owned(id, who);
            if (o.getStatus() == OrderStatus.AWAITING_PAYMENT && o.getGatewayOrderId() != null
                    && (o.getPaymentCheckedAt() == null
                        || o.getPaymentCheckedAt().isBefore(Instant.now().minusSeconds(10)))) {
                reconcile(o);
                o = reload(id);
            }
            return view(o);
        } finally {
            // every open "your prints" screen asks this again and again: how long it takes says how busy the server is
            answerMillis = answerMillis * 0.9 + (System.nanoTime() - started) / 1e6 * 0.1;
        }
    }

    /**
     * How soon a device should ask about an order again, in seconds. Soon
     * while something is about to change (paying, printing, standing at the
     * counter); seldom while nothing can (ready, on the way to the counter).
     * When the server takes long to answer, everybody is told to ask less
     * often: a rush of customers makes the screens a little slower to update,
     * not the service slower to answer. 0: nothing can change any more.
     */
    int pollSeconds(PrintOrder o) {
        int seconds = switch (o.getStatus()) {
            case AWAITING_UPLOAD -> 2;                                    // a Word file being turned into pages
            case AWAITING_PAYMENT -> 3;
            case QUEUED -> 6;
            case PRINTING -> 4;
            case COMPLETED -> o.getCollectedAt() != null ? 0 : o.isAtCounter(AT_COUNTER) ? 3 : 12;
            case FAILED -> o.getPaidAt() != null ? 15 : 0;               // staff may print it again
            default -> 0;
        };
        double ms = answerMillis;
        int busy = ms > 1500 ? 6 : ms > 600 ? 4 : ms > 250 ? 2 : 1;
        return seconds == 0 ? 0 : Math.min(60, seconds * busy);
    }

    /** The printers, read at most as often as the shop's details (see shop()). */
    private List<Printer> allPrinters() {
        List<Printer> cached = printersCache;
        long ttl = limits.shopCacheMillis();
        if (cached != null && ttl > 0 && System.currentTimeMillis() - printersCacheAt < ttl) return cached;
        List<Printer> fresh = printers.findAll();
        printersCache = fresh;
        printersCacheAt = System.currentTimeMillis();
        return fresh;
    }

    /**
     * "I am at the counter." The student opened their paid order at the Xerox
     * center: it now shows at the top of the staff screen with a picture of
     * each file, so staff hand over the right pages without anyone showing or
     * typing a code. Only the phone that made the order can say this (it holds
     * the order's private key), so a screenshot or a copy of the same PDF on
     * another phone cannot. here = false takes it back.
     */
    public OrderView arrive(UUID id, Caller who, boolean here) {
        PrintOrder o = owned(id, who);
        if (o.getCollectedAt() != null) return view(o);            // handed over already: nothing to do
        if (orders.markArrived(id, here) == 0 && here) {
            PrintOrder now = reload(id);
            if (now.getCollectedAt() != null) return view(now);
            throw ApiException.conflict("NOT_PAID", now.getStatus() == OrderStatus.CANCELLED
                    ? "This order was cancelled." : "Pay for this order first: then it prints and you can collect it.");
        }
        return view(reload(id));
    }

    /** The picture of one file's first printed sheet (made by the Xerox PC), for the student's own order. */
    public byte[] preview(UUID orderId, Caller who, UUID documentId) {
        PrintOrder o = owned(orderId, who);
        OrderDocument d = ownedDocument(o, documentId);
        return previews.get(d.getId()).orElseThrow(() -> ApiException.notFound("That picture"));
    }

    /** Only before payment. */
    public OrderView cancel(UUID id, Caller who) {
        PrintOrder o = owned(id, who);
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
        boolean printing = o.getStatus() == OrderStatus.QUEUED || o.getStatus() == OrderStatus.PRINTING;
        Instant readyAbout = null;
        if (printing || docs.stream().anyMatch(d -> d.getPrinterId() != null)) {
            List<Printer> all = allPrinters();
            all.forEach(p -> printerNames.put(p.getId(), p.getName()));
            if (printing) {
                int online = (int) all.stream().filter(p -> p.isOnline(agentProps.offlineAfter())).count();
                readyAbout = estimate.forOrder(o, docs, online).orElse(null);
            }
        }
        // Pictures exist only once the Xerox PC printed a file, and go when the order is handed over.
        Set<UUID> withPreview = o.getPaidAt() == null || o.getCollectedAt() != null
                ? Set.of() : previews.documentsWithPreview(List.of(o.getId()));
        List<DocumentView> views = docs.stream().map(d -> documentView(d, printerNames, withPreview)).toList();
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

        boolean free = o.getStaffId() != null;        // a college staff member's order: nothing is paid
        String stage;
        String message = null;
        int count = live.size();
        switch (o.getStatus()) {
            case AWAITING_UPLOAD -> stage = "Adding files";
            case AWAITING_PAYMENT -> {
                if (free) {
                    stage = "Ready to print";
                    message = "Nothing to pay: this comes from your free staff pages. Press Print.";
                } else if (o.isUpiStarted() && o.getPaymentClaimedAt() != null) {
                    stage = "Checking your payment";
                    message = "We are matching your UPI payment of " + rupees(o.getAmountPaise()) + " with the bank"
                            + (o.getPaymentClaimRef() == null ? "" : " (reference " + o.getPaymentClaimRef() + ")")
                            + ". This usually takes a minute. Nothing prints before the money has arrived. "
                            + "Taking long? Show this order and your UPI app's receipt at the counter.";
                } else if (o.isUpiStarted() && o.getPaymentNote() != null) {
                    stage = "Payment not found yet";
                    message = o.getPaymentNote();
                } else {
                    stage = "Waiting for payment";
                    if (o.isUpiStarted()) {
                        message = "Pay " + rupees(o.getAmountPaise()) + " with any UPI app. This page updates by itself.";
                    }
                }
            }
            case QUEUED -> {
                stage = free ? "Sent – waiting for a printer" : "Paid – waiting for a printer";
                if (ahead != null && ahead > 0) message = ahead + (ahead == 1 ? " order" : " orders") + " ahead of you.";
            }
            case PRINTING -> {
                stage = count > 1 ? "Printing – " + done + " of " + count + " files done" : "Printing now";
                if (anyFailed) {
                    message = free ? "One file had a problem. Show this order at the counter."
                            : "One file had a problem. Your payment is safe: show this order at the counter.";
                }
            }
            case COMPLETED -> {
                stage = o.getCollectedAt() != null ? "Collected" : "Ready – collect at the counter";
                if (o.getCollectedAt() == null) {
                    message = "Open this order on your phone at the Xerox counter and show your files. "
                            + "Staff see the same files on their screen and hand you the pages. No code needed.";
                }
            }
            case FAILED -> {
                stage = o.getPaidAt() != null ? "Problem – please visit the counter" : "Could not use this file";
                message = o.getPaidAt() == null ? o.getErrorMessage()
                        : free ? "Show this order at the counter and they will print it again."
                        : "Your payment is safe. Show this order at the counter and they will print it again or refund you.";
            }
            case CANCELLED -> {
                stage = "Cancelled";
                message = free && o.getPaidAt() != null
                        ? "Cancelled at the counter. Its pages do not count against your month." : o.getErrorMessage();
            }
            case EXPIRED -> stage = free ? "Expired – not sent to print" : "Expired - not paid";
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
                done, sheets, refundDue, paymentView(o),
                o.getCollectedAt(), o.isAtCounter(AT_COUNTER) ? o.getArrivedAt() : null, readyAbout, Instant.now(),
                free ? Integer.valueOf(o.getStaffPages() == null ? 0 : o.getStaffPages()) : null,
                pollSeconds(o));
    }

    /** How the order is (being) paid. Null before a payment screen was opened. */
    private static PaymentView paymentView(PrintOrder o) {
        if (o.getPaymentProvider() == null) return null;
        boolean upi = "upi".equals(o.getPaymentProvider());
        return new PaymentView(o.getPaymentProvider(), o.getPaidAt() != null,
                upi ? o.getUpiTagPaise() : null, upi ? o.getGatewayOrderId() : null,
                upi ? o.getPaymentClaimRef() : null, upi ? o.getPaymentClaimedAt() : null,
                upi && o.getPaidAt() == null ? o.getPaymentNote() : null,
                o.getPaidAt() == null ? null : o.getPaymentVerifiedBy());
    }

    public DocumentView documentView(OrderDocument d, Map<UUID, String> printerNames) {
        return documentView(d, printerNames, Set.of());
    }

    /** withPreview: the documents the Xerox PC made a picture of. */
    public DocumentView documentView(OrderDocument d, Map<UUID, String> printerNames, Set<UUID> withPreview) {
        String printer = d.getPrinterId() == null || printerNames == null ? null : printerNames.get(d.getPrinterId());
        Integer ahead = d.getStatus() == DocumentStatus.CONVERTING ? word.ahead(d) : null;
        String stage = switch (d.getStatus()) {
            case UPLOADING -> "Uploading";
            case CONVERTING -> ahead != null && ahead > 0 ? "Being prepared – " + ahead + " ahead" : "Being prepared";
            case READY -> "Ready";
            case REJECTED -> "Cannot be printed";
            case QUEUED -> "Waiting for a printer";
            case CLAIMED, DOWNLOADING -> "Getting ready to print";
            case SUBMITTED -> printer == null ? "Printing now" : "Printing on " + printer;
            case COMPLETED -> printer == null ? "Printed" : "Printed on " + printer;
            case FAILED -> "Problem – ask at the counter";
            case CANCELLED -> !"CANCELLED_AT_COUNTER".equals(d.getErrorCode()) ? "Cancelled"
                    : d.getAmountPaise() != null && d.getAmountPaise() > 0 ? "Cancelled at the counter - refund due"
                    : "Cancelled at the counter";
        };
        String problem = d.getStatus() == DocumentStatus.REJECTED ? d.getErrorMessage() : null;
        return new DocumentView(d.getId(), d.getPosition(), d.getFileName(), d.getFileType().name(),
                d.getFileSizeBytes(), d.getStatus().name(), stage, problem, d.getPageCount(), d.getImageInfo(),
                d.getRequirements() == null ? null : d.getSettings(),
                pagesText(d), d.getPrintPages(), d.getSides(), d.getSheets(), d.getAmountPaise(), printer,
                d.getStatus() == DocumentStatus.COMPLETED ? d.getCompletedAt() : null, withPreview.contains(d.getId()),
                d.getSourceType() == null ? null : d.getSourceType().name(), ahead);
    }

    /** "3, 7, 10–12", "All 24 pages", "1 page" or "Picture". */
    static String pagesText(OrderDocument d) {
        if (d.getFileType() == FileType.DOCX) return null;        // a Word file that is not pages yet
        if (d.getFileType() != FileType.PDF) return "Picture";
        Integer total = d.getPageCount();
        PrintSettings s = d.getSettings();
        if (s != null && s.pages() != null) return s.pages().replace(",", ", ").replace("-", "–");
        if (total == null) return null;
        return total == 1 ? "1 page" : "All " + total + " pages";
    }

    // ------------------------------------------------------------------ helpers

    private record Inspection(FileInspector.Result result, long size, String problemCode, String problem) {}

    /**
     * Reads the uploaded file (bounded) and checks it. First a small look: how
     * big it really is (whatever the device said) and what it starts with, so
     * a file that is too large or of no known kind is refused without being
     * read. Then the whole file, with its share of the checking memory.
     */
    private Inspection inspect(OrderDocument d) {
        SupabaseStorage.Probe look = storage.probe(d.getStoragePath(), 2048)
                .orElseThrow(() -> ApiException.badRequest("UPLOAD_MISSING",
                        "The file did not arrive. Check your internet and try again."));
        if (look.totalSize() > limits.maxFileSizeBytes()) {
            return new Inspection(null, look.totalSize(), "FILE_TOO_LARGE",
                    "Files must be smaller than " + (limits.maxFileSizeBytes() / 1024 / 1024) + " MB.");
        }
        boolean oldOffice = edu.campus.print.storage.DocxInspector.looksLikeOldOffice(look.prefix());
        if (look.isComplete() || (FileType.detect(look.prefix()) == null && !oldOffice)) {
            // all of it is here already (a tiny file), or it is of no kind that is read further
            FileInspector.Result r = inspector.inspect(look.prefix());
            if (!r.ok()) return new Inspection(r, look.totalSize(), r.problemCode(), r.problem());
            if (look.isComplete()) return judged(r, look.totalSize());
        }

        int share = checkShare(look.totalSize(), checkBudgetMb, checkShareMb);
        boolean got;
        try {
            got = checking.tryAcquire(share, 60, TimeUnit.SECONDS);
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
            return judged(r, probe.totalSize());
        } finally {
            checking.release(share);
        }
    }

    /**
     * How much of the checking memory (in megabytes) a file of this size takes: as many as it is big, never
     * less than a fair share (so only a few small files are read at once), never more than there is (so the
     * biggest file allowed can still be checked, alone).
     */
    static int checkShare(long bytes, int budgetMb, int fairShareMb) {
        long megabytes = bytes / (1024 * 1024) + 1;
        return (int) Math.min(budgetMb, Math.max(fairShareMb, megabytes));
    }

    private Inspection judged(FileInspector.Result r, long size) {
        if (r.pages() > limits.maxFilePages()) {
            return new Inspection(r, size, "TOO_MANY_PAGES",
                    "This file has " + r.pages() + " pages. Files can have up to " + limits.maxFilePages() + ".");
        }
        return new Inspection(r, size, null, null);
    }

    private void deleteFile(OrderDocument d) {
        if (d.isFileDeleted()) return;
        storage.delete(d.getStoragePath());
        documents.markFileDeleted(d.getId());
    }

    /**
     * The order, if this caller may see it; "not found" otherwise (the same
     * answer as for an order that does not exist).
     *
     * An ordinary order answers to its private key. A staff order answers to
     * its staff ID alone: any device signed in with that ID, and none once the
     * ID is switched off or gets a new password. Its key is not enough.
     */
    private PrintOrder owned(UUID id, Caller who) {
        PrintOrder o = orders.findById(id).orElse(null);
        if (o == null || who == null) throw ApiException.notFound("That order");
        if (o.getStaffId() != null) {
            if (who.staff() == null || !o.getStaffId().equals(who.staff().getId())) throw ApiException.notFound("That order");
            return o;
        }
        String key = who.key();
        if (key == null || !Secrets.sameText(o.getAccessKeyHash(), Secrets.sha256Hex(key.trim()))) {
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
        if (t == null) throw ApiException.badRequest("NOT_SUPPORTED", edu.campus.print.storage.DocxInspector.KINDS);
        return t;
    }

    private String uniquePickupCode() {
        for (int i = 0; i < 20; i++) {
            String code = Secrets.newPickupCode();
            if (!orders.existsByPickupCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find a free order number");
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
