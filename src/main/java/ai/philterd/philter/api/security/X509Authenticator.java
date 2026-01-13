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

import com.vaadin.flow.spring.security.RequestUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class X509Authenticator {

    private static final Logger LOGGER = LogManager.getLogger(X509Authenticator.class);

    private final RequestUtil requestUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Value("${server.ssl.client-auth:none}")
    private String clientAuth;

    public X509Authenticator(final RequestUtil requestUtil) {
        this.requestUtil = requestUtil;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {

        if(!StringUtils.equalsIgnoreCase(clientAuth, "none")) {

            LOGGER.info("Mutual SSL authentication is enabled.");
            http.authorizeHttpRequests(authorize -> authorize
                    .requestMatchers(requestUtil::isFrameworkInternalRequest).permitAll()
                    .requestMatchers("/public/**", "/styles/**", "/images/**", "/VAADIN/**").permitAll()
                    .requestMatchers("/api/status").permitAll()
                    .anyRequest().authenticated()
            ).x509(x509 -> x509
                    .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                    .userDetailsService(userDetailsService)
            );

        } else {

            http
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(requestUtil::isFrameworkInternalRequest).permitAll()
                            .requestMatchers("/public/**", "/styles/**", "/images/**", "/VAADIN/**").permitAll()
                            .requestMatchers("/api/status").permitAll()
                            .anyRequest().permitAll()
                    )
                    .csrf(csrf -> csrf
                            .ignoringRequestMatchers(requestUtil::isFrameworkInternalRequest)
                    );

        }

        return http.build();

    }

}