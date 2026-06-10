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

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.MongoDBAuditEventPublisher;
import ai.philterd.philter.data.MongoClientUtil;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.cache.LoginAttemptCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.LocalEncryptionService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.filtering.RedactionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.webhook.WebhookService;
import ai.philterd.philter.utils.EnvUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.userdetails.UserDetailsService;


@Configuration
@Theme("philter")
// Vaadin 25 loads all Lumo modules automatically except the utility classes, which must be
// requested explicitly. AbstractRestrictedView uses LumoUtility CSS classes, so load the
// utility stylesheet here (replaces the removed "utility" entry in theme.json's lumoImports).
@StyleSheet(Lumo.UTILITY_STYLESHEET)
// Override the react-router version that Vaadin's React integration pulls in transitively. The
// platform-bundled version (7.13.1 with Vaadin 25.1.x) has open security advisories; pin a patched
// release. See the Dependabot alerts for react-router (GHSA-8x6r-g9mw-2r78 and related).
@NpmPackage(value = "react-router", version = "7.17.0")
@SpringBootApplication
@PropertySource("classpath:internal.properties")
@EnableScheduling
public class PhilterApplication implements AppShellConfigurator {

    private static final Logger LOGGER = LogManager.getLogger(PhilterApplication.class);

    // When unset/blank, the caches fall back to an in-memory (ephemeral) implementation.
    private final String CACHE_HOSTNAME = System.getenv().getOrDefault("CACHE_HOSTNAME", "");
    private final int CACHE_PORT = EnvUtils.getInt("CACHE_PORT", 6379);
    private final String CACHE_PASSWORD = System.getenv().getOrDefault("CACHE_PASSWORD", "");
    private final boolean CACHE_SSL = Boolean.parseBoolean(System.getenv().getOrDefault("CACHE_SSL", "false"));

    static void main(String[] args) {

        LOGGER.info("Starting Philter...");
        SpringApplication.run(PhilterApplication.class, args);

        if (AdminAccessConfig.isCrossUserAccessEnabled()) {
            LOGGER.warn("****************************************************************************************");
            LOGGER.warn("* ADMIN_CROSS_USER_ACCESS_ENABLED is ON: administrators can view and act on OTHER     *");
            LOGGER.warn("* users' contexts, policies, custom lists, documents, and redaction ledger via the    *");
            LOGGER.warn("* API 'owner' parameter and the admin 'All ...' UI tabs. Disable this unless you      *");
            LOGGER.warn("* explicitly require cross-user administration.                                       *");
            LOGGER.warn("****************************************************************************************");
        }

    }

    @Bean
    public Gson gson() {
        return new Gson();
    }

    // Sets the title and version shown in the generated OpenAPI specification (and Swagger UI),
    // replacing springdoc's defaults ("OpenAPI definition" / "v0"). The version is the build version
    // so the published spec is labeled with the Philter release it was generated from.
    @Bean
    public OpenAPI philterOpenAPI(@Value("${build.version}") final String version) {
        return new OpenAPI().info(new Info().title("Philter API").version(version));
    }

    @Bean
    public HttpClient httpClient() {

        final PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(10)
                        .setMaxConnPerRoute(10)
                        .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                        .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .evictExpiredConnections()
                .disableAutomaticRetries()
                .build();

    }

    @Bean
    public RedactionService redactionService(final MeterRegistry meterRegistry) {
        return new RedactionService(mongoClient(), policyDataService(), customListService(), redactListsService(), contextDataService(), auditEventPublisher(), ledgerService(), userService(), meterRegistry, phieldPublisher(), piiCountAggregatePublisher(), redactionCache());
    }

    @Bean
    public RedactionCache redactionCache() {
        // Deliberately in-process only (not Valkey): the cached policy JSON can carry PII in
        // filter-strategy conditions and the redact-list terms are sensitive, so this data is kept
        // within the JVM rather than written to a shared/persistent cache.
        return new RedactionCache();
    }

    @Bean
    public PhieldPublisher phieldPublisher() {
        return new PhieldPublisher(adminSettingsDataService());
    }

