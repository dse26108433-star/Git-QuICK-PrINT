package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Keeps only the pages the student chose, e.g. "102", "333-390", "1-5,8" or
 * "333-" (to the end). Page numbers are the PDF's own, starting at 1. Pages
 * print in order, each once.
 *
 * The backend already checked the choice against this exact file (same
 * SHA-256), so a page that is not in the file means something is wrong: we
 * refuse rather than print the wrong pages.
 */
public final class PageSelection {

    private PageSelection() {
    }

    /** Removes every page that was not chosen. Returns how many pages are left. */
    public static int keepOnly(PDDocument doc, String spec) {
        int total = doc.getNumberOfPages();
        boolean[] keep = parse(spec, total);
        for (int i = total - 1; i >= 0; i--) {
            if (!keep[i]) doc.removePage(i);
        }
        return doc.getNumberOfPages();
    }

    static boolean[] parse(String spec, int total) {
        boolean[] keep = new boolean[total];
        boolean any = false;
        String text = spec.replace('–', '-').replace('—', '-').replace(';', ',');
        for (String raw : text.split(",")) {
            String part = raw.replace(" ", "");
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            int from, to;
            try {
                if (dash < 0) {
                    from = to = Integer.parseInt(part);
                } else {
                    from = Integer.parseInt(part.substring(0, dash));
                    to = dash == part.length() - 1 ? total : Integer.parseInt(part.substring(dash + 1));
                }
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Page choice \"" + spec + "\" cannot be read");
            }
            if (to < from) {
                int x = from; from = to; to = x;
            }
            if (from < 1 || to > total) {
                throw new IllegalStateException("Page choice \"" + spec + "\" does not fit this "
                        + total + "-page file");
            }
            for (int p = from; p <= to; p++) keep[p - 1] = true;
            any = true;
        }
        if (!any) {
            throw new IllegalStateException("Page choice \"" + spec + "\" is empty");
        }
        return keep;
    }
}
