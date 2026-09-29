package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.common.ApiException;
import edu.campus.print.domain.FileType;
import edu.campus.print.orders.PageRanges;
import edu.campus.print.printing.*;
import edu.campus.print.support.Files;
import edu.campus.print.storage.FileInspector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules the website, the database and the Xerox PC must agree on, checked
 * against the shared cases in spec/cases (the website runs the same files).
 */
class SharedRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode spec(String name) throws Exception {
        return JSON.readTree(Path.of("..", "spec", "cases", name).toFile());
    }

    @Test
    void pageRanges() throws Exception {
        for (JsonNode c : spec("page-ranges.json").path("cases")) {
            String text = c.path("text").asText();
            int total = c.path("total").asInt();
            if (c.path("error").asBoolean()) {
                assertThatThrownBy(() -> PageRanges.resolve(text, total)).as(c.toString())
                        .isInstanceOf(ApiException.class);
                continue;
            }
            PageRanges.Selection s = PageRanges.resolve(text, total);
            assertThat(s.spec()).as(c.toString()).isEqualTo(c.path("spec").isNull() ? null : c.path("spec").asText());
            assertThat(s.count()).as(c.toString()).isEqualTo(c.path("count").asInt());
        }
    }

    @Test
    void pricing() throws Exception {
        JsonNode spec = spec("pricing.json");
        PricingRules rules = JSON.treeToValue(spec.path("rules"), PricingRules.class);
        for (JsonNode c : spec.path("cases")) {
            PrintSettings s = settings(c.path("settings"));
            PrintPlan plan = PrintPlan.of(c.path("printPages").asInt(), s);
            int bw = c.path("priceBwPaise").asInt(spec.path("priceBwPaise").asInt());
            Pricing.Quote q = Pricing.quote(s, plan, bw, spec.path("priceColorPaise").asInt(), rules);
            String name = c.path("name").asText();
            assertThat(plan.sides()).as(name).isEqualTo(c.path("sides").asInt());
            assertThat(plan.sheets()).as(name).isEqualTo(c.path("sheets").asInt());
            assertThat(q.perSidePaise()).as(name).isEqualTo(c.path("perSide").asInt());
            assertThat(q.amountPaise()).as(name).isEqualTo(c.path("amount").asLong());
        }
    }

    @Test
    void printerRules() throws Exception {
        JsonNode spec = spec("printer-rules.json");
        for (JsonNode c : spec.path("cases")) {
            JsonNode p = spec.path("printers").path(c.path("printer").asText());
            PrinterFeatures f = p.path("features").isNull() ? null : JSON.treeToValue(p.path("features"), PrinterFeatures.class);
            PrinterRules.Candidate cand = new PrinterRules.Candidate("p", p.path("color").asBoolean(),
                    p.path("bw").asBoolean(), f);
            Requirements r = JSON.treeToValue(c.path("req"), Requirements.class);
            if (r.finishing() == null) r = new Requirements(r.color(), r.paperSize(), r.duplex(), List.of(),
                    r.mediaType(), r.borderless(), r.quality());
            assertThat(PrinterRules.canDo(cand, r)).as(c.toString()).isEqualTo(c.path("can").asBoolean());
        }
    }

    /** Missing settings are the defaults, as in the website's print-core.js. */
    private static PrintSettings settings(JsonNode partial) throws Exception {
        ObjectNode full = JSON.valueToTree(PrintSettings.defaults());
        full.setAll((ObjectNode) partial);
        return JSON.treeToValue(full, PrintSettings.class);
    }

    // ------------------------------------------------------------------ settings check

    private static final List<PrinterRules.Candidate> PRINTERS = List.of(
            new PrinterRules.Candidate("BW", false, true, new PrinterFeatures(List.of("A4", "A3"), true,
                    List.of("STAPLE_TOP_LEFT"), List.of(), Map.of(), false, false, 999, 4.2)),
            new PrinterRules.Candidate("Photo", true, true, new PrinterFeatures(List.of("A4", "PHOTO_4X6"), false,
                    List.of(), List.of(), Map.of(), true, true, 99, 3.0)));
    private static final SettingsChecker.Limits LIMITS = new SettingsChecker.Limits(50, 300);

    private static SettingsChecker.Checked check(String json, FileType type, int pages) throws Exception {
        return SettingsChecker.check(JSON.readTree(json), new SettingsChecker.Facts(type, pages), PRINTERS, LIMITS);
    }

    @Test
    void picturesAreTidied() throws Exception {
        var c = check("{\"pages\":\"1-3\",\"pagesPerSheet\":4,\"rotation\":-90,\"scaling\":\"FILL\",\"center\":false}",
                FileType.JPEG, 1);
        assertThat(c.settings().pages()).isNull();
        assertThat(c.settings().pagesPerSheet()).isEqualTo(1);
        assertThat(c.settings().rotation()).isEqualTo(270);
        assertThat(c.settings().scaling()).isEqualTo("FILL");
        assertThat(c.settings().center()).isFalse();
        assertThat(c.plan().printPages()).isEqualTo(1);
        assertThat(c.legacyOk()).isFalse();
    }

    @Test
    void pdfsCannotBeFilledOrTurned() throws Exception {
        assertThatThrownBy(() -> check("{\"scaling\":\"FILL\"}", FileType.PDF, 3))
                .hasMessage("Unknown scaling \"FILL\".");
        var c = check("{\"rotation\":90,\"center\":false}", FileType.PDF, 3);
        assertThat(c.settings().rotation()).isZero();
        assertThat(c.settings().center()).isTrue();
        assertThat(c.legacyOk()).isTrue();
    }

    @Test
    void choicesAreCheckedStrictly() {
        assertThatThrownBy(() -> check("{\"copies\":0}", FileType.PDF, 1)).hasMessage("Choose between 1 and 50 copies.");
        assertThatThrownBy(() -> check("{\"copies\":2.5}", FileType.PDF, 1)).hasMessageContaining("whole number");
        assertThatThrownBy(() -> check("{\"duplex\":\"BOTH\"}", FileType.PDF, 1)).hasMessageContaining("Unknown sides");
        assertThatThrownBy(() -> check("{\"paperSize\":\"A0\"}", FileType.PDF, 1)).hasMessageContaining("Unknown paper");
        assertThatThrownBy(() -> check("{\"pagesPerSheet\":3}", FileType.PDF, 9)).hasMessageContaining("1, 2, 4, 6, 9 or 16");
        assertThatThrownBy(() -> check("{\"scaling\":\"CUSTOM\",\"scalePercent\":5}", FileType.PDF, 1))
                .hasMessageContaining("10 % and 400 %");
        assertThatThrownBy(() -> check("{\"marginMm\":41}", FileType.PDF, 1)).hasMessageContaining("Margins");
        assertThatThrownBy(() -> check("{\"staple\":\"MIDDLE\"}", FileType.PDF, 4)).hasMessageContaining("Unknown staple");
        assertThatThrownBy(() -> check("{\"rotation\":45}", FileType.PNG, 1)).hasMessageContaining("90, 180 or 270");
        assertThatThrownBy(() -> check("{\"pages\":\"1-301\"}", FileType.PDF, 400)).hasMessageContaining("Up to 300 pages");
    }

    @Test
    void borderlessNeedsAPrinterThatCan() throws Exception {
        // The photo printer (which also takes B/W) can do borderless A4, but nobody does borderless A3.
        assertThat(check("{\"marginMm\":0}", FileType.PDF, 1).requirements().borderless()).isTrue();
        assertThatThrownBy(() -> check("{\"marginMm\":0,\"paperSize\":\"A3\"}", FileType.PDF, 1))
                .hasMessage("No single printer can do A3 + borderless together. Change one of these choices.");
        var c = check("{\"marginMm\":0,\"color\":true,\"paperSize\":\"PHOTO_4X6\",\"quality\":\"HIGH\"}", FileType.PNG, 1);
        assertThat(c.requirements().borderless()).isTrue();
        assertThat(c.requirements().quality()).isEqualTo("HIGH");
    }

    @Test
    void staplingMakesCopiesCollated() throws Exception {
        var c = check("{\"copies\":3,\"collate\":false,\"staple\":\"TOP_LEFT\"}", FileType.PDF, 4);
        assertThat(c.settings().collate()).isTrue();
        assertThat(c.requirements().finishing()).containsExactly("STAPLE_TOP_LEFT");
        var u = check("{\"copies\":3,\"collate\":false}", FileType.PDF, 4);
        assertThat(u.settings().collate()).isFalse();
    }

    // ------------------------------------------------------------------ what students can get from a printer

    @Test
    void effectiveFeaturesNarrowWhatThePrinterCanDo() throws Exception {
        JsonNode caps = JSON.readTree("""
                {"paperSizes":[{"id":"A4","marginsMm":[4.2,4.2,4.2,5.1]},{"id":"LETTER"},{"id":"A3"},{"id":"PHOTO_4X6"}],
                 "duplex":true,"finishing":["STAPLE_TOP_LEFT","PUNCH_LEFT","NOT_A_THING"],
                 "mediaTypes":[{"id":"psk:Plain","name":"Plain"},{"id":"psk:PhotographicHighGloss","name":"Glossy"}],
                 "borderless":true,"highQuality":true,"maxCopies":999}""");
        PrinterFeatures d = EffectiveFeatures.compute(caps, null);
        assertThat(d.paperSizes()).containsExactly("A4", "A3");
        assertThat(d.duplex()).isTrue();
        assertThat(d.finishing()).isEmpty();                  // finishing only once staff ticked it (after a test print)
        assertThat(d.mediaTypes()).isEmpty();                 // paper types only when staff offer them
        assertThat(d.minMarginMm()).isEqualTo(5.1);

        PrinterFeatures o = EffectiveFeatures.compute(caps, new OfferedFeatures(List.of("PHOTO_4X6", "A4", "B4"),
                false, List.of("PUNCH_LEFT"), List.of("psk:PhotographicHighGloss"), false, null));
        assertThat(o.paperSizes()).containsExactly("A4", "PHOTO_4X6");   // B4: the printer does not have it
        assertThat(o.duplex()).isFalse();
        assertThat(o.finishing()).containsExactly("PUNCH_LEFT");
        assertThat(o.mediaTypes()).containsExactly("psk:PhotographicHighGloss");
        assertThat(o.mediaTypeNames()).containsEntry("psk:PhotographicHighGloss", "Glossy");
        assertThat(o.borderless()).isFalse();
        assertThat(o.highQuality()).isTrue();

        // Staff tick finishing the driver does not have: only what the printer has counts.
        assertThat(EffectiveFeatures.compute(caps, new OfferedFeatures(null, null,
                List.of("STAPLE_TOP_LEFT", "PUNCH_LEFT", "BIND_LEFT"), null, null, null)).finishing())
                .containsExactly("STAPLE_TOP_LEFT", "PUNCH_LEFT");

        // Staff untick every size: never leave the printer with nothing.
        assertThat(EffectiveFeatures.compute(caps, new OfferedFeatures(List.of(), null, null, null, null, null))
                .paperSizes()).containsExactly("A4");
    }

    @Test
    void aPrinterNobodyCouldReadIsPlainA4UnlessStaffSaySo() {
        assertThat(EffectiveFeatures.compute(null, null)).isEqualTo(PrinterFeatures.PLAIN_A4);
        PrinterFeatures d = EffectiveFeatures.compute(null, new OfferedFeatures(List.of("A3", "A4"), true,
                List.of("STAPLE_TOP_LEFT"), List.of("x"), null, null));
        assertThat(d.paperSizes()).containsExactly("A4", "A3");
        assertThat(d.duplex()).isTrue();
        assertThat(d.finishing()).containsExactly("STAPLE_TOP_LEFT");
        assertThat(d.mediaTypes()).isEmpty();
    }

    // ------------------------------------------------------------------ pictures

    @Test
    void phonePhotosAreMeasuredAndTheirTurnIsRead() {
        FileInspector.Result r = new FileInspector().inspect(Files.jpegWithOrientation(640, 480, 6));
        assertThat(r.ok()).isTrue();
        assertThat(r.image().widthPx()).isEqualTo(640);
        assertThat(r.image().exifOrientation()).isEqualTo(6);
        assertThat(r.image().lookWidthPx()).isEqualTo(480);           // shown turned upright
        assertThat(r.image().dpi()).isEqualTo(96.0);
        FileInspector.Result png = new FileInspector().inspect(Files.png(30, 20));
        assertThat(png.image().exifOrientation()).isEqualTo(1);
    }
}
