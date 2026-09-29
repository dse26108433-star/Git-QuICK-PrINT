package edu.campus.print.counter;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.print.common.ApiException;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.domain.*;
import edu.campus.print.payment.PaymentGateway;
import edu.campus.print.payment.UpiGateway;
import edu.campus.print.payment.UpiLedger;
import edu.campus.print.printing.Finishing;
import edu.campus.print.printing.OfferedFeatures;
import edu.campus.print.printing.PaperSize;
import edu.campus.print.printing.PricingRules;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.printing.PrinterRules;
import edu.campus.print.printing.SettingsText;
import edu.campus.print.repo.AgentRepository;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.repo.PrinterRepository;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.storage.SupabaseStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The Xerox center staff screen (the Campus Print Station app, or web/counter.html).
 * Protected by the counter password. Shows what is printing, what is ready to
 * hand over, and what went wrong; lets staff switch printers on/off, choose what
 * students may pick on each printer, set prices, and switch the pickup-code
 * label on/off. The Station app also uses it to register its PC and printers.
 * With CampusPay (direct UPI) staff also confirm payments no bank message proved.
 */
@RestController
@RequestMapping("/api/v1/counter")
public class CounterController {

    private static final Logger log = LoggerFactory.getLogger(CounterController.class);

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final PrinterRepository printers;
    private final AgentRepository agents;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final PaymentGateway gateway;
    private final UpiLedger upi;
    private final AgentProperties agentProps;
    private final JdbcTemplate jdbc;

    public CounterController(OrderRepository orders, OrderDocumentRepository documents, PrinterRepository printers,
                             AgentRepository agents, ShopSettingsRepository settings, SupabaseStorage storage,
                             PaymentGateway gateway, UpiLedger upi, AgentProperties agentProps, JdbcTemplate jdbc) {
        this.orders = orders;
        this.documents = documents;
        this.printers = printers;
        this.agents = agents;
        this.settings = settings;
        this.storage = storage;
        this.gateway = gateway;
        this.upi = upi;
        this.agentProps = agentProps;
        this.jdbc = jdbc;
    }

    /** One file of an order, as staff need it. */
    public record CounterDocument(UUID id, int position, String fileName, String fileType, Integer pageCount,
                                  String pages, Integer printPages, Integer sheets, int copies, boolean color,
                                  String settingsText, String status, String printerName, String errorCode,
                                  String errorMessage, int attempts, boolean fileKept, Integer amountPaise,
                                  boolean noPrinter) {}

    public record CounterOrder(UUID id, String pickupCode, String status, Integer amountPaise,
                               String paymentProvider, String paymentId, String errorCode, String errorMessage,
                               Instant createdAt, Instant paidAt, Instant completedAt, Instant collectedAt,
                               List<CounterDocument> documents, int totalSheets, Integer refundDuePaise,
                               // The first document, for older counter screens:
                               String fileName, String fileType, Integer pageCount, String pages,
                               Integer printPages, int copies, boolean color, String printerName,
                               int attempts, boolean fileKept,
                               // CampusPay (direct UPI):
                               Instant paymentStartedAt, Integer upiTagPaise, String paymentClaimRef,
                               Instant paymentClaimedAt, String paymentNote, String paymentVerifiedBy) {}

    public record SettingsForm(String centerName, Integer priceBwPaise, Integer priceColorPaise, Boolean stampCode,
                               PricingRules pricing) {}

    public record PcForm(String name) {}

    public record PrinterForm(String name, String windowsPrinterName, Boolean supportsColor, Boolean acceptsBw,
                              UUID agentId, Boolean enabled, JsonNode capabilities, String capabilitiesHash,
                              OfferedFeatures offered) {}

    /** Everything the top of the screen needs, in one call. */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Duration fresh = agentProps.offlineAfter();
        ShopSettings s = settings.current();
        List<Printer> all = printers.findAllByOrderByName();

