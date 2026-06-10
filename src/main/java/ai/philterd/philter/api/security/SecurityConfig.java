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

import ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter;
import ai.philterd.philter.api.filters.size.SizeLimitingFilter;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.views.LoginView;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static com.vaadin.flow.spring.security.VaadinSecurityConfigurer.vaadin;

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
    public SizeLimitingFilter sizeLimitingFilter(@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        return new SizeLimitingFilter(resolver);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().requestMatchers("/public/**", "/themes/**", "/favicon.ico");
    }

    /**
     * Security chain for the stateless, Bearer-token API ({@code /api/**}). It is registered ahead of
     * the UI chain so API requests never touch the Vaadin/session machinery.
     *
     * <p>The chain creates no HTTP session ({@link SessionCreationPolicy#STATELESS}): authentication is
     * re-established from the API key on every request by {@link ApiAuthenticationFilter}, so nothing is
     * carried in a session between requests. That is what lets the API scale horizontally behind a plain
     * load balancer, unlike the session-bound Vaadin dashboard. The filter performs authentication
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
     * Security chain for everything that is not the API: the session-based Vaadin dashboard and the
     * actuator endpoints. This is the catch-all chain ({@code @Order(2)}), so it handles every request
     * the API chain above did not match.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(final HttpSecurity http,
                                           final SizeLimitingFilter sizeLimitingFilter) throws Exception {

        http
                // CSRF protection is left enabled for the Vaadin UI (Vaadin's security configurer
                // handles its own internal requests). It is disabled only for the actuator endpoints,
                // which do not use cookie-based sessions and are therefore not susceptible to CSRF.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/actuator/**"))
                .headers(headers -> headers.frameOptions(headers2 -> headers2.disable()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**", "/styles/**", "/icons/**", "/actuator/**", "/themes/**", "/favicon.ico").permitAll()
                        // The OpenAPI specification and Swagger UI are public, matching the
                        // documented behavior (ApiAuthenticationFilter also allows these paths,
                        // but it only runs on the /api/** chain, so they must be permitted here).
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                )

                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/dashboard", true))
                .with(vaadin(), vaadin -> vaadin.loginView(LoginView.class));

        return http.build();

    }

}