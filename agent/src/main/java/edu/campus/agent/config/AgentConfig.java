package edu.campus.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Settings from agent.yml (next to print-agent.jar). The install script
 * writes that file for you. The printers themselves are NOT listed here:
 * they come from the database, so staff can add or switch them off without
 * touching this PC.
 */
public class AgentConfig {

    public String backendUrl = "";
    public String agentId = "";
    public String agentSecret = "";

    public int pollIntervalSeconds = 3;
    public int heartbeatSeconds = 20;

    public String workDir = "C:/ProgramData/CampusPrintAgent";
    public long maxFileSizeBytes = 50L * 1024 * 1024;
    public int tempRetentionMinutes = 60;

    /**
     * Print the pickup code small in the bottom-right corner of each order's
     * first page, so staff can match paper to students without an extra sheet.
     */
    public boolean pickupCodeOnPage = true;

    /**
     * Also print a separate cover sheet (huge code) for orders of at least this
     * many sheets (pages x copies). 0 = never, which saves the most paper.
     * If pickupCodeOnPage is false, every order gets a cover sheet.
     */
    public int coverSheetMinSheets = 0;

    public String printStrategy = "pdfbox";      // pdfbox | external
    public String externalToolPath = "C:/Program Files/SumatraPDF/SumatraPDF.exe";
    public int externalToolTimeoutSeconds = 180;

    /** Watch the Windows print queue so staff and students see the real outcome. */
    public boolean verifyViaSpooler = true;
    public int spoolerPollSeconds = 3;

    /**
     * Ask Windows whether each printer is ready (offline, paper out, jam...) and
     * take no new documents while it is not. Switch off only for a driver that
     * wrongly reports its printer offline while it prints fine.
     */
    public boolean checkPrinterStatus = true;

    public static AgentConfig load(Path file) throws Exception {
        AgentConfig cfg = Files.exists(file)
                ? new ObjectMapper(new YAMLFactory())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(file.toFile(), AgentConfig.class)
                : new AgentConfig();
        cfg.agentId = env("CAMPUS_AGENT_ID", cfg.agentId);
        cfg.agentSecret = env("CAMPUS_AGENT_SECRET", cfg.agentSecret);
        cfg.backendUrl = env("CAMPUS_BACKEND_URL", cfg.backendUrl);
        cfg.validate(file);
        return cfg;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? (fallback == null ? "" : fallback) : v;
    }

    /** Checks settings made in code (the Station app). Throws with a message for the user. */
    public AgentConfig check() {
        validate(Path.of("the Campus Print Station settings"));
        return this;
    }

    private void validate(Path file) {
        if (agentId.isBlank() || agentSecret.isBlank() || backendUrl.isBlank()) {
            throw new IllegalStateException("backendUrl, agentId and agentSecret must be set in "
                    + file.toAbsolutePath() + ". Get agentId/agentSecret from seed.sql step 3.");
        }
        backendUrl = backendUrl.trim().replaceAll("/+$", "");
        boolean local = backendUrl.startsWith("http://localhost") || backendUrl.startsWith("http://127.0.0.1")
                || backendUrl.matches("http://(10|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\..*");
        if (!backendUrl.startsWith("https://") && !local) {
            throw new IllegalStateException("backendUrl must start with https:// (http:// is only allowed "
                    + "for a backend on this PC or the local network while testing).");
        }
    }

    public Path work() { return Path.of(workDir); }
    public Duration pollInterval() { return Duration.ofSeconds(Math.max(1, pollIntervalSeconds)); }
    public Duration heartbeat() { return Duration.ofSeconds(Math.max(5, heartbeatSeconds)); }
    public Duration spoolerPoll() { return Duration.ofSeconds(Math.max(1, spoolerPollSeconds)); }
}
