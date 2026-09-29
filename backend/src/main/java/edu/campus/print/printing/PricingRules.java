package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Price rules on top of the per-side B/W and colour prices
 * (shop_settings.pricing, edited on the counter):
 *
 * @param paperSizePercent per paper size, % of the normal price per side (A3: 200 = double). Missing = 100.
 * @param mediaTypePercent per paper type, % of the normal price per side. Missing = 100.
 * @param finishingPaise   per finishing group (STAPLE, PUNCH, BIND), paise per copy. Missing = free.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingRules(
        Map<String, Integer> paperSizePercent,
        Map<String, Integer> mediaTypePercent,
        Map<String, Integer> finishingPaise
) {
    public static final PricingRules DEFAULT = new PricingRules(Map.of("A3", 200), Map.of(), Map.of());

    public PricingRules {
        paperSizePercent = paperSizePercent == null ? Map.of() : Map.copyOf(paperSizePercent);
        mediaTypePercent = mediaTypePercent == null ? Map.of() : Map.copyOf(mediaTypePercent);
        finishingPaise = finishingPaise == null ? Map.of() : Map.copyOf(finishingPaise);
    }
}
