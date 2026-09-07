package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs only against an explicitly supplied, isolated real MongoDB with user-administration rights. */
@EnabledIfSystemProperty(named = "philter.test.mongoUri", matches = ".+")
class RequiredSchemaMongoIT {
    private MongoClient client;
    private MongoDatabase database;
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    @BeforeEach void connect() {
        client = spy(MongoClients.create(System.getProperty("philter.test.mongoUri")));
        database = client.getDatabase("philter_p1_" + new ObjectId());
        doReturn(database).when(client).getDatabase("philter");
    }
    @AfterEach void cleanup() { database.drop(); client.close(); }

    @Test void rejectsChangedTtlAndLeavesExistingSchemaUntouched() {
        var collection = database.getCollection("pending_documents");
        collection.createIndex(Indexes.ascending("retention_at"), new IndexOptions().expireAfter(1L, TimeUnit.SECONDS));
        assertThrows(RuntimeException.class, () -> new PendingDocumentDataService(client, new TestEncryptionService(), audit));
        Document index = collection.listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(i -> i.containsKey("expireAfterSeconds")).findFirst().orElseThrow();
        assertEquals(1L, ((Number) index.get("expireAfterSeconds")).longValue());
    }

    @Test void rejectsLedgerAutomaticExpiryRegardlessOfIndexName() {
        database.getCollection("ledger").createIndex(Indexes.ascending("timestamp"),
                new IndexOptions().name("unexpected_expiry").expireAfter(1L, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> new LedgerDataService(client, new TestEncryptionService(), audit,
                new LegalHoldDataService(client, audit), mock(SigningService.class)));
        assertTrue(database.getCollection("ledger").listIndexes().into(new java.util.ArrayList<>()).stream()
                .anyMatch(i -> "unexpected_expiry".equals(i.getString("name"))));
    }

    @Test void requiredUniquenessFailureStopsConstruction() {
        var collection = database.getCollection("users");
        collection.insertMany(List.of(new Document("username", "duplicate"), new Document("username", "duplicate")));
        assertThrows(RuntimeException.class, () -> new UserService(client, new TestEncryptionService(), audit));
    }

    @Test void restrictedPrivilegesCannotSilentlySkipRequiredIndexes() {
        database.runCommand(new Document("createUser", "reader").append("pwd", "isolated-reader-password")
                .append("roles", List.of(new Document("role", "read").append("db", database.getName()))));
        var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(System.getProperty("philter.test.mongoUri")))
                .credential(MongoCredential.createCredential("reader", database.getName(), "isolated-reader-password".toCharArray())).build();
        try (MongoClient restricted = spy(MongoClients.create(settings))) {
            doReturn(restricted.getDatabase(database.getName())).when(restricted).getDatabase("philter");
            var failure = assertThrows(com.mongodb.MongoCommandException.class,
                    () -> new UserService(restricted, new TestEncryptionService(), audit));
            assertEquals(13, failure.getErrorCode());
        }
    }

    @Test void requiredSchemaIsVerifiedAndIdempotent() {
        new UserService(client, new TestEncryptionService(), audit);
        new UserService(client, new TestEncryptionService(), audit);
        new PendingDocumentDataService(client, new TestEncryptionService(), audit);
        new PendingDocumentDataService(client, new TestEncryptionService(), audit);
        assertTrue(database.getCollection("users").listIndexes().into(new java.util.ArrayList<>()).stream()
                .anyMatch(i -> i.getBoolean("unique", false)));
    }
    @Test void snapshotValidationFailureIsNotMistakenForDuplicateContent() {
        database.createCollection("policy_contents", new com.mongodb.client.model.CreateCollectionOptions()
                .validationOptions(new com.mongodb.client.model.ValidationOptions()
                        .validator(new Document("required_flag", true))));
        var versions = new PolicyVersionDataService(client, audit);
        var policy = new ai.philterd.philter.data.entities.PolicyEntity();
        policy.setUserId(new ObjectId()); policy.setName("policy"); policy.setPolicy("{}");
        var failure = assertThrows(com.mongodb.MongoWriteException.class, () -> versions.snapshot(policy));
        assertEquals(121, failure.getError().getCode());
        assertNull(versions.findByContentHash(PolicyVersionDataService.contentHash("{}")));
    }
}
