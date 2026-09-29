package edu.campus.agent.print;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Settings Java cannot ask a Windows printer for directly (stapling, hole
 * punching, binding, borderless, paper type, and sometimes two-sided or high
 * quality) go through the printer's "print ticket", the same settings the
 * printer's own dialog changes:
 *
 *   1. start from the printer's defaults, add what this document needs, and let
 *      the driver check it (MergeAndValidatePrintTicket). If the driver drops or
 *      changes anything we asked for, nothing is printed: the document goes back
 *      to the queue for another printer (never print something else than chosen);
 *   2. make that this Windows user's printer settings, print (Java starts from
 *      them), and put the previous settings back right after.
 *
 * The previous settings are kept on disk first, so if the PC loses power in
 * between they are put back when the Station starts (restoreLeftovers).
 */
public final class PrintTicket {

    private static final Logger log = LoggerFactory.getLogger(PrintTicket.class);

    /** One setting: a Print Schema feature and the option to choose, e.g. psk:JobStapleAllDocuments = psk:StapleTopLeft. */
    public record Choice(String feature, String option) {}

    /** The driver would not do everything we asked. */
    public static class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    private final Path dir;

    public PrintTicket(Path workDir) {
        this.dir = workDir.resolve("tickets");
    }

    /**
     * Checks and applies; returns what restore() needs. Throws Refused if the
     * driver would change any choice, or if Windows cannot do it at all.
     */
    public Path apply(String printer, List<Choice> choices) throws Exception {
        Files.createDirectories(dir);
        Path saved = savedFile(printer);
        Path delta = Files.createTempFile(dir, "delta", ".xml");
        try {
            Files.writeString(delta, deltaXml(choices), StandardCharsets.UTF_8);
            Map<String, String> r = run(printer, "apply", delta, saved);
            if (r.containsKey("ERROR")) throw new Refused("Windows could not set the printer: " + r.get("ERROR"));
            byte[] validated = Base64.getDecoder().decode(r.getOrDefault("VALIDATED", ""));
            List<String> missing = missing(validated, choices);
            if (!missing.isEmpty()) {
                if (r.containsKey("APPLIED")) restore(printer);
                throw new Refused("The printer's driver would not do: " + String.join(", ", missing));
            }
            if (!r.containsKey("APPLIED")) throw new Refused("Windows did not take the printer settings.");
            return saved;
        } finally {
            Files.deleteIfExists(delta);
        }
    }

    /** Only checks (nothing changes on the printer). Returns the choices the driver would not do. */
    public List<String> check(String printer, List<Choice> choices) throws Exception {
        Files.createDirectories(dir);
        Path delta = Files.createTempFile(dir, "delta", ".xml");
        try {
            Files.writeString(delta, deltaXml(choices), StandardCharsets.UTF_8);
            Map<String, String> r = run(printer, "check", delta, null);
            if (r.containsKey("ERROR")) throw new Refused("Windows could not check the printer: " + r.get("ERROR"));
            return missing(Base64.getDecoder().decode(r.getOrDefault("VALIDATED", "")), choices);
        } finally {
            Files.deleteIfExists(delta);
        }
    }

    /** Puts this printer's previous settings back. Safe to call when nothing was changed. */
    public void restore(String printer) {
        Path saved = savedFile(printer);
        if (!Files.exists(saved)) return;
        try {
            Map<String, String> r = run(printer, "restore", null, saved);
            if (r.containsKey("RESTORED")) {
                Files.deleteIfExists(saved);
            } else {
                log.warn("Printer settings of \"{}\" not put back yet: {}", printer, r.getOrDefault("ERROR", "?"));
            }
        } catch (Exception e) {
            log.warn("Printer settings of \"{}\" not put back yet: {}", printer, e.toString());
        }
    }

    /** After a crash or power cut: put back every printer's settings that were changed for a document. */
    public void restoreLeftovers(List<String> printers) {
        for (String p : printers) restore(p);
    }

    // ------------------------------------------------------------------ the ticket

