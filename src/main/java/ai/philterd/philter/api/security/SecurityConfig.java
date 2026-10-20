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
import java.util.Optional;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter;
import ai.philterd.philter.api.filters.content.ContentTypeVerifyingFilter;
import ai.philterd.philter.api.filters.size.SizeLimitingFilter;
import ai.philterd.philter.audit.AuditEventPublisher;
import com.google.gson.Gson;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final Gson gson;

    public SecurityConfig(final Gson gson) {
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

    /** The message for a request the firewall refuses for its path. */
    public static final String REQUEST_REJECTED = "The request was refused: its path contains a character or segment "
            + "that is not allowed, such as ;, an encoded %, an empty segment (//), or a . or .. segment.";

    /**
     * Answers a request the firewall refuses for its path with {@code {"message": ...}} JSON, as Philter's other
     * errors are, rather than Spring's default error body. The message never echoes the path.
     *
     * <p>The firewall refuses the request before any chain runs, so the refusal is written through the
     * {@link HeaderWriterFilter} of the chain the request would have reached, to carry the same security headers.
     */
    @Bean
    public RequestRejectedHandler requestRejectedHandler(final ObjectProvider<SecurityFilterChain> chains) {
        return (request, response, requestRejectedException) -> {
            final FilterChain writeRefusal = (req, res) -> {
                final HttpServletResponse refusal = (HttpServletResponse) res;
                refusal.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                refusal.setContentType(MediaType.APPLICATION_JSON_VALUE);
                refusal.setCharacterEncoding(StandardCharsets.UTF_8.name());
                refusal.getWriter().write(gson.toJson(new GenericResponse(REQUEST_REJECTED, ErrorReasons.INVALID_REQUEST)));
            };
            final Optional<Filter> headerWriter = chains.orderedStream()
                    .filter(chain -> chain.matches(request))
                    .findFirst()
                    .flatMap(chain -> chain.getFilters().stream().filter(HeaderWriterFilter.class::isInstance).findFirst());
            if (headerWriter.isPresent()) {
                headerWriter.get().doFilter(request, response, writeRefusal);
            } else {
                writeRefusal.doFilter(request, response);
            }
        };
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
     *
     * <p>Every response carries {@code Cross-Origin-Resource-Policy: same-origin}, so a page on another site
     * cannot load it as an image, script, or frame. That applies only to loads made without CORS; it does
     * not change how a client that is not a browser calls the API.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(final HttpSecurity http, final AuditEventPublisher auditEventPublisher,
                                              final MeterRegistry meterRegistry,
                                              final SizeLimitingFilter sizeLimitingFilter,
                                              final ai.philterd.philter.services.cache.ApiKeyCache apiKeyCache,
                                              final ai.philterd.philter.data.services.UserService userService,
                                              final ai.philterd.philter.data.services.ApiKeyDataService apiKeyDataService) throws Exception {

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiAuthenticationFilter(apiKeyDataService, auditEventPublisher, meterRegistry, gson, apiKeyCache, userService), UsernamePasswordAuthenticationFilter.class);

        return http.build();

    }

    /**
     * Security chain for the documentation under {@code /public/docs}. It is public, static, and holds no
     * session. It carries the same security headers as the chain below, including
     * {@code Cross-Origin-Resource-Policy: same-origin}, except that it leaves out the {@code Cache-Control},
     * {@code Pragma}, and {@code Expires} headers that forbid caching, so a browser can cache the pages.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain docsFilterChain(final HttpSecurity http) throws Exception {

        http
                .securityMatcher("/public/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .cacheControl(cacheControl -> cacheControl.disable())
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'"))
                        .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();

    }

    /**
     * Security chain for everything else: the actuator endpoints and the OpenAPI specification and
     * Swagger UI. All are public and hold no session, so nothing here sets a cookie, and any other path
     * answers 404 because no handler serves it. Swagger UI loads only its own assets, so
     * {@code Cross-Origin-Resource-Policy: same-origin} applies here too.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain filterChain(final HttpSecurity http, final SizeLimitingFilter sizeLimitingFilter) throws Exception {

        http
                // No session and no cookie-based authentication, so there is nothing for CSRF to forge.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("frame-ancestors 'self'"))
                        .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN)))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();

    }

}