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
package ai.philterd.philter;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.NoOpAuditEventPublisher;
import ai.philterd.philter.data.MongoClientUtil;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.GlobalTermsDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.LocalEncryptionService;
import ai.philterd.philter.services.usage.OpenSearchRedactionsUsageService;
import ai.philterd.philter.services.usage.apirequests.OpenSearchApiRequestsUsageService;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@PropertySource(value="file:philter.properties", ignoreResourceNotFound = true)
@Configuration
@Theme(value="philter", variant=Lumo.LIGHT)
@SpringBootApplication
public class PhilterApplication implements AppShellConfigurator {

    private static final Logger LOGGER = LogManager.getLogger(PhilterApplication.class);

    private final String CACHE_HOSTNAME = System.getenv().getOrDefault("CACHE_HOSTNAME", "localhost");
    private final String CACHE_PASSWORD = System.getenv().getOrDefault("CACHE_PASSWORD", "");
    private final boolean CACHE_SSL = Boolean.parseBoolean(System.getenv().getOrDefault("CACHE_SSL", "false"));

    public static void main(String[] args) {

        LOGGER.info("Starting Philter...");
        SpringApplication.run(PhilterApplication.class, args);

    }

    @Bean
    public Gson gson() {
        return new Gson();
    }

    @Bean
    public HttpClient httpClient() {

        final PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(10)
                        .setMaxConnPerRoute(10)
                        .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                        .build();

        final CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .evictIdleConnections(TimeValue.ofSeconds(30))
                        .evictExpiredConnections()
                        .disableAutomaticRetries()
                        .build();

        return httpClient;

    }

    @Bean
    public MongoClient mongoClient() {
        return MongoClientUtil.getClient();
    }

    @Bean
    public PdfFilterService pdfFilterService() throws IOException {
        return new PdfFilterService(phileasConfiguration(), contextService(), vectorService(), httpClient());
    }

    @Bean
    public PlainTextFilterService plainTextFilterService() throws IOException {
        return new PlainTextFilterService(phileasConfiguration(), contextService(), vectorService(), httpClient());
    }

    @Bean
    public PhileasConfiguration phileasConfiguration() throws IOException {

        final Properties properties = new Properties();

        final Path path = Paths.get("philter.properties");
        if(Files.exists(path)) {
            final FileReader fileReader = new FileReader("philter.properties");
            properties.load(fileReader);
        } else {
            LOGGER.warn("Philter configuration file not found: philter.properties. Default values will be used.");
        }

        return new PhileasConfiguration(properties);

    }

    @Bean
    public AuditEventPublisher auditEventPublisher() {
        return new NoOpAuditEventPublisher();
    }

    @Bean
    public PhilterConfiguration philterConfiguration() throws IOException {

        final Properties properties = new Properties();

        final Path path = Paths.get("philter.properties");
        if(Files.exists(path)) {
            final FileReader fileReader = new FileReader("philter.properties");
            properties.load(fileReader);
        } else {
            LOGGER.warn("Philter configuration file not found: philter.properties. Default values will be used.");
        }

        return new PhilterConfiguration(properties);

    }

    @Bean
    public ContextCache contextCache() {
        LOGGER.info("Initializing context cache.");
        return new ContextCache(CACHE_HOSTNAME, 6379, CACHE_PASSWORD, CACHE_SSL);
    }

    @Bean
    public ApiKeyCache apiKeyCache() {
        LOGGER.info("Initializing API key cache.");
        return new ApiKeyCache(CACHE_HOSTNAME, 6379, CACHE_PASSWORD, CACHE_SSL);
    }

    @Bean
    public ContextEntryDataService contextEntryDataService() {
        return new ContextEntryDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public ContextDataService contextDataService() {
        return new ContextDataService(mongoClient(), contextCache(), auditEventPublisher());
    }

    @Bean
    public OpenSearchRedactionsUsageService openSearchRedactionsUsageService() {
        return new OpenSearchRedactionsUsageService();
    }

    @Bean
    public OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService() {
        return new OpenSearchApiRequestsUsageService();
    }

    @Bean
    public GlobalTermsDataService globalTermsService() {
        return new GlobalTermsDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public ApiKeyDataService apiKeyDataService() {
        return new ApiKeyDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public PolicyDataService policyDataService() {
        return new PolicyDataService(mongoClient(), auditEventPublisher(), gson());
    }

    @Bean
    public CustomListDataService customListDataService() {
        return new CustomListDataService(mongoClient(), encryptionService(), auditEventPublisher());
    }

    @Bean
    public UserService userService() {
        return new UserService(mongoClient(), encryptionService(), auditEventPublisher());
    }

    @Bean
    public EncryptionService encryptionService() {
        return new LocalEncryptionService();
    }

    @Bean
    public ContextService contextService() {
        return new DefaultContextService();
    }

    @Bean
    public VectorService vectorService() {
        return new InMemoryVectorService();
    }

    @Bean
    public UserDetailsService userDetailsService(final UserService userService) {
        return email -> {
            final ai.philterd.philter.data.entities.UserEntity user = userService.findByEmail(email);
            if (user == null) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email);
            }
            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getEmail())
                    .password(user.getPassword())
                    .roles(user.getRole() != null ? user.getRole().toUpperCase() : "USER")
                    .build();
        };
    }

}
