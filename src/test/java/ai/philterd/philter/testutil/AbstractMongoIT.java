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

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.InetSocketAddress;

/**
 * Base class for integration tests that exercise data-access code against a real MongoDB wire
 * protocol. It uses an in-process, in-memory {@code mongo-java-server} (no Docker, no external
 * process) so the actual driver, queries, indexes, and aggregations run — unlike the mock-based
 * unit tests. A fresh, empty server is started before each test and shut down after, giving full
 * isolation between tests.
 */
public abstract class AbstractMongoIT {

    private MongoServer server;
    protected MongoClient mongoClient;

    @BeforeEach
    void startMongo() {
        server = new MongoServer(new MemoryBackend());
        final InetSocketAddress address = server.bind();
        mongoClient = MongoClients.create("mongodb://" + address.getHostName() + ":" + address.getPort());
    }

    /**
     * Stores a user and returns their id. Context tokens are hashed for their owner's username, so a context's owner
     * has to exist.
     */
    protected String newUser() {
        final org.bson.types.ObjectId id = new org.bson.types.ObjectId();
        mongoClient.getDatabase("philter").getCollection("users")
                .insertOne(new org.bson.Document("_id", id).append("username", "user-" + id.toHexString()));
        return id.toHexString();
    }

    @AfterEach
    void stopMongo() {
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

}
