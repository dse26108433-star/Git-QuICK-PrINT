package edu.campus.agent.station;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * What the Station app remembers on this PC, in
 * %LOCALAPPDATA%\CampusPrint\station.json (only this Windows user can read it).
 *
 * Filled in by the setup wizard: the server address, and the PC's own sign-in
 * (agentId + agentSecret) that the server gave it when it was registered.
 */
public class StationConfig {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String backendUrl = "";
    public String agentId = "";
    public String agentSecret = "";
    public String pcName = "";
    /** The counter password, so staff do not have to type it on this PC every time. */
    public String counterPassword = "";
    /**
     * The counter sign-in the server gave this PC for that password (30 days,
     * renewed by itself). With it the counter keeps working even while the
     * server pauses new sign-ins because someone keeps guessing the password.
     */
    public String counterSession = "";

    /** Everything the app keeps: settings, logs, and files while they print. */
    public static Path dir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) base = System.getProperty("user.home");
        return Path.of(base, "CampusPrint");
    }

    private static Path file() {
        return dir().resolve("station.json");
    }

    @JsonIgnore
    public boolean isConfigured() {
        return !backendUrl.isBlank() && !agentId.isBlank() && !agentSecret.isBlank();
    }

    public static StationConfig load() {
        StationConfig c = new StationConfig();
        try {
            if (Files.exists(file())) {
                c = JSON.readValue(file().toFile(), StationConfig.class);
            }
        } catch (IOException e) {
            // A damaged file: start the setup again rather than refuse to open.
        }
        if (c.backendUrl.isBlank()) c.backendUrl = defaultBackendUrl();
        return c;
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(dir());
        Path tmp = dir().resolve("station.json.tmp");
        JSON.writeValue(tmp.toFile(), this);
        Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * A server address built into this copy of the app, so an installer made for
     * one college is already pointed at its server: build-installer.ps1 -BackendUrl
     * sets it (as -Dcampusprint.backendUrl); station-defaults.properties also works.
     */
    public static String defaultBackendUrl() {
        String built = System.getProperty("campusprint.backendUrl", "").trim();
        if (!built.isEmpty()) return built;
        try (InputStream in = StationConfig.class.getResourceAsStream("/station-defaults.properties")) {
            if (in == null) return "";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("backendUrl", "").trim();
        } catch (IOException e) {
            return "";
        }
    }
}
