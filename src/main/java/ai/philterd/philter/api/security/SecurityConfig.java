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

import static com.vaadin.flow.spring.security.VaadinSecurityConfigurer.vaadin;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

import ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter;
import ai.philterd.philter.api.filters.size.SizeLimitingFilter;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.usage.apirequests.OpenSearchApiRequestsUsageService;
import ai.philterd.philter.views.LoginView;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http, final AuditEventPublisher auditEventPublisher,
                                           final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService,
                                           final SizeLimitingFilter sizeLimitingFilter) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(headers2 -> headers2.disable()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**", "/styles/**", "/icons/**", "/api/**","/themes/**", "/favicon.ico").permitAll()
                )

                .addFilterBefore(sizeLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiAuthenticationFilter(mongoClient, auditEventPublisher, openSearchApiRequestsUsageService, gson), UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.defaultSuccessUrl("/dashboard", true))
                .with(vaadin(), vaadin -> vaadin.loginView(LoginView.class));

        return http.build();

    }

}