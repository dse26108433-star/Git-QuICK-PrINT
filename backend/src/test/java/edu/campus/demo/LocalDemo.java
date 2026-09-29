package edu.campus.demo;

import edu.campus.print.PrintServerApplication;
import edu.campus.print.storage.SupabaseProperties;
import edu.campus.print.storage.SupabaseStorage;
import edu.campus.print.support.TestDb;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole backend on this computer, for trying the website without any
 * cloud account: a throwaway PostgreSQL (db/setup.sql), files kept in memory
 * (served at /dev-storage), demo payments, and two example printers.
 *
 * Never for real use. From backend/, after "mvn test-compile":
 *   java -cp "target/classes;target/test-classes;<dependencies>" edu.campus.demo.LocalDemo [--real-printers]
 * then open the website with apiBase = http://localhost:8080.
 *
 * --real-printers: no example printers; register a Station (counter password
 * "demo-counter-password") and its real printers are used.
 *
 * --upi: CampusPay instead of demo payments. The UPI ID is UPI_ID if set (your
 * own, to try a real payment of a few rupees), else a made-up one. Bank
 * messages: POST them to /api/v1/payments/upi/alerts with header
 * X-Alert-Token: demo-alert-token-0123456789abcdef, or paste them at the counter.
 */
public final class LocalDemo {

    public static final int PORT = 8080;
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
    /** The example printers belong to this PC, so tests can take print jobs like a Station would. */
    private static volatile Map<String, Object> demoPc = Map.of();

    private LocalDemo() {
    }

    public static void main(String[] args) {
        boolean realPrinters = List.of(args).contains("--real-printers");
        boolean upi = List.of(args).contains("--upi");
        String url = TestDb.freshDatabase();
        TestDb.run(TestDb.dataSource(url), TestDb.setupSql());
        JdbcTemplate db = new JdbcTemplate(TestDb.dataSource(url));
        db.update("update shop_settings set center_name = 'Main Xerox Center', price_bw_paise = 200, price_color_paise = 1000, "
                + "pricing = '{\"paperSizePercent\":{\"A3\":200,\"LEGAL\":150},\"mediaTypePercent\":{\"psk:PhotographicHighGloss\":300},"
                + "\"finishingPaise\":{\"STAPLE\":100,\"PUNCH\":100}}'");
        if (!realPrinters) {
            db.update("""
                    insert into printers (name, windows_printer_name, supports_color, accepts_bw, status, status_at, effective)
                    values ('Printer 1 (B/W)', 'CampusPrint Test BW', false, true, 'READY', now() + interval '10 years',
                            '{"paperSizes":["A4","A3","LEGAL"],"duplex":true,"finishing":["STAPLE_TOP_LEFT","STAPLE_DUAL_LEFT","PUNCH_LEFT"],
                              "mediaTypes":[],"mediaTypeNames":{},"borderless":false,"highQuality":false}'),
                           ('Printer 2 (Colour)', 'CampusPrint Test Colour', true, true, 'READY', now() + interval '10 years',
                            '{"paperSizes":["A4","PHOTO_4X6","PHOTO_5X7"],"duplex":false,"finishing":[],
                              "mediaTypes":["psk:PhotographicHighGloss"],"mediaTypeNames":{"psk:PhotographicHighGloss":"Glossy photo paper"},
                              "borderless":true,"highQuality":true}')
                    """);
            Map<String, Object> pc = db.queryForMap("select agent_id, agent_secret from enroll_agent('Demo PC')");
            db.update("update printers set agent_id = ?", pc.get("agent_id"));
            demoPc = Map.of("agentId", pc.get("agent_id").toString(), "agentSecret", pc.get("agent_secret").toString());
        }
        SpringApplication app = new SpringApplication(PrintServerApplication.class, DemoBeans.class);
        String upiId = System.getenv("UPI_ID") == null || System.getenv("UPI_ID").isBlank()
                ? "xeroxshop@okaxis" : System.getenv("UPI_ID");
        String upiName = System.getenv("UPI_NAME") == null ? "" : System.getenv("UPI_NAME");
        List<String> payment = upi
                ? List.of("--campus.payment.mode=upi", "--campus.payment.upi-id=" + upiId,
                          "--campus.payment.upi-name=" + upiName,
                          "--campus.payment.upi-alert-token=demo-alert-token-0123456789abcdef")
                : List.of("--campus.payment.mode=demo");
        List<String> all = new java.util.ArrayList<>(List.of("--server.port=" + PORT,
                "--spring.datasource.url=" + url, "--spring.datasource.username=postgres", "--spring.datasource.password=",
                "--campus.supabase.url=http://localhost:" + PORT, "--campus.supabase.service-key=demo",
                "--campus.counter.password=demo-counter-password",
                "--campus.agent.token-secret=local-demo-token-secret-0123456789abcdef",
                "--campus.cors.allowed-origins=*"));
        all.addAll(payment);
        app.run(all.toArray(String[]::new));
        System.out.println("\n  Campus Print demo backend on http://localhost:" + PORT
                + "  (counter password: demo-counter-password)"
                + (upi ? "\n  CampusPay: pay " + upiId + "; bank messages need X-Alert-Token: demo-alert-token-0123456789abcdef"
                       : "") + "\n");
    }

