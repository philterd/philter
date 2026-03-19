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

import ai.philterd.phileas.model.filtering.Span;
import org.bson.Document;
import org.bson.types.ObjectId;

public class VectorEntity extends AbstractEntity {

    private ObjectId id;
    private String context;
    private double hash;
    private Span span;
    private int vectorSize;
    private String filterType;
    private ObjectId userId;

    public VectorEntity() {

    }

    public static VectorEntity fromDocument(final Document document) {
        final VectorEntity vectorEntity = new VectorEntity();
        vectorEntity.setId(document.getObjectId("_id"));
        vectorEntity.setUserId(document.getObjectId("user_id"));
        vectorEntity.setContext(document.getString("context"));
        vectorEntity.setHash(document.getDouble("hash"));
        vectorEntity.setVectorSize(document.getInteger("vector_size"));
        vectorEntity.setFilterType(document.getString("filter_type"));
        return vectorEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("context", context);
        document.put("hash", hash);
        document.put("vector_size", vectorSize);
        document.put("filter_type", filterType);
        return document;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public Span getSpan() {
        return span;
    }

    public void setSpan(Span span) {
        this.span = span;
    }

    public int getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(int vectorSize) {
        this.vectorSize = vectorSize;
    }

    public String getFilterType() {
        return filterType;
    }

    public void setFilterType(String filterType) {
        this.filterType = filterType;
    }

    public double getHash() {
        return hash;
    }

    public void setHash(double hash) {
        this.hash = hash;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

}