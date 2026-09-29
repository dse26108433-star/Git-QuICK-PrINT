package edu.campus.agent.print;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the "Print Capabilities" document Windows gives for a printer (the
 * Print Schema, what the printer's own settings dialog is built from): paper
 * sizes, two-sided, colour, paper types, trays, resolutions, borderless,
 * copies, collation, stapling, hole punching, binding, quality modes.
 *
 * Names in the document are qualified (psk:ISOA4, ns0000:EpsonPhotoPaper...).
 * Standard ones (Print Schema keywords) are kept as "psk:Name"; a printer
 * maker's own as "{namespace}Name", which is what a print ticket needs to
 * select the same option again.
 */
public final class PrintCapabilitiesXml {

    public static final String PSF = "http://schemas.microsoft.com/windows/2003/08/printing/printschemaframework";
    public static final String PSK = "http://schemas.microsoft.com/windows/2003/08/printing/printschemakeywords";

    /** One option of a feature, e.g. a paper type. */
    public record Option(String id, String name, Map<String, String> properties) {}

    /** The features found: feature id -> its options (in document order). */
    public record Parsed(Map<String, List<Option>> features, Map<String, Map<String, String>> parameters) {

        public List<Option> options(String... featureIds) {
            for (String f : featureIds) {
                List<Option> o = features.get(f);
                if (o != null) return o;
            }
            return List.of();
        }

        public Set<String> optionIds(String... featureIds) {
            Set<String> out = new LinkedHashSet<>();
            for (Option o : options(featureIds)) out.add(o.id());
            return out;
        }

        /** A number from a parameter definition, e.g. the most copies. */
        public Integer parameterInt(String parameterId, String property) {
            Map<String, String> p = parameters.get(parameterId);
            if (p == null || p.get(property) == null) return null;
            try {
                return Integer.parseInt(p.get(property).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private PrintCapabilitiesXml() {
    }

    public static Parsed parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml));
        Element root = doc.getDocumentElement();

        Map<String, List<Option>> features = new LinkedHashMap<>();
        Map<String, Map<String, String>> parameters = new LinkedHashMap<>();
        for (Element feature : children(root, "Feature")) {
            collectFeature(feature, features);
        }
        for (Element p : children(root, "ParameterDef")) {
            String id = qualified(p, p.getAttribute("name"));
            Map<String, String> props = new LinkedHashMap<>();
            for (Element prop : children(p, "Property")) {
                props.put(qualified(prop, prop.getAttribute("name")), value(prop));
            }
            parameters.put(id, props);
        }
        return new Parsed(features, parameters);
    }

    /** Features can contain sub-features (e.g. N-up's direction); keep them all, by id. */
    private static void collectFeature(Element feature, Map<String, List<Option>> out) {
        String id = qualified(feature, feature.getAttribute("name"));
        List<Option> options = new ArrayList<>();
        for (Element o : children(feature, "Option")) {
            String raw = o.getAttribute("name");
            Map<String, String> props = new LinkedHashMap<>();
            for (Element sp : children(o, "ScoredProperty")) {
                props.put(qualified(sp, sp.getAttribute("name")), value(sp));
            }
            String display = null;
            for (Element p : children(o, "Property")) {
                String pn = qualified(p, p.getAttribute("name"));
                if ("psk:DisplayName".equals(pn)) display = value(p);
                else props.put(pn, value(p));
            }
            String oid = raw.isEmpty() ? null : qualified(o, raw);
            String name = display != null && !display.isBlank() ? display.trim()
                    : oid == null ? null : prettify(oid);
            options.add(new Option(oid, name, props));
        }
        out.put(id, options);
        for (Element sub : children(feature, "Feature")) collectFeature(sub, out);
    }

    /** "psk:ISOA4" -> "psk:ISOA4"; "ns0000:EpsonPhotoPaper" -> "{http://...}EpsonPhotoPaper". */
    static String qualified(Element context, String name) {
        int colon = name.indexOf(':');
        if (colon < 0) return name;
        String prefix = name.substring(0, colon);
        String local = name.substring(colon + 1);
        String uri = context.lookupNamespaceURI(prefix);
        if (uri == null) return name;
        if (PSK.equals(uri)) return "psk:" + local;
        if (PSF.equals(uri)) return "psf:" + local;
        return "{" + uri + "}" + local;
    }

    /** The words for an option without a display name: "{...}EpsonPhotoPaperGlossy" -> "Epson Photo Paper Glossy". */
    static String prettify(String id) {
        String local = id.startsWith("{") ? id.substring(id.indexOf('}') + 1)
                : id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return local.replaceAll("([a-z])([A-Z0-9])", "$1 $2").replaceAll("([A-Z])([A-Z][a-z])", "$1 $2").trim();
    }

    private static String value(Element e) {
        for (Element v : children(e, "Value")) return v.getTextContent();
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && PSF.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }
}
