package edu.campus.print.printing;

/**
 * The price of one document, in paise:
 *
 *   per side   = B/W or colour price x paper size % x paper type % (rounded to the paise)
 *   per copy   = sides x per side + finishing (staple, punch, bind: per copy)
 *   document   = copies x per copy
 *
 * Whole numbers only, so the website (web/js/print-core.js) gets exactly the
 * same answer; spec/cases/pricing.json checks both.
 */
public final class Pricing {

    private Pricing() {
    }

    public record Quote(int perSidePaise, int finishingPerCopyPaise, long amountPaise) {}

    public static Quote quote(PrintSettings s, PrintPlan plan, int priceBwPaise, int priceColorPaise,
                              PricingRules rules) {
        PricingRules r = rules == null ? PricingRules.DEFAULT : rules;
        long base = s.color() ? priceColorPaise : priceBwPaise;
        long sizePct = r.paperSizePercent().getOrDefault(s.paperSize(), 100);
        long mediaPct = s.mediaType() == null ? 100 : r.mediaTypePercent().getOrDefault(s.mediaType(), 100);
        int perSide = (int) ((base * sizePct * mediaPct + 5_000) / 10_000);
        int finishing = 0;
        if (s.staple() != null) finishing += r.finishingPaise().getOrDefault("STAPLE", 0);
        if (s.punch() != null) finishing += r.finishingPaise().getOrDefault("PUNCH", 0);
        if (s.bind() != null) finishing += r.finishingPaise().getOrDefault("BIND", 0);
        long amount = (long) s.copies() * ((long) plan.sides() * perSide + finishing);
        return new Quote(perSide, finishing, amount);
    }
}
