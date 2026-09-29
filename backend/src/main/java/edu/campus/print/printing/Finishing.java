package edu.campus.print.printing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finishing a printer can do to each copy, as Windows reports it:
 * stapling, hole punching and binding. An id is GROUP_POSITION, e.g.
 * STAPLE_TOP_LEFT. A document's settings store only the position
 * ("staple": "TOP_LEFT"); the printer must list "STAPLE_TOP_LEFT".
 */
public final class Finishing {

    public static final List<String> GROUPS = List.of("STAPLE", "PUNCH", "BIND");

    /** Every finishing id with the words students see, in display order. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("STAPLE_TOP_LEFT", "Staple: top-left corner");
        LABELS.put("STAPLE_TOP_RIGHT", "Staple: top-right corner");
        LABELS.put("STAPLE_BOTTOM_LEFT", "Staple: bottom-left corner");
        LABELS.put("STAPLE_BOTTOM_RIGHT", "Staple: bottom-right corner");
        LABELS.put("STAPLE_DUAL_LEFT", "Two staples: left edge");
        LABELS.put("STAPLE_DUAL_RIGHT", "Two staples: right edge");
        LABELS.put("STAPLE_DUAL_TOP", "Two staples: top edge");
        LABELS.put("STAPLE_DUAL_BOTTOM", "Two staples: bottom edge");
        LABELS.put("PUNCH_LEFT", "Hole punch: left edge");
        LABELS.put("PUNCH_RIGHT", "Hole punch: right edge");
        LABELS.put("PUNCH_TOP", "Hole punch: top edge");
        LABELS.put("PUNCH_BOTTOM", "Hole punch: bottom edge");
        LABELS.put("BIND_LEFT", "Bind: left edge");
        LABELS.put("BIND_RIGHT", "Bind: right edge");
        LABELS.put("BIND_TOP", "Bind: top edge");
        LABELS.put("BIND_BOTTOM", "Bind: bottom edge");
    }

    private Finishing() {
    }

    public static boolean known(String id) {
        return LABELS.containsKey(id);
    }

    /** "STAPLE" + "TOP_LEFT" -> "STAPLE_TOP_LEFT", or null when position is null. */
    public static String id(String group, String position) {
        return position == null ? null : group + "_" + position;
    }

    public static int rank(String id) {
        int i = 0;
        for (String k : LABELS.keySet()) {
            if (k.equals(id)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }
}
