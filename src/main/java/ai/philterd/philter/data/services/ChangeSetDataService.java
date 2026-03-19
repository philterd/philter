package ai.philterd.philter.data.services;

import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ChangeSetEntity;
import ai.philterd.philter.model.ChangeSetType;
import ai.philterd.philter.model.ContentType;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChangeSetDataService extends AbstractEncryptedService<ChangeSetEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeSetDataService.class);

    public static final int MAX_CHANGESET_VERSIONS_PER_DOCUMENT = 20;

    private final EncryptionService encryptionService;

    protected ChangeSetDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "change_sets", encryptionService, auditEventPublisher);
        this.encryptionService = encryptionService;
    }

    public int getCurrentVersion(final ObjectId userId, final String documentId) {

        final List<Integer> versions = getChangeSetVersionsForDocument(userId, documentId);

        if(!versions.isEmpty()) {

            return versions.get(versions.size() - 1);

        } else {

            // The document does not exist.
            LOGGER.warn("Attempting to get current changeset version for document {} that does not exist", documentId);
            return 1;

        }

    }

    public int getNextVersion(final ObjectId userId, final String documentId) {

        // TODO: Query the database to get the max(version) value and add 1 instead of this manual lookup.
        final List<Integer> versions = getChangeSetVersionsForDocument(userId, documentId);

        if(!versions.isEmpty()) {

            return versions.get(versions.size() - 1) + 1;

        } else {

            // The document does not exist.
            LOGGER.warn("Attempting to get next changeset version for document {} that does not exist", documentId);
            return 1;

        }

    }

    public boolean atChangeSetLimit(final ObjectId userId, final String documentId) {

        final Document query = new Document("user_id", userId).append("document_id", documentId);

        final Iterable<Document> documents = collection.find(query);

        final Set<Integer> changeSetVersions = new HashSet<>();

        for (final Document document : documents) {
            changeSetVersions.add(ChangeSetEntity.fromDocument(document, encryptionService).getVersion());
        }

        return changeSetVersions.size() >= MAX_CHANGESET_VERSIONS_PER_DOCUMENT;

    }

    public void deleteChangeSetEntity(final ObjectId userId, final String documentId, final ObjectId changeSetId) {

        collection.deleteOne(new Document("user_id", userId).append("document_id", documentId).append("_id", changeSetId));

    }

    public void deleteByUserIdAndDocumentId(final ObjectId userId, final String documentId) {

        final Document query = new Document("user_id", userId).append("document_id", documentId);

        collection.deleteMany(query);

    }

    public int duplicateChangeSet(final ObjectId userId, final String documentId, final ContentType contentType, final int version) {

        // Get the next version number.
        final int nextVersion = getNextVersion(userId, documentId);

        // Get all changeset entities in the original version.
        final List<ChangeSetEntity> changeSetEntities = findByUserIdAndDocumentId(userId, documentId, version, contentType);

        // Duplicate the changeset entities for the new version.
        for(final ChangeSetEntity changeSetEntity : changeSetEntities) {

            // Duplicate the changeset entity.
            final ChangeSetEntity cse = ChangeSetEntity.duplicate(changeSetEntity, nextVersion);

            // Save the duplicate changeset entity.
            save(cse);

        }

        return nextVersion;

    }

    public List<Integer> getChangeSetVersionsForDocument(final ObjectId userId, final String documentId) {

        final Document query = new Document("user_id", userId).append("document_id", documentId);

        final Iterable<Document> documents = collection.find(query);

        final List<Integer> versions = new ArrayList<>();

        for(final Document document : documents) {
            if (!versions.contains(document.getInteger("version"))) {
                versions.add(document.getInteger("version"));
            }
        }

        // Sort the versions in ascending order.
        Collections.sort(versions);

        return versions;

    }

    public List<ChangeSetEntity> findByUserIdAndDocumentId(final ObjectId userId, final String documentId, final int version, final ContentType contentType) {

        final Document query = new Document("user_id", userId).append("document_id", documentId).append("version", version);

        final Bson sortCriteria;

        // Sort the changeset by the order in which the change was made in the document.
        if(contentType == ContentType.DOCX) {
            sortCriteria = Sorts.orderBy(Sorts.ascending("paragraph"), Sorts.ascending("character_start"));
        } else if(contentType == ContentType.PDF) {
            sortCriteria = Sorts.ascending("character_start");
        } else if(contentType == ContentType.TXT) {
            sortCriteria = Sorts.ascending("character_start");
        } else {
            sortCriteria = Sorts.ascending("character_start");
        }

        final List<ChangeSetEntity> changeSetEntities = new ArrayList<>();

        final Iterable<Document> documents = collection.find(query).sort(sortCriteria);

        for (final Document document : documents) {
            changeSetEntities.add(ChangeSetEntity.fromDocument(document, encryptionService));
        }

        return changeSetEntities;

    }

    public List<ChangeSetEntity> findByUserIdAndDocumentId(final ObjectId userId, final String documentId, final int version, final ContentType contentType, final int offset, final int limit) {

        final Document query = new Document("user_id", userId).append("document_id", documentId).append("version", version);

        final Bson sortCriteria;

        // Sort the changeset by the order in which the change was made in the document.
        if(contentType == ContentType.DOCX) {
            sortCriteria = Sorts.orderBy(Sorts.ascending("paragraph"), Sorts.ascending("character_start"));
        } else if(contentType == ContentType.PDF) {
            sortCriteria = Sorts.ascending("character_start");
        } else if(contentType == ContentType.TXT) {
            sortCriteria = Sorts.ascending("character_start");
        } else {
            sortCriteria = Sorts.ascending("character_start");
        }

        final List<ChangeSetEntity> changeSetEntities = new ArrayList<>();

        final Iterable<Document> documents = collection.find(query).sort(sortCriteria).skip(offset).limit(limit);

        for (final Document document : documents) {
            changeSetEntities.add(ChangeSetEntity.fromDocument(document, encryptionService));
        }

        return changeSetEntities;

    }

    public int count(final ObjectId userId, final String documentId, final int version) {
        final Document query = new Document("user_id", userId).append("document_id", documentId).append("version", version);
        return (int) collection.countDocuments(query);
    }

    public void saveChangeSet(final ObjectId userId, final String documentId, final Map<Integer, Explanation> explanations,
                              final ChangeSetType changeSetType) {

        for(final int paragraphOrPage : explanations.keySet()) {

            final Explanation explanation = explanations.get(paragraphOrPage);

            for (final Span span : explanation.appliedSpans()) {

                final ChangeSetEntity changeSetEntity = new ChangeSetEntity();

                changeSetEntity.setToken(span.getText());
                changeSetEntity.setReplacement(span.getReplacement());
                changeSetEntity.setConfidence(span.getConfidence());
                changeSetEntity.setCharacterStart(span.getCharacterStart());
                changeSetEntity.setCharacterEnd(span.getCharacterEnd());
                changeSetEntity.setType(span.getFilterType().name());
                changeSetEntity.setDocumentId(documentId);
                changeSetEntity.setUserId(userId);
                changeSetEntity.setVersion(1);
                changeSetEntity.setLowerLeftY(span.getLowerLeftY());
                changeSetEntity.setUpperRightY(span.getUpperRightY());
                changeSetEntity.setLowerLeftX(span.getLowerLeftX());
                changeSetEntity.setUpperRightX(span.getUpperRightX());
                changeSetEntity.setLineHash(span.getLineHash());

                if(changeSetType == ChangeSetType.PARAGRAPH) {

                    // Word documents are PARAGRAPH
                    changeSetEntity.setParagraph(paragraphOrPage);

                } else if(changeSetType == ChangeSetType.LINE_NUMBER) {

                    // PDF documents are LINE_NUMBER
                    changeSetEntity.setLineNumber(span.getLineNumber());

                }

                save(changeSetEntity);

            }

        }

    }

}
