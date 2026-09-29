package edu.campus.agent.print;

import edu.campus.agent.net.Messages.JobSettings;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading what Windows says a printer can do (real documents from an Epson
 * inkjet and Microsoft Print to PDF), and turning settings into print tickets.
 */
class CapabilitiesTest {

    private static PrintCapabilitiesXml.Parsed fixture(String name) throws Exception {
        try (InputStream in = CapabilitiesTest.class.getResourceAsStream("/printcaps/" + name)) {
            return PrintCapabilitiesXml.parse(in.readAllBytes());
        }
    }

    @Test
    void readsAnEpsonInkjet() throws Exception {
        PrintCapabilitiesXml.Parsed x = fixture("epson-l3210.xml");
        assertThat(x.optionIds("psk:PageOutputColor")).containsExactly("psk:Monochrome", "psk:Color");
        assertThat(x.optionIds("psk:JobDuplexAllDocumentsContiguously")).containsExactly("psk:OneSided");
        assertThat(x.optionIds("psk:PageBorderless")).containsExactly("psk:None");
        assertThat(x.parameterInt("psk:JobCopiesAllDocuments", "psf:MaxValue")).isEqualTo(999);
        // Paper types: standard names stay short, the maker's own keep their namespace (a print ticket needs it).
        List<PrintCapabilitiesXml.Option> media = x.options("psk:PageMediaType");
        assertThat(media.get(0).id()).isEqualTo("psk:Plain");
        assertThat(media.get(0).name()).isEqualTo("Plain paper");
        assertThat(media).extracting(PrintCapabilitiesXml.Option::id)
                .contains("{http://schema.epson.net/printschema/business/v100}EpsonPhotoPaperGlossy");
        // Real paper sizes, with their size in microns.
        PrintCapabilitiesXml.Option a4 = x.options("psk:PageMediaSize").get(0);
        assertThat(a4.id()).isEqualTo("psk:ISOA4");
        assertThat(a4.properties()).containsEntry("psk:MediaSizeWidth", "210000");
        assertThat(x.optionIds("psk:PageMediaSize")).doesNotContain("psk:ISOA3");
    }

    @Test
    void readsMicrosoftPrintToPdf() throws Exception {
        PrintCapabilitiesXml.Parsed x = fixture("ms-print-to-pdf.xml");
        assertThat(x.optionIds("psk:PageMediaSize")).contains("psk:ISOA3", "psk:ISOA4", "psk:NorthAmericaLetter");
        assertThat(x.optionIds("psk:PageOutputColor")).containsExactly("psk:Color");
        assertThat(x.parameterInt("psk:JobCopiesAllDocuments", "psf:MaxValue")).isEqualTo(1);
    }

    @Test
    void prettifiesOptionsWithoutAName() {
        assertThat(PrintCapabilitiesXml.prettify("{http://x}EpsonPhotoPaperGlossy")).isEqualTo("Epson Photo Paper Glossy");
        assertThat(PrintCapabilitiesXml.prettify("psk:StapleTopLeft")).isEqualTo("Staple Top Left");
    }

    // ------------------------------------------------------------------ print tickets

    private static JobSettings settings(String duplex, int margin, String staple, String punch, String media,
                                        String quality) {
        return new JobSettings(1, false, null, duplex, "A4", "AUTO", "FIT", 100, 1, margin, 0, true, true,
                staple, punch, null, media, quality);
    }

    @Test
    void plainDocumentsNeedNoTicket() {
        assertThat(TicketPlan.choices(JobSettings.plain(3, true, "1-3"), Optional.empty())).isEmpty();
    }

    @Test
    void stapleAndPunchBecomePrintSchemaOptions() throws Exception {
        var choices = TicketPlan.choices(settings("ONE_SIDED", 5, "DUAL_LEFT", "TOP", null, "STANDARD"),
                Optional.empty());
        assertThat(choices).containsExactly(
                new PrintTicket.Choice("psk:JobStapleAllDocuments", "psk:StapleDualLeft"),
                new PrintTicket.Choice("psk:JobHolePunch", "psk:TopEdge"));
    }

    @Test
    void borderlessPaperTypeAndQuality() throws Exception {
        PrintCapabilitiesXml.Parsed x = fixture("epson-l3210.xml");
        var caps = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("highQualityTicket", "{http://schema.epson.net/printschema/business/v100}HighQuality");
        var known = Optional.of(new CapabilityDiscovery.Discovered(caps, x));
        var choices = TicketPlan.choices(settings("ONE_SIDED", 0, null, null,
                "{http://schema.epson.net/printschema/business/v100}EpsonPhotoPaperGlossy", "HIGH"), known);
        assertThat(choices).containsExactly(
                new PrintTicket.Choice("psk:PageBorderless", "psk:Borderless"),
                new PrintTicket.Choice("psk:PageMediaType",
                        "{http://schema.epson.net/printschema/business/v100}EpsonPhotoPaperGlossy"),
                new PrintTicket.Choice("psk:PageOutputQuality",
                        "{http://schema.epson.net/printschema/business/v100}HighQuality"));
    }

    @Test
    void aTicketDeclaresTheMakersNamespace() {
        String xml = PrintTicket.deltaXml(List.of(
                new PrintTicket.Choice("psk:PageMediaType", "{http://maker.example/ps}Glossy"),
                new PrintTicket.Choice("psk:JobStapleAllDocuments", "psk:StapleTopLeft")));
        assertThat(xml).contains("xmlns:cp0=\"http://maker.example/ps\"");
        assertThat(xml).contains("<psf:Feature name=\"psk:PageMediaType\"><psf:Option name=\"cp0:Glossy\"/>");
        assertThat(xml).contains("<psf:Option name=\"psk:StapleTopLeft\"/>");
    }

    @Test
    void anythingTheDriverDroppedIsNoticed() throws Exception {
        String validated = """
                <psf:PrintTicket xmlns:psf="http://schemas.microsoft.com/windows/2003/08/printing/printschemaframework"
                    xmlns:psk="http://schemas.microsoft.com/windows/2003/08/printing/printschemakeywords"
                    xmlns:v="http://maker.example/ps" version="1">
                  <psf:Feature name="psk:JobStapleAllDocuments"><psf:Option name="psk:None"/></psf:Feature>
                  <psf:Feature name="psk:PageMediaType"><psf:Option name="v:Glossy"/></psf:Feature>
                </psf:PrintTicket>""";
        List<String> missing = PrintTicket.missing(validated.getBytes(StandardCharsets.UTF_8), List.of(
                new PrintTicket.Choice("psk:JobStapleAllDocuments", "psk:StapleTopLeft"),
                new PrintTicket.Choice("psk:PageMediaType", "{http://maker.example/ps}Glossy")));
        assertThat(missing).containsExactly("psk:StapleTopLeft");
    }

    @Test
    void theLabelNamesTheFileAndCountsSheets() {
        PrintJob one = job(1, 1, 1);
        assertThat(PickupCodeStamp.label(one, 1)).isEqualTo("  ·  1 sheet");
        PrintJob many = job(2, 3, 2);
        assertThat(PickupCodeStamp.label(many, 3)).isEqualTo("  ·  2/3  ·  3 sheets × 2");
    }

    private static PrintJob job(int number, int count, int copies) {
        return new PrintJob("id", "p", "PDF", "K7M4X", "f.pdf", number, count, JobSettings.plain(copies, false, null),
                new edu.campus.agent.net.Messages.Paper("A4", 210, 297), null, true);
    }
}
