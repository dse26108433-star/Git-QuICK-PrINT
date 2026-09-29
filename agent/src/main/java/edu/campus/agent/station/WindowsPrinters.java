package edu.campus.agent.station;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.agent.print.CapabilityDiscovery;
import edu.campus.agent.print.PrinterDiscovery;

import javax.print.PrintService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * The printer scan: every printer installed in Windows on this PC, with what
 * staff need to choose (colour or not, connected how, working or not), what
 * it can do (paper sizes, two-sided, stapling...: CapabilityDiscovery), and
 * whether it is a "virtual" printer that only makes a file.
 */
public final class WindowsPrinters {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> VIRTUAL = List.of("print to pdf", "xps", "onenote", "fax", "pdf", "send to");

    private WindowsPrinters() {
    }

    public static List<Map<String, Object>> scan() {
        Map<String, JsonNode> details = windowsDetails();
        List<String> names = PrinterDiscovery.all().stream().map(PrintService::getName).toList();
        Map<String, CapabilityDiscovery.Discovered> features = CapabilityDiscovery.discover(names);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrintService s : PrinterDiscovery.all()) {
            String name = s.getName();
            JsonNode d = details.get(name.toLowerCase(Locale.ROOT));
            String driver = d == null ? "" : d.path("DriverName").asText("");
            String port = d == null ? "" : d.path("PortName").asText("");
            String status = d == null ? "" : d.path("Status").asText("");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("color", PrinterDiscovery.reportsColor(s));
            m.put("driver", driver);
            m.put("port", port);
            m.put("connection", connection(port));
            m.put("status", status.isBlank() ? "Normal" : status);
            m.put("virtual", isVirtual(name, driver));
            CapabilityDiscovery.Discovered f = features.get(name);
            if (f != null) {
                m.put("capabilities", f.capabilities());
                m.put("capabilitiesHash", CapabilityDiscovery.hash(f.capabilities()));
            }
            out.add(m);
        }
        // Real printers first, then by name.
        out.sort(Comparator.comparing((Map<String, Object> m) -> (Boolean) m.get("virtual"))
                .thenComparing(m -> ((String) m.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean isVirtual(String name, String driver) {
        String all = (name + " " + driver).toLowerCase(Locale.ROOT);
        return VIRTUAL.stream().anyMatch(all::contains);
    }

    private static String connection(String port) {
        String p = port.toUpperCase(Locale.ROOT);
        if (p.startsWith("USB")) return "USB cable";
        if (p.startsWith("IP_") || p.startsWith("WSD") || p.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) return "Network";
        if (p.startsWith("\\\\")) return "Shared by another PC";
        if (p.startsWith("LPT") || p.startsWith("COM")) return "Cable";
        return "";                       // files, virtual ports: nothing useful to say
    }

    /** Driver, port and status from Windows itself. Empty if PowerShell is not available. */
    private static Map<String, JsonNode> windowsDetails() {
        Map<String, JsonNode> map = new HashMap<>();
        String ps = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
                + "Get-Printer | Select-Object Name,DriverName,PortName,@{n='Status';e={[string]$_.PrinterStatus}} "
                + "| ConvertTo-Json -Compress";
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) return map;
            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return map;
            JsonNode root = JSON.readTree(text.substring(Math.max(0, Math.min(indexOf(text, '['), indexOf(text, '{')))));
            for (JsonNode n : root.isArray() ? root : List.of(root)) {
                map.put(n.path("Name").asText("").toLowerCase(Locale.ROOT), n);
            }
        } catch (Exception ignored) {
            // Names and colour from Java are enough to set printers up.
        }
        return map;
    }

    private static int indexOf(String s, char c) {
        int i = s.indexOf(c);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
