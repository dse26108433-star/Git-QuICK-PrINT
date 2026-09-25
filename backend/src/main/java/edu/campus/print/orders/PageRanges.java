package edu.campus.print.orders;

import edu.campus.print.common.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which pages of a PDF the student wants, written the way every print dialog
 * accepts it:   102      333-390      1-5, 8, 12-15      333-   (to the end)
 *
 * Page numbers are the PDF's own (1 = first page of the file). Pages are
 * always printed in order, each once: "8, 1-3, 2" prints 1, 2, 3, 8.
 *
 * Stored in the normalised form "1-3,8"; null means all pages.
 */
public final class PageRanges {

    public static final int MAX_LENGTH = 200;
    private static final Pattern PART = Pattern.compile("(\\d{1,6})(?:\\s*-\\s*(\\d{1,6})?)?");

    /** The normalised selection (null = every page) and how many pages it prints. */
    public record Selection(String spec, int count) {
    }

    private PageRanges() {
    }

    /**
     * Checks the writing only, before the file is uploaded (the page count is
     * not known yet). Returns the text trimmed, or null for "all pages".
     */
    public static String checkSyntax(String text) {
        if (text == null || text.isBlank()) return null;
        parse(text, Integer.MAX_VALUE);
        return text.trim();
    }

    /** Full check against the real number of pages in the file. */
    public static Selection resolve(String text, int totalPages) {
        if (text == null || text.isBlank()) return new Selection(null, totalPages);
        List<int[]> ranges = parse(text, totalPages);
        int count = 0;
        StringBuilder spec = new StringBuilder();
        for (int[] r : ranges) {
            count += r[1] - r[0] + 1;
            if (!spec.isEmpty()) spec.append(',');
            spec.append(r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "-" + r[1]);
        }
        return count == totalPages ? new Selection(null, totalPages) : new Selection(spec.toString(), count);
    }

    /** Sorted, merged, 1-based inclusive ranges. */
    private static List<int[]> parse(String text, int totalPages) {
        String t = text.trim()
                .replace('–', '-').replace('—', '-')   // en/em dash typed on phones
                .replace(';', ',');
        if (t.length() > MAX_LENGTH) {
            throw bad("The page list is too long. Use ranges like 10-50.");
        }
        List<int[]> parts = new ArrayList<>();
        for (String raw : t.split(",")) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            Matcher m = PART.matcher(p);
            if (!m.matches()) {
                throw bad("\"" + p + "\" is not a page number. Write pages like 5, 10-20 or 1-3, 8.");
            }
            int from = Integer.parseInt(m.group(1));
            boolean isRange = p.contains("-");
            int to = !isRange ? from : m.group(2) == null ? totalPages : Integer.parseInt(m.group(2));
            if (isRange && m.group(2) == null && totalPages == Integer.MAX_VALUE) {
                to = from;               // "333-" before the page count is known: syntax is fine
            }
            if (to < from) {
                int x = from; from = to; to = x;
            }
            if (from < 1) {
                throw bad("Pages start at 1.");
            }
            if (to > totalPages) {
                throw bad("Page " + to + " does not exist: this PDF has " + totalPages
                        + (totalPages == 1 ? " page." : " pages."));
            }
            parts.add(new int[]{from, to});
        }
        if (parts.isEmpty()) {
            throw bad("Write the pages you want, like 5 or 10-20.");
        }
        parts.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : parts) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(r);
            }
        }
        return merged;
    }

    private static ApiException bad(String message) {
        return ApiException.badRequest("BAD_PAGES", message);
    }
}
