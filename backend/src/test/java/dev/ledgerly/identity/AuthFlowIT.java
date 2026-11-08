package dev.ledgerly.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerly.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthFlowIT extends AbstractIntegrationTest {

    private static final String EMAIL = "asha@example.com";

    @Test
    void registerCreatesUserRoleAccountAndHardenedSession() throws Exception {
        MvcResult result = register("Asha@Example.com", STRONG_PASSWORD);

        JsonNode body = body(result);
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(900);
        assertThat(body.at("/user/email").asText()).isEqualTo(EMAIL);
        assertThat(body.at("/user/role").asText()).isEqualTo("USER");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String hash = jdbc.queryForObject("select password_hash from users", String.class);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(STRONG_PASSWORD);
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        register(EMAIL, STRONG_PASSWORD);

        mvc.perform(registerRequest("ASHA@example.com", STRONG_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void parallelSignUpsWithTheSameEmailCreateExactlyOneUser() throws Exception {
        Callable<Integer> signUp = () -> mvc.perform(registerRequest(EMAIL, STRONG_PASSWORD)).andReturn()
                .getResponse().getStatus();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var statuses = pool.invokeAll(java.util.Collections.nCopies(8, signUp)).stream()
                    .map(f -> { try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); } })
                    .toList();
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    @Test
    void weakPasswordAndInvalidBodyAreRejected() throws Exception {
        mvc.perform(registerRequest(EMAIL, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(registerRequest("not-an-email", STRONG_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"password\":\"" + STRONG_PASSWORD
                                + "\",\"fullName\":\"A\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest()); // unknown fields fail: a client cannot ask to be ADMIN
    }

    @Test
    void loginSucceedsAndWrongPasswordLooksLikeUnknownEmail() throws Exception {
        register(EMAIL, STRONG_PASSWORD);

        assertThat(login(EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        MvcResult wrong = login(EMAIL, "not-the-password");
        MvcResult unknown = login("nobody@example.com", "not-the-password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(wrong).get("detail")).isEqualTo(body(unknown).get("detail"));
    }

    @Test
    void accountLocksAfterFiveFailuresEvenForTheCorrectPasswordThenUnlocks() throws Exception {
        register(EMAIL, STRONG_PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(login(EMAIL, "wrong-password-" + i).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult locked = login(EMAIL, STRONG_PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(423);
        assertThat(body(locked).get("code").asText()).isEqualTo("ACCOUNT_LOCKED");

        jdbc.update("update users set locked_until = now() - interval '1 minute'");
        assertThat(login(EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        register(EMAIL, STRONG_PASSWORD);
        jdbc.update("update users set enabled = false");

        assertThat(login(EMAIL, STRONG_PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void meRequiresAValidBearerToken() throws Exception {
        String token = accessToken(register(EMAIL, STRONG_PASSWORD));

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTheTokenAndReuseRevokesTheWholeFamily() throws Exception {
        String first = refreshCookie(register(EMAIL, STRONG_PASSWORD));

        MvcResult rotated = refresh(first);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String second = refreshCookie(rotated);
        assertThat(second).isNotEqualTo(first);

        // An attacker replays the old token: it fails, and the legitimate new token is revoked too.
        assertThat(refresh(first).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshAndLogoutRequireTheScriptedRequestHeader() throws Exception {
        String cookie = refreshCookie(register(EMAIL, STRONG_PASSWORD));

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", cookie)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", cookie)))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutRevokesTheRefreshTokenAndClearsTheCookie() throws Exception {
        String cookie = refreshCookie(register(EMAIL, STRONG_PASSWORD));

        MvcResult logout = mvc.perform(post("/api/v1/auth/logout").header("X-Requested-With", "XMLHttpRequest")
                        .cookie(new Cookie("refresh_token", cookie)))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithoutOrWithUnknownTokenIsUnauthenticated() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
        assertThat(refresh("made-up-token").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void ordinaryUserIsForbiddenFromAdminRoutes() throws Exception {
        String token = accessToken(register(EMAIL, STRONG_PASSWORD));

        mvc.perform(get("/api/v1/admin/anything").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private org.springframework.test.web.servlet.RequestBuilder registerRequest(String email, String password)
            throws Exception {
        return post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password, "fullName", "Test User")));
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private MvcResult refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").header("X-Requested-With", "XMLHttpRequest")
                .cookie(new Cookie("refresh_token", cookie))).andReturn();
    }

    private static String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        return header.substring("refresh_token=".length(), header.indexOf(';'));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
