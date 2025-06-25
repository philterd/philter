/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.ui;

import ai.philterd.philter.PhilterClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;
import org.thymeleaf.util.StringUtils;

@PropertySource(value="file:philter-ui.properties")
@ComponentScan("ai.philterd.philter")
@SpringBootApplication
public class PhilterUiApplication {

    private static final Logger LOGGER = LogManager.getLogger(PhilterUiApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(PhilterUiApplication.class, args);
    }

    @Value("${philter.endpoint}")
    private String philterEndpoint;

    @Value("${client.timeout}")
    private long timeout = 60000;

    @Value("${client.ssl.keystore}")
    private String sslKeystore;

    @Value("${client.ssl.keystore.password}")
    private String sslKeystorePassword;

    @Value("${client.ssl.truststore}")
    private String sslTruststore;

    @Value("${client.ssl.truststore.password}")
    private String sslTruststorePassword;

    @Bean
    public Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    @Bean
    public PhilterClient philterClient() throws Exception {

        LOGGER.info("Using Philter endpoint {}", philterEndpoint);

        if(StringUtils.isEmptyOrWhitespace(sslKeystore)) {

            return new PhilterClient.PhilterClientBuilder()
                    .withTimeout(timeout)
                    .withEndpoint(philterEndpoint)
                    .build();

        } else {

            LOGGER.info("Client SSL keystore: {}", sslKeystore);
            LOGGER.info("Client SSL truststore: {}", sslTruststore);

            // PHI-359: Handle client certificate.
            return new PhilterClient.PhilterClientBuilder()
                    .withTimeout(timeout)
                    .withSslConfiguration(sslKeystore, sslKeystorePassword, sslTruststore, sslTruststorePassword)
                    .withEndpoint(philterEndpoint)
                    .build();

        }

    }

}