    @Bean
    public PiiCountAggregatePublisher piiCountAggregatePublisher() {
        return new PiiCountAggregatePublisher(mongoClient(), adminSettingsDataService());
    }

    @Bean
    public MongoClient mongoClient() {
        return MongoClientUtil.getClient();
    }


    @Bean
    public AuditEventPublisher auditEventPublisher() {
        return new MongoDBAuditEventPublisher(mongoClient());
    }

    @Bean
    public AuditLogService auditLogService() {
        return new AuditLogService(mongoClient());
    }

    @Bean
    public ContextCache contextCache() {
        LOGGER.info("Initializing context cache.");
        return new ContextCache(CACHE_HOSTNAME, CACHE_PORT, CACHE_PASSWORD, CACHE_SSL);
    }

    @Bean
    public ApiKeyCache apiKeyCache() {
        LOGGER.info("Initializing API key cache.");
        return new ApiKeyCache(CACHE_HOSTNAME, CACHE_PORT, CACHE_PASSWORD, CACHE_SSL);
    }

    @Bean
    public LoginAttemptCache loginAttemptCache() {
        LOGGER.info("Initializing login attempt cache.");
        return new LoginAttemptCache(CACHE_HOSTNAME, CACHE_PORT, CACHE_PASSWORD, CACHE_SSL);
    }

    @Bean
    public ContextDataService contextDataService() {
        return new ContextDataService(mongoClient(), contextCache(), auditEventPublisher());
    }

    @Bean
    public LegalHoldDataService legalHoldDataService() {
        return new LegalHoldDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public LedgerDataService ledgerService() {
        return new LedgerDataService(mongoClient(), encryptionService(), auditEventPublisher(), legalHoldDataService());
    }

    @Bean
    public ContextEntryDataService contextEntryDataService() {
        return new ContextEntryDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public CustomListDataService customListService() {
        return new CustomListDataService(mongoClient(), encryptionService(), auditEventPublisher());
    }

    @Bean
    public RedactListsDataService redactListsService() {
        return new RedactListsDataService(mongoClient(), encryptionService(), auditEventPublisher());
    }

    @Bean
    public ApiKeyDataService apiKeyDataService() {
        return new ApiKeyDataService(mongoClient(), auditEventPublisher(), apiKeyCache());
    }

    @Bean
    public PendingDocumentDataService pendingDocumentDataService() {
        return new PendingDocumentDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public WebhookDeliveryDataService webhookDeliveryDataService() {
        return new WebhookDeliveryDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public WebhookService webhookService() {
        return new WebhookService(httpClient());
    }

    @Bean
    public PolicyVersionDataService policyVersionDataService() {
        return new PolicyVersionDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public PolicyDataService policyDataService() {
        return new PolicyDataService(mongoClient(), auditEventPublisher(), gson(), policyVersionDataService());
    }

    @Bean
    public CustomListDataService customListDataService() {
        return new CustomListDataService(mongoClient(), encryptionService(), auditEventPublisher());
    }

    @Bean
    public AdminSettingsDataService adminSettingsDataService() {
        return new AdminSettingsDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public SigningKeyDataService signingKeyDataService() {
        return new SigningKeyDataService(mongoClient(), auditEventPublisher());
    }

    @Bean
    public SigningService signingService() {
        return new SigningService(signingKeyDataService(), adminSettingsDataService());
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
    public UserDetailsService userDetailsService(final UserService userService, final LoginAttemptCache loginAttemptCache) {
        return email -> {
            final UserEntity user = userService.findByUsername(email);
            if (user == null) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found: " + email);
            }
            // When the account is locked out (too many recent failed logins), build it as locked so
            // Spring Security rejects the attempt with a LockedException before checking the password.
            final boolean locked = loginAttemptCache.isLocked(email);
            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getUsername())
                    .password(user.getPassword())
                    .roles(user.getRole() != null ? user.getRole().toUpperCase() : "USER")
                    .accountLocked(locked)
                    .build();
        };
    }

}
