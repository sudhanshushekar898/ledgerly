package dev.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Boots the whole app against real PostgreSQL: migrations apply, probes are up, errors are well-formed. */
class ApplicationSmokeIT extends AbstractIntegrationTest {

    @Test
    void readinessProbeIsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flywayAppliedBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(jdbc.queryForList("select extname from pg_extension", String.class)).contains("citext");
    }

    @Test
    void unauthenticatedCallIsDeniedByDefaultWithProblemJsonAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("X-Request-Id", "test-req-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value("test-req-1"))
                .andExpect(header().string("X-Request-Id", "test-req-1"));
    }

    @Test
    void authenticatedUnknownRouteIsResourceNotFound() throws Exception {
        String token = accessToken(register("someone@example.com", STRONG_PASSWORD));
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
