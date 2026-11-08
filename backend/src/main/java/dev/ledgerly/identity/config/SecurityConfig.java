package dev.ledgerly.identity.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerly.identity.domain.Role;
import dev.ledgerly.shared.error.ErrorCode;
import dev.ledgerly.shared.error.GlobalExceptionHandler;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Stateless, deny-by-default API security. Role rules here are the first layer; services check ownership. */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    private static final String AUTH = "/api/v1/auth/";
    private static final Set<String> KNOWN_ROLES = Arrays.stream(Role.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** BCrypt cost 12, wrapped so hashes carry an id and can be upgraded to another algorithm later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, mapper, GlobalExceptionHandler.problem(ErrorCode.UNAUTHENTICATED, "Authentication required."));
        };
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> write(response, mapper,
                GlobalExceptionHandler.problem(ErrorCode.FORBIDDEN, "You do not have permission to perform this action."));
    }

    /** Only roles this app knows become authorities, so a forged-but-signed claim cannot invent new ones. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = roles == null ? List.of()
                    : roles.stream().filter(KNOWN_ROLES::contains)
                            .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return authorities;
        });
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                            AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler)
            throws Exception {
        http
                // Bearer-token API. The only cookie endpoints (refresh, logout) require X-Requested-With instead.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The SSE stream's later async dispatches were authorised on the original request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                AUTH + "register", AUTH + "login", AUTH + "refresh", AUTH + "logout").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Scraped by Prometheus inside the compose network. Nginx does not proxy it to the public;
                        // in production it belongs on a private management port.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Operators have no wallet; customer money endpoints are USER-only.
                        .requestMatchers("/api/v1/wallet/**", "/api/v1/transfers/**", "/api/v1/recipients/**",
                                "/api/v1/notifications/**").hasRole("USER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        return http.build();
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
