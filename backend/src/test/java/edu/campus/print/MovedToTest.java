package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.print.support.FakeStorage;
import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The old server after the print service has moved (MOVED_TO is set on it):
 * it says where XeoGo is now, so websites and apps go there by themselves,
 * and it takes no new orders (nothing ordered here would ever print).
 */
@SpringBootTest
@AutoConfigureMockMvc
class MovedToTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static String dbUrl;

    @Autowired MockMvc mvc;

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        FakeStorage fakeStorage() {
            return new FakeStorage();
        }
    }

    @BeforeAll
    static void database() {
        dbUrl = TestDb.freshDatabase();
        TestDb.run(TestDb.dataSource(dbUrl), TestDb.setupSql());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> dbUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("campus.supabase.url", () -> "https://storage.test");
        r.add("campus.supabase.service-key", () -> "test-key");
        r.add("campus.payment.mode", () -> "demo");
        r.add("campus.counter.password", () -> "counter-pass-123");
        r.add("campus.agent.token-secret", () -> "a-test-token-secret-that-is-long-enough-123");
        r.add("campus.moved-to", () -> "https://print.mycollege.in/");
    }

    @Test
    void theOldServerSaysWhereXeoGoIsNowAndTakesNoNewOrders() throws Exception {
        JsonNode shop = json(mvc.perform(get("/api/v1/shop")).andReturn());
        assertThat(shop.path("movedTo").asText()).isEqualTo("https://print.mycollege.in");      // tidied: no slash at the end
        assertThat(shop.path("centerName").asText()).isNotBlank();                              // still a proper answer

        MvcResult r = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        JsonNode said = json(r);
        assertThat(said.path("error").asText()).isEqualTo("MOVED");
        assertThat(said.path("message").asText()).contains("has moved");
        assertThat(said.path("movedTo").asText()).isEqualTo("https://print.mycollege.in");
    }

    private static JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
