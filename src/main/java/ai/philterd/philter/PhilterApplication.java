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
import ai.philterd.philter.services.policies.InMemoryPolicyService;
import ai.philterd.philter.services.policies.LocalPolicyService;
import ai.philterd.philter.services.policies.OpenSearchPolicyService;
import ai.philterd.philter.services.policies.PolicyService;
import com.google.gson.Gson;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

@PropertySource(value="file:philter.properties")
@Configuration
@Theme(themeClass = Lumo.class, variant = Lumo.LIGHT)
@SpringBootApplication
public class PhilterApplication implements AppShellConfigurator {

    private static final Logger LOGGER = LogManager.getLogger(PhilterApplication.class);

    public static void main(String[] args) {

        LOGGER.info("Starting Philter...");
        SpringApplication.run(PhilterApplication.class, args);

    }

    @Bean
    public Gson gson() {
        return new Gson();
    }

    @Bean
    public PdfFilterService pdfFilterService() throws IOException {
        return new PdfFilterService(phileasConfiguration(), contextService(), vectorService());
    }

    @Bean
    public PlainTextFilterService plainTextFilterService() throws IOException {
        return new PlainTextFilterService(phileasConfiguration(), contextService(), vectorService());
    }

    @Bean
    public PhileasConfiguration phileasConfiguration() throws IOException {

        final FileReader fileReader = new FileReader("philter.properties");
        final Properties properties = new Properties();
        properties.load(fileReader);

        return new PhileasConfiguration(properties);

    }

    @Bean
    public PhilterConfiguration philterConfiguration() throws IOException {
        return  new PhilterConfiguration("philter.properties", "Philter");
    }

    @Bean
    public PolicyService policyService() throws Exception {

        final PhilterConfiguration philterConfiguration = philterConfiguration();

        final PolicyService policyService;

        if("local".equalsIgnoreCase(philterConfiguration.policyService())) {
            policyService = new LocalPolicyService(philterConfiguration);
        } else if("opensearch".equalsIgnoreCase(philterConfiguration.policyService())) {
            policyService = new OpenSearchPolicyService(philterConfiguration);
        } else {
            policyService = new InMemoryPolicyService();
        }

        return policyService;

    }

    @Bean
    public ContextService contextService() {
        return new DefaultContextService();
    }

    @Bean
    public VectorService vectorService() {
        return new InMemoryVectorService();
    }

}
