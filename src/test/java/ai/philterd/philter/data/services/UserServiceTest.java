package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private ContextDataService contextDataService;

    @Mock
    private PolicyDataService policyDataService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("users")).thenReturn(mongoCollection);
        userService = new UserService(mongoClient, encryptionService, auditEventPublisher);
        // Important: we need to reset since UserService constructor already calls mongoClient.getDatabase and mongoDatabase.getCollection
        // and we might want different behavior in tests.
    }

    @Test
    void findByEmail() {
        String email = "test@example.com";
        Document doc = new Document("_id", new ObjectId()).append("email", email);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        UserEntity user = userService.findByEmail(email);

        assertNotNull(user);
        assertEquals(email, user.getEmail());
    }

    @Test
    void createUser() {
        String email = "new@example.com";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        ObjectId userId = new ObjectId();
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(userId));

        ServiceResponse response = userService.createUser(email, "password", "role", contextDataService, policyDataService);

        assertTrue(response.isSuccessful());
        verify(contextDataService).save(any());
        verify(policyDataService).save(any());
    }

    @Test
    void findAll() {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find()).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));

        List<UserEntity> users = userService.findAll(0, 10);

        assertNotNull(users);
        assertTrue(users.isEmpty());
    }

    @Test
    void count() {
        when(mongoCollection.countDocuments()).thenReturn(10L);
        assertEquals(10, userService.count());
    }

    @Test
    void deleteUser() {
        UserEntity user = new UserEntity();
        user.setId(new ObjectId());

        // We need a mock for the database and collection specifically for this test
        // as deleteUser calls mongoClient.getDatabase multiple times.
        MongoDatabase mockPhilterDatabase = mock(MongoDatabase.class);
        MongoDatabase mockPhilterdDataServicesDatabase = mock(MongoDatabase.class);
        MongoCollection<Document> mockGenericCollection = mock(MongoCollection.class);

        when(mongoClient.getDatabase("philter")).thenReturn(mockPhilterDatabase);
        when(mongoClient.getDatabase("philterd_data_services")).thenReturn(mockPhilterdDataServicesDatabase);
        
        when(mockPhilterDatabase.getCollection(anyString())).thenReturn(mockGenericCollection);
        when(mockPhilterdDataServicesDatabase.getCollection(anyString())).thenReturn(mockGenericCollection);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        userService.deleteUser(user);

        // Verify that the final delete on the 'users' collection happened.
        // In UserService, 'collection' is the 'users' collection from AbstractEncryptedService.
        verify(mongoCollection).deleteOne(any(Bson.class));
        
        // Verify other deletions happened on the other collections
        verify(mockPhilterDatabase, atLeastOnce()).getCollection(anyString());
        verify(mockPhilterdDataServicesDatabase, atLeastOnce()).getCollection(anyString());
    }
}
