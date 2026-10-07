/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.api.security;

import java.nio.charset.StandardCharsets;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter;
import ai.philterd.philter.api.filters.content.ContentTypeVerifyingFilter;
import ai.philterd.philter.api.filters.size.SizeLimitingFilter;
import ai.philterd.philter.audit.AuditEventPublisher;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final MongoClient mongoClient;
    private final Gson gson;

    public SecurityConfig(final MongoClient mongoClient, final Gson gson) {
        this.mongoClient = mongoClient;
        this.gson = gson;
    }

    @Bean
    public ContentTypeVerifyingFilter contentTypeVerifyingFilter(
            @Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        return new ContentTypeVerifyingFilter(resolver);
    }

    @Bean
    public SizeLimitingFilter sizeLimitingFilter(@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        return new SizeLimitingFilter(resolver);
    }

    /**
     * Answers a request the firewall refuses for its path with {@code {"message": ...}} JSON, as Philter's other
     * errors are, rather than Spring's default error body. The message never echoes the path.
     */
    /** The message for a request the firewall refuses for its path. */
    public static final String REQUEST_REJECTED = "The request was refused: its path contains a character or segment "
            + "that is not allowed, such as ;, an encoded %, an empty segment (//), or a . or .. segment.";

    @Bean
    public RequestRejectedHandler requestRejectedHandler() {
        return (request, response, requestRejectedException) -> {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(gson.toJson(new GenericResponse(REQUEST_REJECTED)));
        };
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers("/public/**");
    }

    /**
     * Security chain for the stateless, Bearer-token API ({@code /api/**}).
     *
     * <p>The chain creates no HTTP session ({@link SessionCreationPolicy#STATELESS}): authentication is
     * re-established from the API key on every request by {@link ApiAuthenticationFilter}, so nothing is
     * carried in a session between requests. That is what lets the API scale horizontally behind a plain
     * load balancer. The filter performs authentication
     * itself (returning 401/403 JSON and handing the resolved key to controllers via a request
     * attribute), so Spring Security authorization here is permissive and CSRF is disabled (token auth
     * does not rely on cookies).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(final HttpSecurity http, final AuditEventPublisher auditEventPublisher,
                                              final MeterRegistry meterRegistry,
                                              final SizeLimitingFilter sizeLimitingFilter,
                                              final ai.philterd.philter.services.cache.ApiKeyCache apiKeyCache,
                                              final ai.philterd.philter.data.services.UserService userService) throws Exception {

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiAuthenticationFilter(mongoClient, auditEventPublisher, meterRegistry, gson, apiKeyCache, userService), UsernamePasswordAuthenticationFilter.class);

        return http.build();

    }

    /**
     * Security chain for everything that is not the API: the actuator endpoints, the OpenAPI
     * specification and Swagger UI, and the documentation under {@code /public/docs}. All are public and
     * hold no session, so nothing here sets a cookie, and any other path answers 404 because no handler
     * serves it.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(final HttpSecurity http, final SizeLimitingFilter sizeLimitingFilter) throws Exception {

        http
                // No session and no cookie-based authentication, so there is nothing for CSRF to forge.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'")))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();

    }

}