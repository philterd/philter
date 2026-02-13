package ai.philterd.philter.services;

import ai.philterd.philter.data.entities.PolicyEntity;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public class PolicyDataService extends AbstractService<PolicyEntity> {

    public PolicyDataService(final MongoClient mongoClient) {
        super(mongoClient, "policies");
    }

    public List<String> get()  {

        final List<String> names = new ArrayList<>();

        collection.find()
                .projection(Projections.fields(Projections.include("name"), Projections.excludeId()))
                .forEach(document -> names.add(document.getString("name")));

        return names;

    }

    public PolicyEntity get(String policyName) {

        final Document document = collection.find(Filters.eq("name", policyName)).first();

        if (document != null) {
            return PolicyEntity.fromDocument(document);
        }

        return null;

    }

    public List<PolicyEntity> get(int from, int size) {

        final FindIterable<Document> documents = collection.find()
                .sort(Sorts.ascending("name"))
                .skip(from)
                .limit(size);

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyEntity.fromDocument(document);
            policies.add(policyEntity);

        }

        return policies;

    }

    public int count() {
        return (int) collection.countDocuments();
    }

    public void delete(final String policyName) {
        collection.deleteOne(Filters.eq("name", policyName));
    }
    
}
