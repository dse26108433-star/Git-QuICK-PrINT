package edu.campus.agent.server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.campus.agent.station.StationConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;

/**
 * "This PC is the XeoGo server": what the Station remembers about the server
 * it runs itself (database + print service + the tunnel to the internet), in
 * %LOCALAPPDATA%\CampusPrint\server.json. Only this Windows user can read it.
 *
 * The secrets in here (database password, signing secret) are made by the
 * Station on the first start and never typed or shown.
 */
public class ServerSettings {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    /** This PC runs the server. false: the server is somewhere else (the cloud), as before. */
    public boolean enabled = false;

    /** Ports on this PC only (127.0.0.1). The tunnel to the internet points at httpPort. */
    public int httpPort = 47810;
    public int dbPort = 47812;
    public int tunnelStatusPort = 47811;

    public String dbPassword = "";
    public String tokenSecret = "";
    /** The staff password of the counter (what COUNTER_PASSWORD is on a cloud server). */
    public String counterPassword = "";

    // ---- how students pay (the same settings a cloud server takes from its environment)
    /** "upi" (XeoGo Pay), "razorpay", "demo" (tests only: prints without payment) or "" (paying is off). */
    public String paymentMode = "";
    public String upiId = "";
    public String upiName = "";
    public String upiMerchantCode = "";
    public String upiAlertToken = "";
    public String upiSmsSenders = "";
    public String upiTrustedApps = "";
    public String razorpayKeyId = "";
    public String razorpayKeySecret = "";

    /** Websites that may use this server from a browser, comma separated. "*": any (orders are protected by their keys). */
    public String webOrigins = "*";
    public String timeZone = "Asia/Kolkata";

    // ---- reaching this PC from the internet
    /** The address students' phones use, e.g. https://print.mycollege.in. Shown, tested, and printed on posters. */
    public String publicUrl = "";
    /** The Cloudflare Tunnel token of this PC (Cloudflare dashboard -> Tunnels -> your tunnel). */
    public String tunnelToken = "";

    // ---- the copy of the database in the cloud
    /** Supabase project URL (https://xxxx.supabase.co) and its secret key: backups are uploaded there. */
    public String cloudUrl = "";
    public String cloudKey = "";

    /** Megabytes of memory the print service may use. 0: chosen from this PC's memory. */
    public int serverMemoryMb = 0;

    public static Path file() {
        return StationConfig.dir().resolve("server.json");
    }

    public static ServerSettings load() {
        ServerSettings s = new ServerSettings();
        try {
            if (Files.exists(file())) s = JSON.readValue(file().toFile(), ServerSettings.class);
        } catch (IOException e) {
            // A damaged file must not be silently replaced by new secrets (the database would lock us out):
            // keep it aside and say so in the log; LocalStack reports "cannot read its settings".
            s = new ServerSettings();
            s.damaged = true;
        }
        return s;
    }

    @JsonIgnore
    public boolean damaged;

    public synchronized void save() throws IOException {
        Files.createDirectories(file().getParent());
        Path tmp = file().resolveSibling("server.json.tmp");
        Files.write(tmp, JSON.writeValueAsBytes(this));
        try {
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Makes the secrets that are never typed, once. True when something was made (save afterwards). */
    public synchronized boolean ensureSecrets() {
        boolean made = false;
        if (dbPassword.isBlank()) { dbPassword = random(32); made = true; }
        if (tokenSecret.isBlank()) { tokenSecret = random(64); made = true; }
        if (upiAlertToken.isBlank()) { upiAlertToken = random(40); made = true; }
        return made;
    }

    @JsonIgnore
    public String localUrl() {
        return "http://127.0.0.1:" + httpPort;
    }

    @JsonIgnore
    public boolean hasTunnel() {
        return tunnelToken != null && !tunnelToken.isBlank();
    }

    @JsonIgnore
    public boolean hasCloud() {
        return cloudUrl != null && !cloudUrl.isBlank() && cloudKey != null && !cloudKey.isBlank();
    }

    static String random(int length) {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder(length);
        for (int i = 0; i < length; i++) b.append(LETTERS.charAt(r.nextInt(LETTERS.length())));
        return b.toString();
    }

    /** A password file for the database tools: written, used, deleted. */
    static Path writeSecretFile(Path dir, String name, String secret) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, secret.getBytes(StandardCharsets.UTF_8));
        return p;
    }
}
