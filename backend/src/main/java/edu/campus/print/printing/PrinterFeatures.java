package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * What students can really get from one printer: what it can do (found by
 * the Station) narrowed down to what staff offer. Stored as
 * printers.effective and used by the print queue, the settings check and the
 * website. Worked out by EffectiveFeatures.
 *
 * @param paperSizes     PaperSize ids
 * @param duplex         can print on both sides
 * @param finishing      Finishing ids, e.g. STAPLE_TOP_LEFT
 * @param mediaTypes     paper type ids students may pick (empty = the usual paper only)
 * @param mediaTypeNames the words for those paper types
 * @param borderless     can print to the edge of the paper
 * @param highQuality    has a better-than-normal quality mode
 * @param maxCopies      what the driver says it can repeat by itself (for information)
 * @param minMarginMm    the widest edge the printer cannot print on (for information)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrinterFeatures(
        List<String> paperSizes,
        boolean duplex,
        List<String> finishing,
        List<String> mediaTypes,
        Map<String, String> mediaTypeNames,
        boolean borderless,
        boolean highQuality,
        Integer maxCopies,
        Double minMarginMm
) {
    /** A printer nobody has looked at yet: A4, one side, nothing else. */
    public static final PrinterFeatures PLAIN_A4 = new PrinterFeatures(List.of("A4"), false, List.of(), List.of(),
            Map.of(), false, false, null, null);

    public PrinterFeatures {
        paperSizes = paperSizes == null ? List.of("A4") : List.copyOf(paperSizes);
        finishing = finishing == null ? List.of() : List.copyOf(finishing);
        mediaTypes = mediaTypes == null ? List.of() : List.copyOf(mediaTypes);
        mediaTypeNames = mediaTypeNames == null ? Map.of() : Map.copyOf(mediaTypeNames);
    }
}
