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
import ai.philterd.philter.services.usage.apirequests.ApiRequestsUsageService;
import ai.philterd.philter.services.usage.apirequests.OpenSearchApiRequestsUsageService;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.spring.security.RequestUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final MongoClient mongoClient;
    private final Gson gson;
    private final RequestUtil requestUtil;

    public SecurityConfig(final MongoClient mongoClient, final Gson gson, final RequestUtil requestUtil) {
        this.mongoClient = mongoClient;
        this.gson = gson;
        this.requestUtil = requestUtil;
    }

    @Bean
    public SizeLimitingFilter sizeLimitingFilter(@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        return new SizeLimitingFilter(resolver);
    }

    @Bean
    @java.lang.SuppressWarnings("squid:S4502")  // Disabling CSRF is ok here since this is a stateless REST API.
    public SecurityFilterChain filterChain(final HttpSecurity http, final AuditEventPublisher auditEventPublisher, final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService, final SizeLimitingFilter sizeLimitingFilter) throws Exception {

        http
                .authorizeHttpRequests(auth -> auth

                        // 1. Explicitly permit Vaadin's internal framework requests (Critical)
                        .requestMatchers(requestUtil::isFrameworkInternalRequest).permitAll()

                        // 2. Explicitly permit the paths for anonymous/public views
                        .requestMatchers("/", "/api", "/contexts", "/lists", "/policies", "/sdks").permitAll()

                        // 3. Permit access to static resources (Added /VAADIN/** to fix login loop)
                        .requestMatchers("/public/**", "/styles/**", "/icons/**", "/VAADIN/**").permitAll()

                        .requestMatchers("/api/**").authenticated()

                        // 4. All other requests require authentication (The most restrictive rule, published last)
                       // .anyRequest().authenticated()

                );

        http.addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(new ApiAuthenticationFilter(mongoClient, auditEventPublisher, openSearchApiRequestsUsageService, gson), UsernamePasswordAuthenticationFilter.class);
        http.csrf(AbstractHttpConfigurer::disable);

        return http.build();

    }

}