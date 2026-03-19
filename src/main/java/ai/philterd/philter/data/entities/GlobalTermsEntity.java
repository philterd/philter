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
package ai.philterd.philter.data.entities;

import ai.philterd.philter.model.SeparatedTermLists;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

public class GlobalTermsEntity extends AbstractEntity {

    private ObjectId id;
    private List<String> termsToAlwaysRedact;
    private List<String> termsToNeverRedact;
    private ObjectId userId;

    public static GlobalTermsEntity fromDocument(final Document document) {

        final GlobalTermsEntity globalTermsEntity = new GlobalTermsEntity();
        globalTermsEntity.setId(document.getObjectId("_id"));
        globalTermsEntity.setUserId(document.getObjectId("user_id"));

        if(document.getList("terms_to_always_redact", String.class) != null) {
            globalTermsEntity.setTermsToAlwaysRedact(document.getList("terms_to_always_redact", String.class));
        } else {
            globalTermsEntity.setTermsToAlwaysRedact(new ArrayList<>());
        }

        if(document.getList("terms_to_never_redact", String.class) != null) {
            globalTermsEntity.setTermsToNeverRedact(document.getList("terms_to_never_redact", String.class));
        } else {
            globalTermsEntity.setTermsToNeverRedact(new ArrayList<>());
        }

        return globalTermsEntity;
    }

    public SeparatedTermLists breakAlwaysRedactIntoSeparateLists() {

        final List<String> fuzzy = new ArrayList<>();
        final List<String> exact = new ArrayList<>();

        for(final String term : termsToAlwaysRedact) {

            if(term.endsWith(":fuzzy")) {
                fuzzy.add(term.substring(0, term.length() - 6));
            } else {
                exact.add(term);
            }

        }

        return new SeparatedTermLists(fuzzy, exact);

    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("terms_to_always_redact", termsToAlwaysRedact);
        document.put("terms_to_never_redact", termsToNeverRedact);
        return document;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public void setTermsToAlwaysRedact(List<String> termsToAlwaysRedact) {
        this.termsToAlwaysRedact = termsToAlwaysRedact;
    }

    public void setTermsToNeverRedact(List<String> termsToNeverRedact) {
        this.termsToNeverRedact = termsToNeverRedact;
    }

    public List<String> getTermsToAlwaysRedact() {
        return termsToAlwaysRedact;
    }

    public List<String> getTermsToNeverRedact() {
        return termsToNeverRedact;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

}