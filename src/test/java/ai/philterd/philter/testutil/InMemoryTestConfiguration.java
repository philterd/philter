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
package ai.philterd.philter.testutil;

import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.net.InetSocketAddress;

/**
 * Lets a {@code @SpringBootTest} boot the whole application with no external dependencies.
 *
 * <p>Replaces the {@code mongoClient} bean with one backed by an in-process, in-memory
 * mongo-java-server, and the encryption service with a test implementation so
 * {@code PHILTER_ENCRYPTION_KEY} is not required. The application is otherwise the real one.
 *
 * <p>Import it and allow bean definition overriding:
 * <pre>
 * &#64;SpringBootTest(webEnvironment = RANDOM_PORT,
 *         properties = {"spring.main.allow-bean-definition-overriding=true"})
 * &#64;Import(InMemoryTestConfiguration.class)
 * </pre>
 */
@TestConfiguration
public class InMemoryTestConfiguration {

    @Bean(destroyMethod = "shutdown")
    public MongoServer mongoServer() {
        return new MongoServer(new MemoryBackend());
    }

    @Bean
    public MongoClient mongoClient(final MongoServer mongoServer) {
        final InetSocketAddress address = mongoServer.bind();
        return MongoClients.create("mongodb://" + address.getHostName() + ":" + address.getPort());
    }

    @Bean
    public EncryptionService encryptionService() {
        return new TestEncryptionService();
    }

}
