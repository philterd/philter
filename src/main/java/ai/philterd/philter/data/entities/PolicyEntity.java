package ai.philterd.philter.data.entities;

import org.bson.Document;
import org.bson.types.ObjectId;

public class PolicyEntity extends AbstractEntity {

    private ObjectId id;
    private String name;
    private String policy;

    public static PolicyEntity fromDocument(final Document document) {
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setId(document.getObjectId("_id"));
        policyEntity.setPolicy(document.getString("policy"));
        policyEntity.setName(document.getString("name"));
        return policyEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("policy", policy);
        document.put("name", name);
        return document;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public ObjectId getId() {
        return id;
    }

}
