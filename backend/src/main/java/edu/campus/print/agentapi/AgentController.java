package edu.campus.print.agentapi;

import edu.campus.print.agentapi.AgentDtos.*;
import edu.campus.print.common.ApiException;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.domain.Agent;
import edu.campus.print.domain.DocumentStatus;
import edu.campus.print.domain.OrderDocument;
import edu.campus.print.domain.PrintOrder;
import edu.campus.print.domain.Printer;
import edu.campus.print.printing.PaperSize;
import edu.campus.print.printing.PrintSettings;
import edu.campus.print.repo.AgentRepository;
import edu.campus.print.repo.OrderDocumentRepository;
import edu.campus.print.repo.OrderRepository;
import edu.campus.print.repo.PrinterRepository;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.security.AgentTokenService;
import edu.campus.print.security.CurrentAgent;
import edu.campus.print.storage.SupabaseStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * The only API the Xerox center PC uses. The PC always starts the
 * conversation (outbound HTTPS), so the printers are never reachable from
 * the internet: no port forwarding, no VPN.
 *
 * Each document of an order is one print "job". Stations from version 4 on
 * use /agent/v1/jobs/...; older Stations still use /agent/v1/orders/... and
 * only ever get plain A4 one-sided documents, which they print correctly.
 */
@RestController
@RequestMapping("/agent/v1")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final int MAX_CAPABILITIES_CHARS = 200_000;

    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final PrinterRepository printers;
    private final AgentRepository agents;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final AgentTokenService tokens;
    private final AgentProperties props;
    private final PasswordEncoder encoder;

    public AgentController(OrderRepository orders, OrderDocumentRepository documents, PrinterRepository printers,
                           AgentRepository agents, ShopSettingsRepository settings, SupabaseStorage storage,
                           AgentTokenService tokens, AgentProperties props, PasswordEncoder encoder) {
        this.orders = orders;
        this.documents = documents;
        this.printers = printers;
        this.agents = agents;
        this.settings = settings;
        this.storage = storage;
        this.tokens = tokens;
        this.props = props;
        this.encoder = encoder;
    }

    // ------------------------------------------------------------ token

    /** Swaps the enrolled secret (HTTP Basic: agentId:secret) for a 15-minute token. */
    @PostMapping("/token")
    public Map<String, Object> token(HttpServletRequest request,
                                     @RequestHeader(value = "X-Agent-Version", required = false) String version,
                                     @RequestHeader(value = "X-Agent-Host", required = false) String host) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NO_CREDENTIALS", "Agent credentials required.");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Malformed agent credentials.");
        }
        int sep = decoded.indexOf(':');
        UUID agentId;
        try {
            agentId = UUID.fromString(decoded.substring(0, Math.max(sep, 0)).trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Unknown agent id.");
        }
        String secret = decoded.substring(sep + 1).trim();

        Agent agent = agents.findById(agentId).orElse(null);
        if (agent == null || agent.isRevoked() || !encoder.matches(secret, agent.getSecretHash())) {
            log.warn("Rejected agent login for {} from {}", agentId, request.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS",
                    "Agent id or secret is wrong, or the agent was revoked.");
        }
        agents.touch(agentId, nz(version), nz(host));
        log.info("Xerox PC '{}' ({}) signed in from {}", agent.getName(), nz(host), request.getRemoteAddr());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", tokens.issue(agentId));
        out.put("expiresInSeconds", tokens.ttlSeconds());
        out.put("leaseSeconds", props.leaseSeconds());
        return out;
    }

    // ------------------------------------------------------------ heartbeat and printer features

    /**
     * Every ~20 seconds: "I am alive, this is what I see in Windows".
     * The answer is the list of printers to run, so a printer added or
     * switched off on the counter screen takes effect without touching the PC.
     */
    @PostMapping("/heartbeat")
    @Transactional
    public HeartbeatResponse heartbeat(@RequestBody HeartbeatRequest body) {
        UUID me = CurrentAgent.id();
        agents.touch(me, nz(body.agentVersion()), nz(body.hostName()));
        if (body.printers() != null) {
            for (PrinterReport r : body.printers()) {
                if (r.printerId() == null) continue;
                String status = switch (nz(r.status())) {
                    case "READY", "MISSING", "ERROR" -> r.status();
                    default -> "UNKNOWN";
                };
                printers.reportStatus(r.printerId(), me, status, truncate(nz(r.detail()), 300));
            }
        }
        List<PrinterConfig> list = printers.findForAgent(me).stream()
                .map(p -> new PrinterConfig(p.getId(), p.getName(), p.getWindowsPrinterName(),
                        p.isSupportsColor(), p.isAcceptsBw(), p.isEnabled(), nz(p.getCapabilitiesHash()),
                        p.isRescanRequested()))
                .toList();
        return new HeartbeatResponse(list, props.leaseSeconds(), settings.current().getCenterName());
    }

    /**
     * What a printer can do, as the PC found it in Windows. Sent when the PC
     * starts, when a printer is added, when staff ask for a re-scan, and
     * whenever the answer changes (a new driver, a finisher fitted...).
     * Students see the change on the website at once.
     */
    @PostMapping("/printers/{id}/capabilities")
    @Transactional
    public Map<String, Object> capabilities(@PathVariable UUID id, @RequestBody CapabilitiesReport r) {
        UUID me = CurrentAgent.id();
        Printer p = printers.findById(id).orElseThrow(() -> ApiException.notFound("That printer"));
        if (p.getAgentId() != null && !p.getAgentId().equals(me)) {
            throw ApiException.conflict("NOT_YOUR_PRINTER", "That printer belongs to another PC.");
        }
        if (r == null || r.capabilities() == null || !r.capabilities().isObject()
                || r.capabilities().toString().length() > MAX_CAPABILITIES_CHARS) {
            throw ApiException.badRequest("BAD_CAPABILITIES", "The printer features could not be read.");
        }
        boolean changed = !Objects.equals(p.getCapabilitiesHash(), r.hash());
        var before = p.features();
        p.setCapabilities(r.capabilities());
        p.setCapabilitiesHash(r.hash());
        p.setCapabilitiesAt(Instant.now());
        p.setRescanRequested(false);
        p.recompute();
        printers.save(p);
        if (changed) {
            log.info("Printer {}: features {} -> {}", p.getName(), before, p.features());
        }
        return Map.of("ok", true, "effective", p.features());
    }

    // ------------------------------------------------------------ claim

    /**
     * One printer asks for its next document. A single SQL statement with
     * FOR UPDATE SKIP LOCKED makes sure two printers never get the same one,
     * and that a printer only gets what it can really print.
     * 204 = nothing to print right now.
     */
    @PostMapping("/jobs/claim")
    @Transactional
    public ResponseEntity<ClaimedJob> claimJob(@RequestBody ClaimRequest req) {
        UUID me = CurrentAgent.id();
        Printer printer = requirePrinter(req);
        Optional<OrderDocument> claimed = documents.claimNext(me, printer.getId(), props.leaseSeconds(), false);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        OrderDocument d = claimed.get();
        PrintOrder o = orders.findById(d.getOrderId()).orElseThrow();
        List<OrderDocument> all = documents.findByOrderIdOrderByPosition(o.getId());
        int count = (int) all.stream().filter(x -> x.getStatus() != DocumentStatus.CANCELLED).count();
        int number = 1;
        for (OrderDocument x : all) {
            if (x.getId().equals(d.getId())) break;
            if (x.getStatus() != DocumentStatus.CANCELLED) number++;
        }
        PrintSettings s = d.getSettings() == null ? PrintSettings.defaults() : d.getSettings();
        PaperSize paper = PaperSize.byId(s.paperSize()).orElse(PaperSize.ALL.get(0));
        log.info("{} took order {} file {}/{} (attempt {}/{})", printer.getName(), o.getPickupCode(), number, count,
                d.getAttempts(), d.getMaxAttempts());
        return ResponseEntity.ok(new ClaimedJob(
                d.getId().toString(), o.getId().toString(), d.getClaimToken().toString(), printer.getId().toString(),
                printer.getWindowsPrinterName(), o.getPickupCode(), number, count, d.getFileName(),
                d.getFileType().name(), d.getFileSizeBytes() == null ? 0 : d.getFileSizeBytes(), nz(d.getSha256()),
                d.getPageCount() == null ? 0 : d.getPageCount(), d.getPrintPages() == null ? 0 : d.getPrintPages(),
                d.getSides() == null ? 0 : d.getSides(), d.getSheets() == null ? 0 : d.getSheets(), s,
                new Paper(paper.id(), paper.widthMm(), paper.heightMm()), d.getImageInfo(),
                settings.current().isStampCode(), d.getAttempts(), d.getMaxAttempts(), props.leaseSeconds()));
    }

    /** Stations before version 4: plain A4 one-sided documents only, in the shape they understand. */
    @PostMapping("/orders/claim")
    @Transactional
    public ResponseEntity<ClaimedOrder> claimLegacy(@RequestBody ClaimRequest req) {
        UUID me = CurrentAgent.id();
        Printer printer = requirePrinter(req);
        Optional<OrderDocument> claimed = documents.claimNext(me, printer.getId(), props.leaseSeconds(), true);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        OrderDocument d = claimed.get();
        PrintOrder o = orders.findById(d.getOrderId()).orElseThrow();
        PrintSettings s = d.getSettings() == null ? PrintSettings.defaults() : d.getSettings();
        log.info("{} (older Station) took order {} file \"{}\" (attempt {}/{})", printer.getName(),
                o.getPickupCode(), d.getFileName(), d.getAttempts(), d.getMaxAttempts());
        return ResponseEntity.ok(new ClaimedOrder(
                d.getId().toString(), d.getClaimToken().toString(), printer.getId().toString(),
                printer.getWindowsPrinterName(), o.getPickupCode(), d.getFileName(), d.getFileType().name(),
                d.getFileSizeBytes() == null ? 0 : d.getFileSizeBytes(), nz(d.getSha256()),
                d.getPageCount() == null ? 0 : d.getPageCount(), nz(s.pages()),
                d.getPrintPages() == null ? 0 : d.getPrintPages(), settings.current().isStampCode(),
                s.color(), s.copies(), d.getAttempts(), d.getMaxAttempts(), props.leaseSeconds()));
    }

    /** A 5-minute download link, only for the PC holding the document. */
    @GetMapping({"/jobs/{id}/download-url", "/orders/{id}/download-url"})
    public Map<String, String> downloadUrl(@PathVariable UUID id, @RequestHeader("X-Claim-Token") UUID claimToken) {
        OrderDocument d = requireHolder(id, claimToken);
        return Map.of("url", storage.createSignedDownload(d.getStoragePath()));
    }

    // ------------------------------------------------------------ progress

    /**
     * Progress report. Accepted only while the PC still holds the document. A
     * refusal (409) means "not yours any more: do NOT print it".
     */
    @PostMapping({"/jobs/{id}/status", "/orders/{id}/status"})
    @Transactional
    public ResponseEntity<Map<String, Object>> status(@PathVariable UUID id, @RequestBody StatusReport r) {
        UUID me = CurrentAgent.id();
        String allowedFrom = switch (nz(r.status())) {
            case "DOWNLOADING" -> "{CLAIMED}";
            case "SUBMITTED" -> "{DOWNLOADING}";
            case "COMPLETED" -> "{SUBMITTED,FAILED}";   // FAILED only if the reaper gave up too early
            case "FAILED" -> "{CLAIMED,DOWNLOADING,SUBMITTED}";
            default -> throw ApiException.badRequest("BAD_STATUS", "Agents may not set status " + r.status());
        };
        int updated = documents.applyAgentStatus(id, parseToken(r.claimToken()), r.status(), allowedFrom,
                props.leaseSeconds(), nz(r.errorCode()), truncate(nz(r.message()), 900));
        if (updated == 0) {
            log.warn("PC {} tried to set document {} to {} but no longer holds it", me, id, r.status());
            return notYours();
        }
        if ("COMPLETED".equals(r.status())) {
            documents.findById(id).ifPresent(d -> {
                storage.delete(d.getStoragePath());
                documents.markFileDeleted(d.getId());
                log.info("Document {} of order {} printed", d.getPosition(), d.getOrderId());
            });
        } else if ("FAILED".equals(r.status())) {
            log.warn("Document {} failed: {} {}", id, nz(r.errorCode()), nz(r.message()));
        }
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    /** Gives the document back BEFORE anything reached a printer. */
    @PostMapping({"/jobs/{id}/release", "/orders/{id}/release"})
    @Transactional
    public ResponseEntity<Map<String, Object>> release(@PathVariable UUID id, @RequestBody ReleaseRequest r) {
        String newStatus = documents.release(id, parseToken(r.claimToken()), nz(r.errorCode()),
                truncate(nz(r.message()), 900));
        if (newStatus == null) {
            return notYours();
        }
        log.info("Document {} released by the PC -> {} ({})", id, newStatus, nz(r.errorCode()));
        return ResponseEntity.ok(Map.of("status", newStatus));
    }

    /** Keeps a long print's lease alive so recovery leaves it alone. */
    @PostMapping({"/jobs/{id}/lease", "/orders/{id}/lease"})
    public ResponseEntity<Map<String, Object>> renew(@PathVariable UUID id, @RequestBody LeaseRequest r) {
        return documents.renewLease(id, parseToken(r.claimToken()), props.leaseSeconds()) == 1
                ? ResponseEntity.ok(Map.of("renewed", true))
                : notYours();
    }

    // ------------------------------------------------------------ helpers

    private Printer requirePrinter(ClaimRequest req) {
        if (req == null || req.printerId() == null) {
            throw ApiException.badRequest("NO_PRINTER", "printerId is required.");
        }
        return printers.findById(req.printerId()).orElseThrow(() -> ApiException.notFound("That printer"));
    }

    private OrderDocument requireHolder(UUID id, UUID claimToken) {
        UUID me = CurrentAgent.id();
        OrderDocument d = documents.findById(id).orElseThrow(() -> ApiException.notFound("That document"));
        boolean holds = me.equals(d.getAgentId()) && claimToken.equals(d.getClaimToken())
                && DocumentStatus.AT_PRINTER.contains(d.getStatus());
        if (!holds) {
            throw ApiException.conflict("NOT_CURRENT_OWNER", "This document is not assigned to this PC any more.");
        }
        return d;
    }

    private static UUID parseToken(String token) {
        try {
            return UUID.fromString(nz(token).trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_TOKEN", "claimToken is missing or malformed.");
        }
    }

    private static ResponseEntity<Map<String, Object>> notYours() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "NOT_CURRENT_OWNER",
                "message", "This document is no longer assigned to this PC. Do not print it."));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
