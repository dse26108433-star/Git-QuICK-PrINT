package edu.campus.print.agentapi;

import edu.campus.print.agentapi.AgentDtos.*;
import edu.campus.print.common.ApiException;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.domain.Agent;
import edu.campus.print.domain.OrderStatus;
import edu.campus.print.domain.PrintOrder;
import edu.campus.print.domain.Printer;
import edu.campus.print.repo.AgentRepository;
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
import java.util.*;

/**
 * The only API the Xerox center PC uses. The PC always starts the
 * conversation (outbound HTTPS), so the printers are never reachable from
 * the internet: no port forwarding, no VPN.
 */
@RestController
@RequestMapping("/agent/v1")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final OrderRepository orders;
    private final PrinterRepository printers;
    private final AgentRepository agents;
    private final ShopSettingsRepository settings;
    private final SupabaseStorage storage;
    private final AgentTokenService tokens;
    private final AgentProperties props;
    private final PasswordEncoder encoder;

    public AgentController(OrderRepository orders, PrinterRepository printers, AgentRepository agents,
                           ShopSettingsRepository settings, SupabaseStorage storage, AgentTokenService tokens,
                           AgentProperties props, PasswordEncoder encoder) {
        this.orders = orders;
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

    // ------------------------------------------------------------ heartbeat

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
                        p.isSupportsColor(), p.isAcceptsBw(), p.isEnabled()))
                .toList();
        return new HeartbeatResponse(list, props.leaseSeconds(), settings.current().getCenterName());
    }

    // ------------------------------------------------------------ claim

    /**
     * One printer asks for its next order. A single SQL statement with
     * FOR UPDATE SKIP LOCKED makes sure two printers never get the same one.
     * 204 = nothing to print right now.
     */
    @PostMapping("/orders/claim")
    @Transactional
    public ResponseEntity<ClaimedOrder> claim(@RequestBody ClaimRequest req) {
        UUID me = CurrentAgent.id();
        if (req.printerId() == null) {
            throw ApiException.badRequest("NO_PRINTER", "printerId is required.");
        }
        Printer printer = printers.findById(req.printerId())
                .orElseThrow(() -> ApiException.notFound("That printer"));

        Optional<PrintOrder> claimed = orders.claimNext(me, printer.getId(), props.leaseSeconds());
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        PrintOrder o = claimed.get();
        log.info("{} took order {} (attempt {}/{})", printer.getName(), o.getPickupCode(),
                o.getAttempts(), o.getMaxAttempts());
        return ResponseEntity.ok(new ClaimedOrder(
                o.getId().toString(), o.getClaimToken().toString(), printer.getId().toString(),
                printer.getWindowsPrinterName(), o.getPickupCode(), o.getFileName(), o.getFileType().name(),
                o.getFileSizeBytes() == null ? 0 : o.getFileSizeBytes(), nz(o.getSha256()),
                o.getPageCount() == null ? 0 : o.getPageCount(), nz(o.getPageRanges()),
                o.getPrintPages() == null ? 0 : o.getPrintPages(), settings.current().isStampCode(),
                o.isColor(), o.getCopies(),
                o.getAttempts(), o.getMaxAttempts(), props.leaseSeconds()));
    }

    /** A 5-minute download link, only for the PC holding the order. */
    @GetMapping("/orders/{id}/download-url")
    public Map<String, String> downloadUrl(@PathVariable UUID id, @RequestHeader("X-Claim-Token") UUID claimToken) {
        PrintOrder o = requireHolder(id, claimToken);
        return Map.of("url", storage.createSignedDownload(o.getStoragePath()));
    }

    // ------------------------------------------------------------ progress

    /**
     * Progress report. Accepted only while the PC still holds the order. A
     * refusal (409) means "not yours any more: do NOT print it".
     */
    @PostMapping("/orders/{id}/status")
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
        int updated = orders.applyAgentStatus(id, parseToken(r.claimToken()), r.status(), allowedFrom,
                props.leaseSeconds(), nz(r.errorCode()), truncate(nz(r.message()), 900));
        if (updated == 0) {
            log.warn("PC {} tried to set order {} to {} but no longer holds it", me, id, r.status());
            return notYours();
        }
        if ("COMPLETED".equals(r.status())) {
            orders.findById(id).ifPresent(o -> {
                storage.delete(o.getStoragePath());
                orders.markFileDeleted(o.getId());
                log.info("Order {} printed", o.getPickupCode());
            });
        } else if ("FAILED".equals(r.status())) {
            log.warn("Order {} failed: {} {}", id, nz(r.errorCode()), nz(r.message()));
        }
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    /** Gives the order back BEFORE anything reached a printer. */
    @PostMapping("/orders/{id}/release")
    @Transactional
    public ResponseEntity<Map<String, Object>> release(@PathVariable UUID id, @RequestBody ReleaseRequest r) {
        String newStatus = orders.release(id, parseToken(r.claimToken()), nz(r.errorCode()),
                truncate(nz(r.message()), 900));
        if (newStatus == null) {
            return notYours();
        }
        log.info("Order {} released by the PC -> {} ({})", id, newStatus, nz(r.errorCode()));
        return ResponseEntity.ok(Map.of("status", newStatus));
    }

    /** Keeps a long print's lease alive so recovery leaves it alone. */
    @PostMapping("/orders/{id}/lease")
    public ResponseEntity<Map<String, Object>> renew(@PathVariable UUID id, @RequestBody LeaseRequest r) {
        return orders.renewLease(id, parseToken(r.claimToken()), props.leaseSeconds()) == 1
                ? ResponseEntity.ok(Map.of("renewed", true))
                : notYours();
    }

    // ------------------------------------------------------------ helpers

    private PrintOrder requireHolder(UUID id, UUID claimToken) {
        UUID me = CurrentAgent.id();
        PrintOrder o = orders.findById(id).orElseThrow(() -> ApiException.notFound("That order"));
        boolean holds = me.equals(o.getAgentId()) && claimToken.equals(o.getClaimToken())
                && (o.getStatus() == OrderStatus.CLAIMED || o.getStatus() == OrderStatus.DOWNLOADING
                    || o.getStatus() == OrderStatus.SUBMITTED);
        if (!holds) {
            throw ApiException.conflict("NOT_CURRENT_OWNER", "This order is not assigned to this PC any more.");
        }
        return o;
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
                "message", "This order is no longer assigned to this PC. Do not print it."));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