    static String deltaXml(List<Choice> choices) {
        Map<String, String> ns = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder();
        for (Choice c : choices) {
            body.append("  <psf:Feature name=\"").append(xmlName(c.feature(), ns)).append("\">")
                    .append("<psf:Option name=\"").append(xmlName(c.option(), ns)).append("\"/></psf:Feature>\n");
        }
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<psf:PrintTicket ")
                .append("xmlns:psf=\"").append(PrintCapabilitiesXml.PSF).append("\" ")
                .append("xmlns:psk=\"").append(PrintCapabilitiesXml.PSK).append("\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"");
        ns.forEach((uri, prefix) -> x.append(" xmlns:").append(prefix).append("=\"").append(escape(uri)).append("\""));
        return x.append(" version=\"1\">\n").append(body).append("</psf:PrintTicket>\n").toString();
    }

    /** "psk:Name" stays; "{uri}Name" gets a prefix declared on the ticket. */
    private static String xmlName(String id, Map<String, String> ns) {
        if (!id.startsWith("{")) return escape(id);
        String uri = id.substring(1, id.indexOf('}'));
        String prefix = ns.computeIfAbsent(uri, u -> "cp" + ns.size());
        return prefix + ":" + escape(id.substring(id.indexOf('}') + 1));
    }

    /** The choices the validated ticket does not contain as asked. */
    static List<String> missing(byte[] validated, List<Choice> choices) throws Exception {
        List<String> out = new ArrayList<>();
        if (validated.length == 0) {
            for (Choice c : choices) out.add(c.option());
            return out;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(validated));
        Map<String, String> chosen = new LinkedHashMap<>();
        collect(doc.getDocumentElement(), chosen);
        for (Choice c : choices) {
            if (!c.option().equals(chosen.get(c.feature()))) out.add(c.option());
        }
        return out;
    }

    private static void collect(Element e, Map<String, String> out) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element f && PrintCapabilitiesXml.PSF.equals(f.getNamespaceURI())
                    && "Feature".equals(f.getLocalName())) {
                String feature = PrintCapabilitiesXml.qualified(f, f.getAttribute("name"));
                for (Node o = f.getFirstChild(); o != null; o = o.getNextSibling()) {
                    if (o instanceof Element opt && "Option".equals(opt.getLocalName())) {
                        out.put(feature, PrintCapabilitiesXml.qualified(opt, opt.getAttribute("name")));
                    }
                }
                collect(f, out);
            }
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ Windows

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
            "try {",
            "  Add-Type -AssemblyName System.Printing, ReachFramework",
            "  $n = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:CP_PRINTER))",
            "  if ($n.StartsWith('\\\\')) { $i = $n.IndexOf('\\', 2); $srv = New-Object System.Printing.PrintServer($n.Substring(0, $i)); $q = $srv.GetPrintQueue($n.Substring($i + 1)) }",
            "  else { $srv = New-Object System.Printing.LocalPrintServer; $q = $srv.GetPrintQueue($n) }",
            "  if ($env:CP_MODE -eq 'restore') {",
            "    $fs = [IO.File]::OpenRead($env:CP_SAVED)",
            "    try { $q.UserPrintTicket = New-Object System.Printing.PrintTicket($fs); $q.Commit() } finally { $fs.Close() }",
            "    'RESTORED'",
            "  } else {",
            "    $fs = [IO.File]::OpenRead($env:CP_DELTA)",
            "    try { $delta = New-Object System.Printing.PrintTicket($fs) } finally { $fs.Close() }",
            "    $res = $q.MergeAndValidatePrintTicket($q.DefaultPrintTicket, $delta)",
            "    'VALIDATED ' + [Convert]::ToBase64String($res.ValidatedPrintTicket.GetXmlStream().ToArray())",
            "    if ($env:CP_MODE -eq 'apply') {",
            "      if (-not (Test-Path -LiteralPath $env:CP_SAVED)) {",
            "        $before = $q.UserPrintTicket; if ($before -eq $null) { $before = $q.DefaultPrintTicket }",
            "        [IO.File]::WriteAllBytes($env:CP_SAVED, $before.GetXmlStream().ToArray())",
            "      }",
            "      $q.UserPrintTicket = $res.ValidatedPrintTicket; $q.Commit()",
            "      'APPLIED'",
            "    }",
            "  }",
            "} catch { 'ERROR ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.Exception.Message)) }");

    private Map<String, String> run(String printer, String mode, Path delta, Path saved) throws Exception {
        if (!SpoolerMonitor.isWindows()) throw new Refused("Printer settings like stapling need Windows.");
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Sta",
                "-EncodedCommand", encoded).redirectErrorStream(true);
        pb.environment().put("CP_PRINTER", Base64.getEncoder().encodeToString(printer.getBytes(StandardCharsets.UTF_8)));
        pb.environment().put("CP_MODE", mode);
        if (delta != null) pb.environment().put("CP_DELTA", delta.toString());
        if (saved != null) pb.environment().put("CP_SAVED", saved.toString());
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new Refused("Windows took too long to set the printer.");
        }
        Map<String, String> r = new LinkedHashMap<>();
        for (String line : new String(out, StandardCharsets.UTF_8).split("\\R")) {
            line = line.trim();
            int sp = line.indexOf(' ');
            String key = sp < 0 ? line : line.substring(0, sp);
            String value = sp < 0 ? "" : line.substring(sp + 1);
            if (key.equals("ERROR")) {
                try {
                    value = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (!key.isEmpty()) r.put(key, value);
        }
        return r;
    }

    private Path savedFile(String printer) {
        try {
            String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(printer.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return dir.resolve("saved-" + h + ".xml");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
