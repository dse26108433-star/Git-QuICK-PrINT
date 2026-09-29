package edu.campus.agent.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Finds out what each printer can really do, from Windows itself:
 *
 *   1. Print Capabilities (the Print Schema document behind the printer's own
 *      settings dialog): two-sided, colour, paper types, trays, borderless,
 *      stapling, hole punching, quality modes, copies, collation.
 *   2. Java's view of the same driver: the paper sizes it can ask for (and the
 *      printable area of each), two-sided, colour, copies, resolutions, trays.
 *   3. Windows' printer list (WMI): the driver name, and "can bind".
 *
 * Only what can really be asked for when printing counts. The answer goes to
 * the server (which narrows it to what staff offer) and so to the website:
 * students only ever see options the printers can do.
 */
public final class CapabilityDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDiscovery.class);
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final int WMI_BIND = 10;

    private CapabilityDiscovery() {
    }

    /** What Windows told about one printer (raw), before it is turned into capabilities. */
    public record WindowsInfo(String driver, Set<Integer> wmiCapabilities, PrintCapabilitiesXml.Parsed xml, String problem) {}

    /** A printer's capabilities, plus Windows' own description (needed to ask for the same options when printing). */
    public record Discovered(ObjectNode capabilities, PrintCapabilitiesXml.Parsed xml) {}

    /** Capabilities of each named printer that exists. Never throws; a printer Windows cannot describe gets Java's view only. */
    public static Map<String, Discovered> discover(List<String> windowsNames) {
        Map<String, WindowsInfo> windows = SpoolerMonitor.isWindows() ? windowsInfo(windowsNames) : Map.of();
        Map<String, Discovered> out = new LinkedHashMap<>();
        for (String name : windowsNames) {
            Optional<PrintService> service = PrinterDiscovery.find(name);
            if (service.isEmpty()) continue;
            try {
                WindowsInfo w = windows.get(name);
                out.put(name, new Discovered(describe(service.get(), w), w == null ? null : w.xml()));
            } catch (Exception e) {
                log.warn("Could not read the features of \"{}\": {}", name, e.toString());
            }
        }
        return out;
    }

    /** The version of a capabilities document (everything but the time it was found). */
    public static String hash(JsonNode caps) {
        try {
            ObjectNode copy = caps.deepCopy();
            copy.remove("discoveredAt");
            byte[] json = JSON.writeValueAsBytes(JSON.treeToValue(copy, Object.class));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json)).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ putting it together

    static ObjectNode describe(PrintService s, WindowsInfo w) {
        PrintCapabilitiesXml.Parsed x = w == null ? null : w.xml();
        ObjectNode c = JSON.createObjectNode();
        c.put("schema", 1);
        c.put("driver", w != null && w.driver() != null ? w.driver() : "");
        ArrayNode sources = c.putArray("sources");
        sources.add("java");
        if (x != null) sources.add("printcapabilities");
        if (w != null && !w.wmiCapabilities().isEmpty()) sources.add("wmi");
        if (w != null && w.problem() != null) c.put("problem", w.problem());

        // Colour: what the driver can print, not what staff decided (that is on the counter).
        Set<String> colors = x == null ? Set.of() : x.optionIds("psk:PageOutputColor");
        boolean javaColor = supports(s, Chromaticity.COLOR) || PrinterDiscovery.reportsColor(s);
        c.put("color", colors.isEmpty() ? javaColor : colors.contains("psk:Color"));

        // Two-sided: Java asks for it through the driver's settings; Windows' own list is a second way in.
        Set<String> duplexX = x == null ? Set.of()
                : x.optionIds("psk:JobDuplexAllDocumentsContiguously", "psk:DocumentDuplex");
        boolean longJ = supports(s, Sides.TWO_SIDED_LONG_EDGE) || supports(s, Sides.DUPLEX);
        boolean shortJ = supports(s, Sides.TWO_SIDED_SHORT_EDGE) || supports(s, Sides.TUMBLE);
        boolean longX = duplexX.contains("psk:TwoSidedLongEdge");
        boolean shortX = duplexX.contains("psk:TwoSidedShortEdge");
        ArrayNode modes = c.putArray("duplexModes");
        modes.add("ONE_SIDED");
        if (longJ || longX) modes.add("LONG_EDGE");
        if (shortJ || shortX) modes.add("SHORT_EDGE");
        c.put("duplex", (longJ || longX) && (shortJ || shortX));
        c.put("duplexByTicket", !(longJ && shortJ) && longX && shortX);

        // Paper sizes Java can ask the driver for, with the edge each printer cannot reach. Some drivers
        // (inkjets) also list sizes they only shrink onto smaller paper (an A4 Epson lists A3): when Windows
        // describes the printer, only the sizes it really feeds (its Print Capabilities) count.
        Set<String> feeds = new HashSet<>();
        if (x != null) {
            for (PrintCapabilitiesXml.Option o : x.options("psk:PageMediaSize")) {
                Integer wu = number(o.properties().get("psk:MediaSizeWidth"));
                Integer hu = number(o.properties().get("psk:MediaSizeHeight"));
                if (wu != null && hu != null) PaperSizes.match(wu / 1000.0, hu / 1000.0).ifPresent(p -> feeds.add(p.id()));
            }
        }
        ArrayNode papers = c.putArray("paperSizes");
        ArrayNode others = c.putArray("otherPaperSizes");
        Set<String> seen = new HashSet<>();
        Object media = s.getSupportedAttributeValues(Media.class, null, null);
        if (media instanceof Media[] list) {
            for (Media m : list) {
                if (!(m instanceof MediaSizeName name)) continue;
                MediaSize size = MediaSize.getMediaSizeForName(name);
                if (size == null) continue;
                double wmm = size.getX(MediaSize.MM), hmm = size.getY(MediaSize.MM);
                Optional<PaperSizes.Size> known = PaperSizes.match(wmm, hmm);
                if (known.isEmpty() || (!feeds.isEmpty() && !feeds.contains(known.get().id()))) {
                    if (others.size() < 60) others.add(name.toString());
                    continue;
                }
                if (!seen.add(known.get().id())) continue;
                ObjectNode p = papers.addObject();
                p.put("id", known.get().id());
                p.put("name", name.toString());
                p.put("widthMm", round(wmm));
                p.put("heightMm", round(hmm));
                double[] margins = margins(s, name, wmm, hmm);
                if (margins != null) {
                    ArrayNode mm = p.putArray("marginsMm");
                    for (double v : margins) mm.add(round(v));
                }
            }
        }

        // Copies and collation.
        Integer maxX = x == null ? null : x.parameterInt("psk:JobCopiesAllDocuments", "psf:MaxValue");
        Object copies = s.getSupportedAttributeValues(Copies.class, null, null);
        int maxJ = copies instanceof CopiesSupported cs ? cs.getMembers()[cs.getMembers().length - 1][1] : 1;
        c.put("maxCopies", maxX != null ? maxX : maxJ);
        c.put("collate", supports(s, SheetCollate.COLLATED)
                || (x != null && x.optionIds("psk:DocumentCollate", "psk:JobCollateAllDocuments").contains("psk:Collated")));

        // Resolutions and quality.
        Set<Integer> dpis = new TreeSet<>();
        if (x != null) {
            for (PrintCapabilitiesXml.Option o : x.options("psk:PageResolution")) {
                Integer rx = number(o.properties().get("psk:ResolutionX"));
                if (rx != null && rx > 0) dpis.add(rx);
            }
        }
        Object res = s.getSupportedAttributeValues(PrinterResolution.class, null, null);
        if (res instanceof PrinterResolution[] rs) {
            for (PrinterResolution r : rs) {
                int d = r.getCrossFeedResolution(PrinterResolution.DPI);
                if (d > 0 && d < 10_000) dpis.add(d);
            }
        }
        ArrayNode dpiList = c.putArray("resolutionsDpi");
        dpis.forEach(dpiList::add);
        String highOption = null;
        if (x != null) {
            for (PrintCapabilitiesXml.Option o : x.options("psk:PageOutputQuality")) {
                String n = (o.id() == null ? "" : o.id()).toLowerCase(Locale.ROOT);
                if (n.endsWith("high") || n.contains("highquality") || n.endsWith("photographic") || n.contains("fine")
                        || n.contains("best")) {
                    highOption = o.id();
                    break;
                }
            }
        }
        boolean highJ = supports(s, PrintQuality.HIGH);
        c.put("highQuality", highJ || highOption != null);
        if (!highJ && highOption != null) c.put("highQualityTicket", highOption);

        // Trays (for staff: paper is taken from the tray that has the chosen size).
        ArrayNode trays = c.putArray("trays");
        if (x != null) {
            for (PrintCapabilitiesXml.Option o : x.options("psk:JobInputBin", "psk:DocumentInputBin", "psk:PageInputBin")) {
                if (o.name() != null) trays.add(o.name());
            }
        }
        if (trays.isEmpty() && media instanceof Media[] list) {
            for (Media m : list) if (m instanceof MediaTray t) trays.add(t.toString());
        }

        // Paper types (plain, glossy photo...), borderless.
        ArrayNode types = c.putArray("mediaTypes");
        if (x != null) {
            for (PrintCapabilitiesXml.Option o : x.options("psk:PageMediaType")) {
                if (o.id() == null) continue;
                ObjectNode t = types.addObject();
                t.put("id", o.id());
                t.put("name", o.name() == null ? o.id() : o.name());
            }
        }
        c.put("borderless", x != null && x.optionIds("psk:PageBorderless").contains("psk:Borderless"));

        // Finishing.
        ArrayNode finishing = c.putArray("finishing");
        if (x != null) {
            for (String id : x.optionIds("psk:JobStapleAllDocuments", "psk:DocumentStaple")) {
                String f = STAPLES.get(id);
                if (f != null) finishing.add(f);
            }
            for (String id : x.optionIds("psk:JobHolePunch", "psk:DocumentHolePunch")) {
                String f = PUNCHES.get(id);
                if (f != null) finishing.add(f);
            }
            // Many drivers list binding edges only as a layout preference (e.g. inkjets): only
            // printers that Windows says can really bind get it.
            if (w != null && w.wmiCapabilities().contains(WMI_BIND)) {
                for (String id : x.optionIds("psk:JobBindAllDocuments", "psk:DocumentBinding")) {
                    String f = BINDS.get(id);
                    if (f != null) finishing.add(f);
                }
            }
        }
        c.put("discoveredAt", Instant.now().toString());
        return c;
    }

    /** Campus Print finishing id -> the Print Schema option that asks for it, and back. */
    public static final Map<String, String> STAPLES = Map.of(
            "psk:StapleTopLeft", "STAPLE_TOP_LEFT", "psk:StapleTopRight", "STAPLE_TOP_RIGHT",
            "psk:StapleBottomLeft", "STAPLE_BOTTOM_LEFT", "psk:StapleBottomRight", "STAPLE_BOTTOM_RIGHT",
            "psk:StapleDualLeft", "STAPLE_DUAL_LEFT", "psk:StapleDualRight", "STAPLE_DUAL_RIGHT",
            "psk:StapleDualTop", "STAPLE_DUAL_TOP", "psk:StapleDualBottom", "STAPLE_DUAL_BOTTOM");
    public static final Map<String, String> PUNCHES = Map.of(
            "psk:LeftEdge", "PUNCH_LEFT", "psk:RightEdge", "PUNCH_RIGHT",
            "psk:TopEdge", "PUNCH_TOP", "psk:BottomEdge", "PUNCH_BOTTOM");
    public static final Map<String, String> BINDS = Map.of(
            "psk:BindLeft", "BIND_LEFT", "psk:BindRight", "BIND_RIGHT",
            "psk:BindTop", "BIND_TOP", "psk:BindBottom", "BIND_BOTTOM");

    // ------------------------------------------------------------------ Windows

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Continue'",
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
            "function B64([string]$s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }",
            "try { Add-Type -AssemblyName System.Printing } catch { 'NOSP ' + (B64 $_.Exception.Message) }",
            "$names = Get-Content -LiteralPath $env:CAMPUSPRINT_NAMES -Encoding UTF8",
            "$wmi = @{}",
            "try { Get-CimInstance Win32_Printer | ForEach-Object { $wmi[$_.Name] = $_ } } catch { }",
            "foreach ($n in $names) {",
            "  if (-not $n) { continue }",
            "  '===PRINTER ' + (B64 $n)",
            "  $w = $wmi[$n]",
            "  if ($w) { 'WMI ' + ($w.Capabilities -join ','); 'DRIVER ' + (B64 ([string]$w.DriverName)) }",
            "  try {",
            "    if ($n.StartsWith('\\\\')) { $i = $n.IndexOf('\\', 2); $srv = New-Object System.Printing.PrintServer($n.Substring(0, $i)); $q = $srv.GetPrintQueue($n.Substring($i + 1)) }",
            "    else { $srv = New-Object System.Printing.LocalPrintServer; $q = $srv.GetPrintQueue($n) }",
            "    $ms = $q.GetPrintCapabilitiesAsXml()",
            "    'XML ' + [Convert]::ToBase64String($ms.ToArray())",
            "  } catch { 'ERROR ' + (B64 $_.Exception.Message) }",
            "}",
            "'===END'");

    /** One PowerShell run for all printers (about a second each). */
    static Map<String, WindowsInfo> windowsInfo(List<String> names) {
        Map<String, WindowsInfo> out = new HashMap<>();
        Path list = null;
        try {
            list = Files.createTempFile("campusprint-printers", ".txt");
            Files.write(list, names, StandardCharsets.UTF_8);
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encoded).redirectErrorStream(true);
            pb.environment().put("CAMPUSPRINT_NAMES", list.toString());
            Process p = pb.start();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("Reading printer features from Windows took too long");
                return out;
            }
            String current = null;
            String driver = null;
            Set<Integer> wmi = new HashSet<>();
            PrintCapabilitiesXml.Parsed xml = null;
            String problem = null;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                line = line.trim();
                if (line.startsWith("===")) {
                    if (current != null) out.put(current, new WindowsInfo(driver, wmi, xml, problem));
                    current = line.startsWith("===PRINTER ") ? text(line.substring(11)) : null;
                    driver = null;
                    wmi = new HashSet<>();
                    xml = null;
                    problem = null;
                } else if (line.startsWith("WMI ")) {
                    for (String v : line.substring(4).split(",")) {
                        Integer n = number(v);
                        if (n != null) wmi.add(n);
                    }
                } else if (line.startsWith("DRIVER ")) {
                    driver = text(line.substring(7));
                } else if (line.startsWith("XML ")) {
                    try {
                        xml = PrintCapabilitiesXml.parse(Base64.getDecoder().decode(line.substring(4)));
                    } catch (Exception e) {
                        problem = "Windows' description of this printer could not be read";
                        log.warn("Print capabilities of \"{}\" unreadable: {}", current, e.toString());
                    }
                } else if (line.startsWith("ERROR ")) {
                    problem = text(line.substring(6));
                    log.info("Windows could not describe \"{}\": {}", current, problem);
                } else if (line.startsWith("NOSP ")) {
                    log.warn("System.Printing is not available: {}", text(line.substring(5)));
                }
            }
        } catch (Exception e) {
            log.warn("Could not ask Windows about the printers: {}", e.toString());
        } finally {
            if (list != null) {
                try {
                    Files.deleteIfExists(list);
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean supports(PrintService s, javax.print.attribute.Attribute a) {
        try {
            return s.isAttributeValueSupported(a, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** Left, top, right, bottom edges (mm) the printer cannot print on, for this paper. */
    private static double[] margins(PrintService s, MediaSizeName name, double wmm, double hmm) {
        try {
            PrintRequestAttributeSet set = new HashPrintRequestAttributeSet();
            set.add(name);
            Object v = s.getSupportedAttributeValues(MediaPrintableArea.class, null, set);
            if (v instanceof MediaPrintableArea[] areas && areas.length > 0) {
                MediaPrintableArea a = areas[0];
                double x = a.getX(MediaPrintableArea.MM), y = a.getY(MediaPrintableArea.MM);
                double w = a.getWidth(MediaPrintableArea.MM), h = a.getHeight(MediaPrintableArea.MM);
                return new double[]{x, y, Math.max(0, wmm - x - w), Math.max(0, hmm - y - h)};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String text(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return b64;
        }
    }

    private static Integer number(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
