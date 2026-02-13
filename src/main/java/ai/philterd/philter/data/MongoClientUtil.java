package ai.philterd.philter.data;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

public class MongoClientUtil {

    public static MongoClient getClient() {

        final String mongoDbConnectionString = System.getenv().getOrDefault("MONGODB_CONNECTION_STRING", "mongodb://localhost:27017");

        final MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoDbConnectionString))
                .build();

        return MongoClients.create(settings);

    }

}