    /**
     * Storage in memory, reachable over HTTP like Supabase's signed links. The
     * links use the address the request came in on, so the demo also works
     * from the Android emulator (10.0.2.2) or a phone on the same Wi-Fi.
     */
    public static class DemoStorage extends SupabaseStorage {

        public DemoStorage() {
            super(new SupabaseProperties("http://localhost:" + PORT, "demo", "print-documents", 300, 300));
        }

        private static String base() {
            try {
                return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/dev-storage/").build().toUriString();
            } catch (IllegalStateException notInARequest) {
                return "http://localhost:" + PORT + "/dev-storage/";
            }
        }

        @Override
        public SignedUpload createSignedUpload(String objectPath) {
            return new SignedUpload(base() + objectPath, "demo", objectPath);
        }

        @Override
        public String createSignedDownload(String objectPath) {
            return base() + objectPath;
        }

        @Override
        public Optional<Probe> probe(String objectPath, int maxBytes) {
            byte[] b = FILES.get(objectPath);
            if (b == null) return Optional.empty();
            return Optional.of(new Probe(b.length, b.length > maxBytes ? java.util.Arrays.copyOf(b, maxBytes) : b));
        }

        @Override
        public void delete(String objectPath) {
            FILES.remove(objectPath);
        }
    }

    @Configuration
    public static class DemoBeans {
        @Bean
        @Primary
        SupabaseStorage demoStorage() {
            return new DemoStorage();
        }

        @Bean
        @Order(0)
        SecurityFilterChain devStorage(HttpSecurity http) throws Exception {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOriginPatterns(List.of("*"));
            cors.setAllowedMethods(List.of("GET", "PUT", "OPTIONS"));
            cors.setAllowedHeaders(List.of("*"));
            http.securityMatcher("/dev-storage/**")
                .csrf(c -> c.disable())
                .cors(c -> c.configurationSource(r -> cors))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        DevStorageController devStorageController() {
            return new DevStorageController();
        }
    }

    @RestController
    @RequestMapping("/dev-storage")
    public static class DevStorageController {
        @PutMapping("/**")
        public ResponseEntity<Map<String, Object>> put(HttpServletRequest req, @RequestBody byte[] body) {
            FILES.put(path(req), body);
            return ResponseEntity.ok(Map.of("Key", path(req)));
        }

        /** Demo only: the sign-in of the PC that owns the example printers (tests act as its Station). */
        @GetMapping("/_demo-pc")
        public Map<String, Object> demoPc() {
            return demoPc;
        }

        @GetMapping("/**")
        public ResponseEntity<byte[]> get(HttpServletRequest req) {
            byte[] b = FILES.get(path(req));
            if (b == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(b);
        }

        private static String path(HttpServletRequest req) {
            return req.getRequestURI().substring(req.getRequestURI().indexOf("/dev-storage/") + "/dev-storage/".length());
        }
    }
}