        List<Map<String, Object>> printerList = new ArrayList<>();
        for (Printer p : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("windowsPrinterName", p.getWindowsPrinterName());
            m.put("supportsColor", p.isSupportsColor());
            m.put("acceptsBw", p.isAcceptsBw());
            m.put("enabled", p.isEnabled());
            m.put("status", p.isOnline(fresh) ? "READY"
                    : (p.getStatusAt() != null && p.getStatusAt().isAfter(Instant.now().minus(fresh))
                       ? p.getStatus() : "OFFLINE"));
            m.put("statusDetail", p.getStatusDetail());
            m.put("agentId", p.getAgentId());
            m.put("features", p.features());
            m.put("capabilities", p.getCapabilities());
            m.put("capabilitiesAt", p.getCapabilitiesAt());
            m.put("offered", p.getOffered());
            m.put("rescanRequested", p.isRescanRequested());
            printerList.add(m);
        }

        List<Map<String, Object>> pcs = new ArrayList<>();
        for (Agent a : agents.findByRevokedFalse()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("hostName", a.getHostName());
            m.put("version", a.getAgentVersion());
            m.put("online", a.isOnline(fresh));
            m.put("lastSeenAt", a.getLastSeenAt());
            pcs.add(m);
        }

        List<PrinterRules.Candidate> usable = all.stream().filter(Printer::isEnabled).map(Printer::candidate).toList();
        long noPrinter = documents.findQueued().stream()
                .filter(d -> d.getRequirements() != null && !PrinterRules.anyCanDo(usable, d.getRequirements()))
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("centerName", s.getCenterName());
        out.put("priceBwPaise", s.getPriceBwPaise());
        out.put("priceColorPaise", s.getPriceColorPaise());
        out.put("pricing", s.getPricing());
        out.put("paymentMode", gateway.name());
        out.put("upi", upiInfo());
        out.put("paymentsToCheck", gateway instanceof UpiGateway ? upi.toCheckCount() : 0);
        out.put("stampCode", s.isStampCode());
        out.put("printers", printerList);
        out.put("pcs", pcs);
        out.put("paperSizes", PaperSize.ALL);
        out.put("finishingLabels", Finishing.LABELS);
        out.put("waiting", orders.countByStatusIn(EnumSet.of(OrderStatus.QUEUED)));
        out.put("printing", orders.countByStatusIn(EnumSet.of(OrderStatus.PRINTING)));
        out.put("ready", orders.findReadyToCollect().size());
        out.put("problems", orders.findProblems().size());
        out.put("noPrinter", noPrinter);
        return out;
    }

    /** view = active | ready | problems | all.  code = search by pickup code. */
    @GetMapping("/orders")
    public List<CounterOrder> list(@RequestParam(defaultValue = "active") String view,
                                   @RequestParam(required = false) String code) {
        List<PrintOrder> found;
        if (code != null && !code.isBlank()) {
            found = orders.findByPickupCodeIgnoreCase(code.trim());
        } else {
            found = switch (view) {
                case "ready" -> orders.findReadyToCollect();
                case "problems" -> orders.findProblems();
                case "all" -> orders.findTop200ByOrderByCreatedAtDesc();
                default -> orders.findTop200ByStatusInOrderByCreatedAtDesc(OrderStatus.IN_PROGRESS);
            };
        }
        return describe(found);
    }

    private List<CounterOrder> describe(List<PrintOrder> found) {
        if (found.isEmpty()) return List.of();
        List<Printer> all = printers.findAll();
        Map<UUID, String> names = new HashMap<>();
        Map<String, String> media = new HashMap<>();
        all.forEach(p -> {
            names.put(p.getId(), p.getName());
            media.putAll(p.features().mediaTypeNames());
        });
        List<PrinterRules.Candidate> usable = all.stream().filter(Printer::isEnabled).map(Printer::candidate).toList();
        Map<UUID, List<OrderDocument>> byOrder = new HashMap<>();
        for (OrderDocument d : documents.findByOrderIdInOrderByPosition(found.stream().map(PrintOrder::getId).toList())) {
            byOrder.computeIfAbsent(d.getOrderId(), k -> new ArrayList<>()).add(d);
        }
        List<CounterOrder> out = new ArrayList<>();
        for (PrintOrder o : found) {
            List<CounterDocument> docs = new ArrayList<>();
            int sheets = 0;
            int refund = 0;
            for (OrderDocument d : byOrder.getOrDefault(o.getId(), List.of())) {
                PrintSettings s = d.getSettings();
                int copies = s == null ? 1 : s.copies();
                if (d.getStatus() != DocumentStatus.CANCELLED && d.getSheets() != null) sheets += d.getSheets() * copies;
                if (d.getStatus() == DocumentStatus.CANCELLED && o.getPaidAt() != null && d.getAmountPaise() != null) {
                    refund += d.getAmountPaise();
                }
                boolean noPrinter = d.getStatus() == DocumentStatus.QUEUED && d.getRequirements() != null
                        && !PrinterRules.anyCanDo(usable, d.getRequirements());
                docs.add(new CounterDocument(d.getId(), d.getPosition(), d.getFileName(), d.getFileType().name(),
                        d.getPageCount(), s == null ? null : s.pages(), d.getPrintPages(), d.getSheets(), copies,
                        s != null && s.color(), SettingsText.of(s, d.getFileType() != FileType.PDF, media),
                        d.getStatus().name(), d.getPrinterId() == null ? null : names.get(d.getPrinterId()),
                        d.getErrorCode(), d.getErrorMessage(), d.getAttempts(), !d.isFileDeleted(),
                        d.getAmountPaise(), noPrinter));
            }
            CounterDocument first = docs.isEmpty() ? null : docs.get(0);
            out.add(new CounterOrder(o.getId(), o.getPickupCode(), o.getStatus().name(), o.getAmountPaise(),
                    o.getPaymentProvider(), o.getGatewayPaymentId(), o.getErrorCode(), o.getErrorMessage(),
                    o.getCreatedAt(), o.getPaidAt(), o.getCompletedAt(), o.getCollectedAt(), docs, sheets,
                    refund == 0 ? null : refund,
                    first == null ? "" : docs.size() == 1 ? first.fileName() : first.fileName() + " + " + (docs.size() - 1) + " more",
                    first == null ? "PDF" : first.fileType(), first == null ? null : first.pageCount(),
                    first == null ? null : first.pages(), first == null ? null : first.printPages(),
                    first == null ? 1 : first.copies(), first != null && first.color(),
                    first == null ? null : first.printerName(),
                    docs.stream().mapToInt(CounterDocument::attempts).max().orElse(0),
                    docs.stream().anyMatch(CounterDocument::fileKept),
                    o.getPaymentStartedAt(), o.getUpiTagPaise(), o.getPaymentClaimRef(), o.getPaymentClaimedAt(),
                    o.getPaymentNote(), o.getPaymentVerifiedBy()));
        }
        return out;
    }

    // ------------------------------------------------------------ CampusPay (direct UPI)

    public record ApproveForm(String reference) {}

    public record RejectForm(String reason) {}

    public record AlertForm(String text) {}

    /** Where students pay, and whether bank messages confirm payments by themselves. Null unless PAYMENT_MODE=upi. */
    private Map<String, Object> upiInfo() {
        if (!(gateway instanceof UpiGateway g)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payeeVpa", g.payee().vpa());
        m.put("payeeName", g.payee().displayName(settings.current().getCenterName()));
        m.put("merchant", g.payee().merchant());
        m.put("alertsConfigured", g.alertsOn());
        m.put("autoConfirm", g.autoConfirm());
        m.put("matchMinutes", g.windowMinutes());
        m.put("verifiers", upi.verifiers());
        return m;
    }

    /**
     * Everything about CampusPay payments: students who said "I have paid"
     * (staff check these), open payment screens, today's confirmed payments,
     * and the bank messages that came in (with the order each one paid).
     */
    @GetMapping("/payments")
    public Map<String, Object> payments() {
        requireUpi();
        List<PrintOrder> toCheck = orders.findUpiToCheck();
        Map<String, Object> hints = new LinkedHashMap<>();
        for (PrintOrder o : toCheck) {
            if (o.getAmountPaise() == null || o.getPaymentStartedAt() == null) continue;
            List<UpiLedger.Alert> same = upi.unmatchedFor(o.getAmountPaise(), o.getPaymentStartedAt());
            if (!same.isEmpty()) hints.put(o.getId().toString(), same);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upi", upiInfo());
        out.put("today", upi.todayTotals());
        out.put("toCheck", describe(toCheck));
        out.put("hints", hints);
        out.put("waiting", describe(orders.findUpiWaiting()));
        out.put("paid", describe(orders.findUpiPaidRecently()));
        out.put("alerts", upi.recentAlerts(50));
        return out;
    }

    /**
     * Staff saw the money in the bank or UPI app (or the student paid at the
     * counter): the order is paid and prints. reference: the 12-digit UPI
     * reference number, if staff have it (it links the bank message, and the
     * same number can never pay a second order).
     */
    @PostMapping("/orders/{id}/payment/approve")
    public Map<String, Object> approvePayment(@PathVariable UUID id, @RequestBody(required = false) ApproveForm f) {
        requireUpi();
        PrintOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
        String ref = f == null || f.reference() == null ? null : f.reference().replaceAll("[\\s-]", "");
        if (ref != null && ref.isEmpty()) ref = null;
        if (ref != null && !ref.matches("[0-9]{12}")) {
            throw ApiException.badRequest("BAD_REFERENCE",
                    "A UPI reference number has 12 digits. Leave it empty if you do not have it.");
        }
        String result = upi.approve(id, ref);
        switch (result) {
            case "OK" -> log.warn("Counter confirmed UPI payment of {} paise for order {} (ref {})",
                    o.getAmountPaise(), o.getPickupCode(), ref == null ? o.getPaymentClaimRef() : ref);
            case "REF_USED" -> throw ApiException.conflict("REFERENCE_USED",
                    "This UPI reference number already paid another order. Check it in the bank app.");
            default -> throw ApiException.conflict("NOT_AWAITING_PAYMENT",
                    o.getStatus().isPaid() ? "This order is already paid." : "This order cannot be paid now.");
        }
        return Map.of("ok", true);
    }

    /** Staff could not find the money: the student is told, and can send the reference again or pay. */
    @PostMapping("/orders/{id}/payment/reject")
    public Map<String, Object> rejectPayment(@PathVariable UUID id, @RequestBody(required = false) RejectForm f) {
        requireUpi();
        PrintOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
        String reason = f == null || f.reason() == null || f.reason().isBlank() ? null : f.reason().trim();
        if (reason != null && reason.length() > 200) reason = reason.substring(0, 200);
        String note = "The Xerox center could not find your payment of " + rupees(o.getAmountPaise())
                + (reason == null ? "" : " (" + reason + ")")
                + ". Check the UPI reference number in your UPI app and send it again. If no money was taken, pay again.";
        if (!upi.reject(id, note)) {
            throw ApiException.conflict("NOT_AWAITING_PAYMENT",
                    o.getStatus().isPaid() ? "This order is already paid." : "This order is not waiting for a UPI payment.");
        }
        log.warn("Counter could not find UPI payment for order {}", o.getPickupCode());
        return Map.of("ok", true);
    }

    /** Staff paste a bank SMS by hand: read and matched exactly like a forwarded one. */
    @PostMapping("/payments/alerts")
    public Map<String, Object> pasteAlert(@RequestBody(required = false) AlertForm f) {
        requireUpi();
        if (f == null || f.text() == null || f.text().isBlank()) {
            throw ApiException.badRequest("BAD_ALERT", "Paste the bank's message first.");
        }
        UpiLedger.Received r = upi.receive(f.text(), "counter", "counter", "",
                ((UpiGateway) gateway).windowMinutes());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", r.parsed().kind().name());
        out.put("amountPaise", r.parsed().amountPaise());
        out.put("references", r.parsed().refs());
        out.put("stored", r.stored());
        out.put("duplicate", r.duplicate());
        out.put("paidOrder", r.alert() == null ? null : r.alert().pickupCode());
        return out;
    }

    private void requireUpi() {
        if (!(gateway instanceof UpiGateway)) {
            throw ApiException.conflict("NOT_UPI",
                    "CampusPay is off: this shop uses PAYMENT_MODE=" + gateway.name() + ".");
        }
    }

    private static String rupees(Integer paise) {
        if (paise == null) return "the order";
        return "₹" + (paise % 100 == 0 ? String.valueOf(paise / 100) : String.format("%.2f", paise / 100.0));
    }

    /** Handed to the student (or refunded, for a problem order). */
    @PostMapping("/orders/{id}/collected")
    public Map<String, Object> collected(@PathVariable UUID id) {
        if (orders.markCollected(id) == 0) {
            throw ApiException.conflict("NOT_READY", "Only paid orders that finished printing can be marked as handed over.");
        }
        return Map.of("ok", true);
    }

    /** Staff checked the tray: print every failed file of this paid order again. */
    @PostMapping("/orders/{id}/print-again")
    public Map<String, Object> printAgain(@PathVariable UUID id) {
        int n = documents.printAgainForOrder(id);
        if (n == 0) {
            throw ApiException.conflict("CANNOT_REPRINT",
                    "Only paid files that failed can be printed again, and only within 24 hours.");
        }
        log.warn("Counter pressed 'Print again' for order {} ({} file(s))", id, n);
        return Map.of("ok", true, "documents", n);
    }

    /** Staff checked the tray: print this one failed file again. */
    @PostMapping("/documents/{id}/print-again")
    public Map<String, Object> printDocumentAgain(@PathVariable UUID id) {
        if (documents.printAgain(id) == 0) {
            throw ApiException.conflict("CANNOT_REPRINT",
                    "Only paid files that failed can be printed again, and only within 24 hours.");
        }
        log.warn("Counter pressed 'Print again' for document {}", id);
        return Map.of("ok", true);
    }

    /** Cancel a paid order that has not started printing. Refund it in the Razorpay dashboard. */
    @PostMapping("/orders/{id}/cancel")
    @Transactional
    public Map<String, Object> cancel(@PathVariable UUID id) {
        PrintOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
        if (documents.cancelOrderAtCounter(id) == 0) {
            throw ApiException.conflict("CANNOT_CANCEL", "Only orders still waiting for a printer can be cancelled.");
        }
        for (OrderDocument d : documents.findByOrderIdOrderByPosition(id)) {
            if (d.getStatus() == DocumentStatus.CANCELLED && !d.isFileDeleted()) {
                storage.delete(d.getStoragePath());
                documents.markFileDeleted(d.getId());
            }
        }
        log.warn("Counter cancelled order {} (refund due, payment {})", o.getPickupCode(), o.getGatewayPaymentId());
        return Map.of("ok", true, "paymentId", o.getGatewayPaymentId() == null ? "" : o.getGatewayPaymentId());
    }

    /**
     * Cancel one paid file that is waiting (for example no printer can do it
     * today) or failed. The rest of the order prints; refund this file's price.
     */
    @PostMapping("/documents/{id}/cancel")
    public Map<String, Object> cancelDocument(@PathVariable UUID id) {
        OrderDocument d = documents.findById(id).orElseThrow(() -> ApiException.notFound("That file"));
        if (documents.cancelAtCounter(id) == 0) {
            throw ApiException.conflict("CANNOT_CANCEL", "Only files waiting for a printer, or that failed, can be cancelled.");
        }
        storage.delete(d.getStoragePath());
        documents.markFileDeleted(id);
        log.warn("Counter cancelled file \"{}\" of order {} (refund {} paise)", d.getFileName(), d.getOrderId(),
                d.getAmountPaise());
        return Map.of("ok", true, "refundPaise", d.getAmountPaise() == null ? 0 : d.getAmountPaise());
    }

    @PostMapping("/printers/{id}/enabled")
    public Map<String, Object> setEnabled(@PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        Printer p = printers.findById(id).orElseThrow(() -> ApiException.notFound("That printer"));
        p.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        printers.save(p);
        log.info("Printer {} {}", p.getName(), p.isEnabled() ? "switched on" : "switched off");
        return Map.of("id", p.getId(), "enabled", p.isEnabled());
    }

    /** Ask the PC to read this printer's features from Windows again (it answers within a minute). */
    @PostMapping("/printers/{id}/rescan")
    public Map<String, Object> rescan(@PathVariable UUID id) {
        Printer p = printers.findById(id).orElseThrow(() -> ApiException.notFound("That printer"));
        p.setRescanRequested(true);
        printers.save(p);
        return Map.of("ok", true);
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody SettingsForm f) {
        ShopSettings s = settings.current();
        if (f.centerName() != null && !f.centerName().isBlank()) {
            s.setCenterName(f.centerName().trim());
        }
        if (f.priceBwPaise() != null) {
            if (f.priceBwPaise() < 0 || f.priceBwPaise() > 100_000) {
                throw ApiException.badRequest("BAD_PRICE", "B/W price looks wrong.");
            }
            s.setPriceBwPaise(f.priceBwPaise());
        }
        if (f.priceColorPaise() != null) {
            if (f.priceColorPaise() < 0 || f.priceColorPaise() > 100_000) {
                throw ApiException.badRequest("BAD_PRICE", "Colour price looks wrong.");
            }
            s.setPriceColorPaise(f.priceColorPaise());
        }
        if (f.stampCode() != null) {
            s.setStampCode(f.stampCode());
            log.info("Pickup code on pages switched {}", f.stampCode() ? "ON" : "OFF");
        }
        if (f.pricing() != null) {
            s.setPricing(checkPricing(f.pricing()));
        }
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        log.info("Prices updated: B/W {} paise, colour {} paise, rules {}", s.getPriceBwPaise(),
                s.getPriceColorPaise(), s.getPricing());
        return Map.of("ok", true);
    }

    private static PricingRules checkPricing(PricingRules r) {
        for (var e : r.paperSizePercent().entrySet()) {
            if (!PaperSize.known(e.getKey()) || e.getValue() == null || e.getValue() < 10 || e.getValue() > 1000) {
                throw ApiException.badRequest("BAD_PRICE", "Paper size prices must be between 10 % and 1000 %.");
            }
        }
        for (var e : r.mediaTypePercent().entrySet()) {
            if (e.getValue() == null || e.getValue() < 10 || e.getValue() > 1000) {
                throw ApiException.badRequest("BAD_PRICE", "Paper type prices must be between 10 % and 1000 %.");
            }
        }
        for (var e : r.finishingPaise().entrySet()) {
            if (!Finishing.GROUPS.contains(e.getKey()) || e.getValue() == null || e.getValue() < 0
                    || e.getValue() > 100_000) {
                throw ApiException.badRequest("BAD_PRICE", "Finishing prices look wrong.");
            }
        }
        return r;
    }

    // ------------------------------------------------------------ PCs and printers (Station app)

    /**
     * Registers a Xerox center PC. The Station app calls this once, during its
     * setup, and keeps the answer; the secret is never shown again.
     */
    @PostMapping("/pcs")
    public Map<String, Object> enrollPc(@RequestBody PcForm f) {
        String name = f == null || f.name() == null || f.name().isBlank() ? "Xerox PC" : f.name().trim();
        if (name.length() > 60) name = name.substring(0, 60);
        Map<String, Object> row = jdbc.queryForMap("select agent_id, agent_secret from enroll_agent(?)", name);
        log.info("Counter registered a new PC \"{}\" ({})", name, row.get("agent_id"));
        return Map.of("agentId", row.get("agent_id"), "agentSecret", row.get("agent_secret"), "name", name);
    }

    /** A PC is taken out of use: it can no longer sign in, and its printers are removed. */
    @DeleteMapping("/pcs/{id}")
    @Transactional
    public Map<String, Object> removePc(@PathVariable UUID id) {
        int pcs = jdbc.update("update agents set revoked = true where id = ?", id);
        if (pcs == 0) throw ApiException.notFound("That PC");
        int removed = jdbc.update("delete from printers where agent_id = ?", id);
        log.warn("Counter removed PC {} and its {} printer(s)", id, removed);
        return Map.of("ok", true, "printersRemoved", removed);
    }

    @PostMapping("/printers")
    public Map<String, Object> addPrinter(@RequestBody PrinterForm f) {
        Checked c = check(f, null);
        Printer p = Printer.create(c.name, c.windowsName, c.color, c.bw, c.agentId);
        if (f.enabled() != null) p.setEnabled(f.enabled());
        applyFeatures(p, f);
        printers.save(p);
        log.info("Printer added: {} (\"{}\", {}) {}", p.getName(), p.getWindowsPrinterName(),
                p.isSupportsColor() ? "colour" : "B/W", p.features());
        return Map.of("id", p.getId(), "features", p.features());
    }

    @PutMapping("/printers/{id}")
    public Map<String, Object> updatePrinter(@PathVariable UUID id, @RequestBody PrinterForm f) {
        Printer p = printers.findById(id).orElseThrow(() -> ApiException.notFound("That printer"));
        Checked c = check(f, p);
        p.setName(c.name);
        p.setWindowsPrinterName(c.windowsName);
        p.setSupportsColor(c.color);
        p.setAcceptsBw(c.bw);
        if (f.enabled() != null) p.setEnabled(f.enabled());
        applyFeatures(p, f);
        printers.save(p);
        log.info("Printer updated: {} {}", p.getName(), p.features());
        return Map.of("id", p.getId(), "features", p.features());
    }

    /** New features found by the Station and/or new choices by staff. */
    private static void applyFeatures(Printer p, PrinterForm f) {
        if (f.capabilities() != null && f.capabilities().isObject()
                && f.capabilities().toString().length() <= 200_000) {
            p.setCapabilities(f.capabilities());
            p.setCapabilitiesHash(f.capabilitiesHash());
            p.setCapabilitiesAt(Instant.now());
        }
        if (f.offered() != null) {
            p.setOffered(f.offered());
        }
        p.recompute();
    }

    @DeleteMapping("/printers/{id}")
    public Map<String, Object> deletePrinter(@PathVariable UUID id) {
        Printer p = printers.findById(id).orElseThrow(() -> ApiException.notFound("That printer"));
        printers.delete(p);
        log.info("Printer removed: {}", p.getName());
        return Map.of("ok", true);
    }

    private record Checked(String name, String windowsName, boolean color, boolean bw, UUID agentId) {}

    private Checked check(PrinterForm f, Printer existing) {
        if (f == null) throw ApiException.badRequest("BAD_PRINTER", "Printer details are missing.");
        String name = f.name() != null ? f.name().trim() : existing != null ? existing.getName() : "";
        String win = f.windowsPrinterName() != null ? f.windowsPrinterName().trim()
                : existing != null ? existing.getWindowsPrinterName() : "";
        boolean color = f.supportsColor() != null ? f.supportsColor() : existing != null && existing.isSupportsColor();
        boolean bw = f.acceptsBw() != null ? f.acceptsBw() : existing == null || existing.isAcceptsBw();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiException.badRequest("BAD_PRINTER", "Give the printer a name (up to 60 letters).");
        }
        if (win.isEmpty() || win.length() > 200) {
            throw ApiException.badRequest("BAD_PRINTER", "The Windows printer name is missing.");
        }
        if (!color && !bw) {
            throw ApiException.badRequest("BAD_PRINTER", "A printer must take colour work, B/W work, or both.");
        }
        UUID agentId = existing != null ? existing.getAgentId() : f.agentId();
        if (existing == null) {
            if (agentId == null || agents.findById(agentId).map(Agent::isRevoked).orElse(true)) {
                throw ApiException.badRequest("BAD_PRINTER", "That PC is not registered.");
            }
        }
        return new Checked(name, win, color, bw, agentId);
    }
}
