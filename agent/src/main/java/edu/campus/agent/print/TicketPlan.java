package edu.campus.agent.print;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.agent.net.Messages.JobSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which of a document's settings must go through the printer's print ticket
 * (see PrintTicket), because Java cannot ask for them: stapling, hole punching,
 * binding, borderless, paper type, and two-sided or high quality on drivers
 * that only offer them that way.
 *
 * On a printer that CAN staple, punch or bind, a document that asks for none
 * of it says so explicitly ("none"), so a default set on the printer can never
 * staple anybody's pages by surprise.
 */
public final class TicketPlan {

    private TicketPlan() {
    }

    public static List<PrintTicket.Choice> choices(JobSettings s, Optional<CapabilityDiscovery.Discovered> known) {
        List<PrintTicket.Choice> out = new ArrayList<>();
        JsonNode caps = known.map(CapabilityDiscovery.Discovered::capabilities).orElse(null);
        PrintCapabilitiesXml.Parsed xml = known.map(CapabilityDiscovery.Discovered::xml).orElse(null);

        finishing(out, xml, s.staple(), "STAPLE", CapabilityDiscovery.STAPLES,
                "psk:JobStapleAllDocuments", "psk:DocumentStaple");
        finishing(out, xml, s.punch(), "PUNCH", CapabilityDiscovery.PUNCHES,
                "psk:JobHolePunch", "psk:DocumentHolePunch");
        finishing(out, xml, s.bind(), "BIND", CapabilityDiscovery.BINDS,
                "psk:JobBindAllDocuments", "psk:DocumentBinding");

        if (s.marginMm() == 0) out.add(new PrintTicket.Choice("psk:PageBorderless", "psk:Borderless"));
        if (s.mediaType() != null) out.add(new PrintTicket.Choice("psk:PageMediaType", s.mediaType()));
        if ("HIGH".equals(s.quality()) && caps != null && caps.hasNonNull("highQualityTicket")) {
            out.add(new PrintTicket.Choice("psk:PageOutputQuality", caps.path("highQualityTicket").asText()));
        }
        if (s.twoSided() && caps != null && caps.path("duplexByTicket").asBoolean(false)) {
            String feature = xml != null && !xml.optionIds("psk:DocumentDuplex").isEmpty()
                    && xml.optionIds("psk:JobDuplexAllDocumentsContiguously").isEmpty()
                    ? "psk:DocumentDuplex" : "psk:JobDuplexAllDocumentsContiguously";
            out.add(new PrintTicket.Choice(feature,
                    "SHORT_EDGE".equals(s.duplex()) ? "psk:TwoSidedShortEdge" : "psk:TwoSidedLongEdge"));
        }
        return out;
    }

    /** The chosen finishing, or an explicit "none" when the printer has that finishing. */
    private static void finishing(List<PrintTicket.Choice> out, PrintCapabilitiesXml.Parsed xml, String position,
                                  String group, Map<String, String> options, String... features) {
        String feature = features[0];
        Set<String> offered = Set.of();
        if (xml != null) {
            for (String f : features) {
                if (!xml.optionIds(f).isEmpty()) {
                    feature = f;
                    offered = xml.optionIds(f);
                    break;
                }
            }
        }
        if (position != null) {
            String wanted = group + "_" + position;
            String option = options.entrySet().stream().filter(e -> e.getValue().equals(wanted))
                    .map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unknown finishing " + wanted));
            out.add(new PrintTicket.Choice(feature, option));
        } else if (offered.contains("psk:None") && offered.stream().anyMatch(options::containsKey)) {
            out.add(new PrintTicket.Choice(feature, "psk:None"));
        }
    }
}
