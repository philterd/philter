/*
 *     Copyright 2024 Philterd, LLC @ https://www.philterd.ai
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

import ai.philterd.philter.api.controllers.PoliciesApiController;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class X509Authenticator {

    private static final Logger LOGGER = LogManager.getLogger(PoliciesApiController.class);

    @Autowired
    private UserDetailsService userDetailsService;

    @Value("${server.ssl.client-auth:none}")
    private String clientAuth;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        if(!StringUtils.equalsIgnoreCase(clientAuth, "none")) {

            LOGGER.info("Mutual SSL authentication is enabled.");

            http.authorizeRequests()
                    .requestMatchers("/api/status").permitAll()
                    .anyRequest()
                    .authenticated()
                    .and()
                    .x509()
                    .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                    .userDetailsService(userDetailsService);

            return http.build();

        } else {

            LOGGER.info("Mutual SSL authentication is disabled.");

            http.csrf().disable().authorizeRequests().anyRequest().permitAll();

        }

        return http.build();
    }

}