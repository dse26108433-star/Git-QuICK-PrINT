package edu.campus.print.printing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Print settings in a few words, for the counter screen: "A4 · two-sided · 2 per sheet · staple top-left". */
public final class SettingsText {

    private SettingsText() {
    }

    public static String of(PrintSettings s, boolean picture, Map<String, String> mediaNames) {
        if (s == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add(s.color() ? "Colour" : "B/W");
        parts.add(PaperSize.byId(s.paperSize()).map(PaperSize::id).orElse(s.paperSize()));
        if ("LONG_EDGE".equals(s.duplex())) parts.add("two-sided");
        if ("SHORT_EDGE".equals(s.duplex())) parts.add("two-sided (short edge)");
        if (s.pagesPerSheet() > 1) parts.add(s.pagesPerSheet() + " per sheet");
        if (!"AUTO".equals(s.orientation())) parts.add(s.orientation().toLowerCase());
        switch (s.scaling()) {
            case "ACTUAL" -> parts.add("actual size");
            case "CUSTOM" -> parts.add(s.scalePercent() + " %");
            case "FILL" -> parts.add("fill page");
            default -> { }
        }
        if (s.borderless()) parts.add("borderless");
        else if (s.marginMm() != PrintSettings.DEFAULT_MARGIN_MM) parts.add(s.marginMm() + " mm margins");
        if (picture && s.rotation() != 0) parts.add("turned " + s.rotation() + "°");
        if (picture && !s.center()) parts.add("top-left");
        if (s.staple() != null) parts.add(label("STAPLE", s.staple()));
        if (s.punch() != null) parts.add(label("PUNCH", s.punch()));
        if (s.bind() != null) parts.add(label("BIND", s.bind()));
        if (s.mediaType() != null) {
            parts.add((mediaNames == null ? s.mediaType() : mediaNames.getOrDefault(s.mediaType(), s.mediaType()))
                    + " paper");
        }
        if ("HIGH".equals(s.quality())) parts.add("high quality");
        if (s.copies() > 1 && !s.collate()) parts.add("uncollated");
        return String.join(" · ", parts);
    }

    private static String label(String group, String position) {
        return Finishing.LABELS.getOrDefault(group + "_" + position, group + " " + position).toLowerCase()
                .replace(":", "");
    }
}
