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

import org.bson.Document;
import org.bson.types.ObjectId;

public class LensEntity extends AbstractEntity {

    private ObjectId id;
    private String name;
    private String description;
    private String displayName;

    public static LensEntity fromDocument(final Document document) {
        final LensEntity lensEntity = new LensEntity();
        lensEntity.setId(document.getObjectId("_id"));
        lensEntity.setName(document.getString("name"));
        lensEntity.setDescription(document.getString("description"));
        lensEntity.setDisplayName(document.getString("displayName"));
        return lensEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("name", name);
        document.put("description", description);
        document.put("displayName", displayName);
        return document;
    }

    public String getFormattedName() {
        return displayName + " (" + name + ")";
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

}