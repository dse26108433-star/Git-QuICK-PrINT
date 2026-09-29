package edu.campus.print.printing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out printers.effective: what the printer can do (capabilities, found
 * by the Station from Windows) narrowed down to what staff offer.
 *
 * Defaults when staff have not chosen:
 *   paper sizes  the common ones the printer has (A4, A3, A5, Legal, Folio)
 *   two-sided, borderless, high quality: everything the printer has
 *   finishing    none (staff tick each option after a test print with it)
 *   paper types  none (students get the paper that is loaded)
 *
 * A printer whose features could not be read counts as A4 one-sided, unless
 * staff declared more (then staff are trusted).
 */
public final class EffectiveFeatures {

    private EffectiveFeatures() {
    }

    public static PrinterFeatures compute(JsonNode caps, OfferedFeatures offered) {
        if (caps == null || caps.isNull() || !caps.isObject() || caps.path("paperSizes").isMissingNode()) {
            return declared(offered);
        }
        List<String> sizes = new ArrayList<>();
        Double minMargin = null;
        for (JsonNode p : caps.path("paperSizes")) {
            String id = p.path("id").asText("");
            if (PaperSize.known(id) && !sizes.contains(id)) sizes.add(id);
            if ("A4".equals(id) && p.path("marginsMm").isArray()) {
                double worst = 0;
                for (JsonNode m : p.path("marginsMm")) worst = Math.max(worst, m.asDouble(0));
                minMargin = Math.round(worst * 10) / 10.0;
            }
        }
        List<String> wantedSizes = offered != null && offered.paperSizes() != null
                ? offered.paperSizes() : PaperSize.DEFAULT_OFFERED;
        List<String> paper = sorted(intersect(sizes, wantedSizes), PaperSize::rank);
        if (paper.isEmpty()) {
            // Never leave a printer with nothing: fall back to A4, or what it has.
            paper = sizes.contains("A4") ? List.of("A4") : sizes.isEmpty() ? List.of() : List.of(sizes.get(0));
        }

        boolean duplex = caps.path("duplex").asBoolean(false) && (offered == null || offered.duplex() == null
                || offered.duplex());

        List<String> finishingCan = new ArrayList<>();
        for (JsonNode f : caps.path("finishing")) {
            if (Finishing.known(f.asText())) finishingCan.add(f.asText());
        }
        // finishing only once staff ticked it (after a test print): drivers often list a finisher that is not fitted
        List<String> finishing = sorted(offered != null && offered.finishing() != null
                ? intersect(finishingCan, offered.finishing()) : List.of(), Finishing::rank);

        Map<String, String> mediaNames = new LinkedHashMap<>();
        for (JsonNode m : caps.path("mediaTypes")) {
            String id = m.path("id").asText("");
            if (!id.isEmpty()) mediaNames.put(id, m.path("name").asText(id));
        }
        List<String> media = offered != null && offered.mediaTypes() != null
                ? intersect(new ArrayList<>(mediaNames.keySet()), offered.mediaTypes()) : List.of();
        Map<String, String> names = new LinkedHashMap<>();
        for (String id : media) names.put(id, mediaNames.get(id));

        boolean borderless = caps.path("borderless").asBoolean(false)
                && (offered == null || offered.borderless() == null || offered.borderless());
        boolean high = caps.path("highQuality").asBoolean(false)
                && (offered == null || offered.highQuality() == null || offered.highQuality());
        Integer maxCopies = caps.hasNonNull("maxCopies") ? caps.path("maxCopies").asInt() : null;

        return new PrinterFeatures(paper, duplex, finishing, media, names, borderless, high, maxCopies, minMargin);
    }

    /** No features read from Windows: what staff declared, else plain A4. */
    private static PrinterFeatures declared(OfferedFeatures o) {
        if (o == null) return PrinterFeatures.PLAIN_A4;
        List<String> paper = o.paperSizes() == null ? List.of("A4")
                : sorted(o.paperSizes().stream().filter(PaperSize::known).distinct().toList(), PaperSize::rank);
        if (paper.isEmpty()) paper = List.of("A4");
        List<String> finishing = o.finishing() == null ? List.of()
                : sorted(o.finishing().stream().filter(Finishing::known).distinct().toList(), Finishing::rank);
        return new PrinterFeatures(paper, Boolean.TRUE.equals(o.duplex()), finishing, List.of(), Map.of(),
                Boolean.TRUE.equals(o.borderless()), false, null, null);
    }

    private static List<String> intersect(List<String> have, List<String> wanted) {
        Set<String> w = new LinkedHashSet<>(wanted);
        return have.stream().filter(w::contains).distinct().toList();
    }

    private static List<String> sorted(List<String> list, java.util.function.ToIntFunction<String> rank) {
        List<String> out = new ArrayList<>(list);
        out.sort(Comparator.comparingInt(rank));
        return List.copyOf(out);
    }
}
