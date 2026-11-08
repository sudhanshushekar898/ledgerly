package dev.ledgerly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.ledgerly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.MediaType;

class BootstrapAdminIT extends AbstractIntegrationTest {

    @Autowired
    ApplicationRunner bootstrapAdminRunner;

    @Test
    void createsOneAdminWhoCanLogInAndIsIdempotent() throws Exception {
        bootstrapAdminRunner.run(null);
        bootstrapAdminRunner.run(null); // second start must not create a duplicate

        assertThat(jdbc.queryForObject("select count(*) from users where role = 'ADMIN'", Integer.class)).isEqualTo(1);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"operator@example.com\",\"password\":\"Operator-Passphrase-2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }
}
