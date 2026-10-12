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

import java.util.ArrayList;
import java.util.List;

public class RedactListsEntity extends AbstractEncryptedEntity {

    private String id;
    private List<String> termsToAlwaysRedact;
    private List<String> termsToNeverRedact;
    private String userId;
    private long alwaysRedactRevision;
    private long neverRedactRevision;

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

    public String getId() {
        return id;
    }

    public void setId(String id) {
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

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAlwaysRedactRevision() {
        return alwaysRedactRevision;
    }

    public void setAlwaysRedactRevision(long alwaysRedactRevision) {
        this.alwaysRedactRevision = alwaysRedactRevision;
    }

    public long getNeverRedactRevision() {
        return neverRedactRevision;
    }

    public void setNeverRedactRevision(long neverRedactRevision) {
        this.neverRedactRevision = neverRedactRevision;
    }

}