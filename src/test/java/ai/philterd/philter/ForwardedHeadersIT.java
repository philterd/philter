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

import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * On a detected cloud platform such as Kubernetes, Spring Boot otherwise installs Tomcat's RemoteIpValve, which
 * believes X-Forwarded-For from any private address before TRUSTED_PROXIES is consulted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true",
                "spring.main.cloud-platform=kubernetes"})
class ForwardedHeadersIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired
    private WebServerApplicationContext context;

    @Test
    @DisplayName("Tomcat does not rewrite the client address from forwarded headers on Kubernetes")
    void noRemoteIpValveOnKubernetes() {
        final TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
        for (final Valve valve : webServer.getTomcat().getEngine().getPipeline().getValves()) {
            assertFalse(valve instanceof RemoteIpValve, "RemoteIpValve is installed");
        }
    }

}
