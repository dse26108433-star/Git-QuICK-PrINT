package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What staff let students choose on one printer (printers.offered), set on
 * the Station's Printers screen. Any field left null means "the sensible
 * default": see EffectiveFeatures. It can only narrow down what the printer
 * really does, except for a printer whose features could not be read: then
 * it is what staff say the printer does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfferedFeatures(
        List<String> paperSizes,
        Boolean duplex,
        List<String> finishing,
        List<String> mediaTypes,
        Boolean borderless,
        Boolean highQuality
) {
}
