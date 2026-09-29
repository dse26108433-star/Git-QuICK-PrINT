package edu.campus.agent.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The layout must give exactly the numbers of spec/cases/layout.json (the website's preview uses the same file). */
class LayoutTest {

    @Test
    void matchesTheSharedCases() throws Exception {
        JsonNode spec = new ObjectMapper().readTree(Path.of("..", "spec", "cases", "layout.json").toFile());
        for (JsonNode c : spec.path("cases")) {
            String name = c.path("name").asText();
            JsonNode s = c.path("settings");
            List<Layout.Size> pages = new ArrayList<>();
            for (JsonNode p : c.path("pages")) pages.add(new Layout.Size(p.get(0).asDouble(), p.get(1).asDouble()));
            Layout.Options o = new Layout.Options(c.path("paperMm").get(0).asDouble() * Layout.MM,
                    c.path("paperMm").get(1).asDouble() * Layout.MM, s.path("orientation").asText("AUTO"),
                    s.path("marginMm").asDouble(5) * Layout.MM, s.path("scaling").asText("FIT"),
                    s.path("scalePercent").asInt(100), s.path("pagesPerSheet").asInt(1), s.path("center").asBoolean(true));
            List<Layout.Sheet> got = Layout.sheets(pages, o);
            JsonNode want = c.path("sheets");
            assertThat(got).as(name).hasSize(want.size());
            for (int i = 0; i < got.size(); i++) {
                Layout.Sheet g = got.get(i);
                JsonNode w = want.get(i);
                assertThat(g.w()).as(name).isCloseTo(w.path("w").asDouble(), within(0.001));
                assertThat(g.h()).as(name).isCloseTo(w.path("h").asDouble(), within(0.001));
                if (w.path("clip").isNull()) {
                    assertThat(g.clip()).as(name + " clip").isNull();
                } else {
                    assertThat(g.clip().x()).as(name).isCloseTo(w.path("clip").get(0).asDouble(), within(0.001));
                    assertThat(g.clip().w()).as(name).isCloseTo(w.path("clip").get(2).asDouble(), within(0.001));
                }
                assertThat(g.cells()).as(name).hasSize(w.path("cells").size());
                for (int k = 0; k < g.cells().size(); k++) {
                    Layout.Cell gc = g.cells().get(k);
                    JsonNode wc = w.path("cells").get(k);
                    assertThat(gc.page()).as(name).isEqualTo(wc.path("page").asInt());
                    assertThat(gc.x()).as(name + " x").isCloseTo(wc.path("x").asDouble(), within(0.001));
                    assertThat(gc.y()).as(name + " y").isCloseTo(wc.path("y").asDouble(), within(0.001));
                    assertThat(gc.w()).as(name + " w").isCloseTo(wc.path("w").asDouble(), within(0.001));
                    assertThat(gc.h()).as(name + " h").isCloseTo(wc.path("h").asDouble(), within(0.001));
                }
            }
        }
    }

    @Test
    void fittingNeverStretches() {
        for (String scaling : List.of("FIT", "FILL", "ACTUAL", "CUSTOM")) {
            Layout.Sheet s = Layout.sheets(List.of(new Layout.Size(400, 300)),
                    new Layout.Options(595.28, 841.89, "AUTO", 14, scaling, 73, 1, true)).get(0);
            Layout.Cell c = s.cells().get(0);
            assertThat(c.w() / c.h()).as(scaling).isCloseTo(400.0 / 300.0, within(1e-9));
        }
    }
}
