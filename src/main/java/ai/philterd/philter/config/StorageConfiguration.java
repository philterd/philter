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
package ai.philterd.philter.config;

import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * Which storage implementation Philter uses, from {@code STORAGE_TYPE}. Each implementation's configuration
 * applies only when it is selected; an unknown value stops Philter from starting, rather than leaving it without
 * storage.
 */
@Configuration
public class StorageConfiguration {

    /** The property {@code STORAGE_TYPE} sets. */
    public static final String PROPERTY = "philter.storage";

    /** {@code STORAGE_TYPE} for MongoDB, which several Philter instances can share. */
    public static final String MONGODB = "mongodb";

    /** {@code STORAGE_TYPE} for SQLite, for a single Philter instance: the default. */
    public static final String SQLITE = "sqlite";

    /** The storage implementations Philter has. */
    public static final List<String> TYPES = List.of(MONGODB, SQLITE);

    public StorageConfiguration(final Environment environment) {
        requireKnown(environment.getProperty(PROPERTY, SQLITE));
        requireChosen(environment.containsProperty("STORAGE_TYPE"), environment.containsProperty("MONGODB_CONNECTION_STRING"));
    }

    /**
     * Refuses to start when a MongoDB connection is configured but no storage is chosen. Such a deployment most
     * likely means MongoDB, as Philter 3 and earlier always did; starting it on the SQLite default would put it
     * on a new, empty database.
     */
    static void requireChosen(final boolean storageTypeSet, final boolean mongoDbConnectionSet) {
        if (!storageTypeSet && mongoDbConnectionSet) {
            throw new IllegalStateException("MONGODB_CONNECTION_STRING is set but STORAGE_TYPE is not. Philter "
                    + "stores its data in SQLite unless STORAGE_TYPE says otherwise; set STORAGE_TYPE=mongodb to use "
                    + "MongoDB, or STORAGE_TYPE=sqlite and remove MONGODB_CONNECTION_STRING to use SQLite.");
        }
    }

    /** Refuses a {@code STORAGE_TYPE} Philter has no implementation for. Case does not matter. */
    static void requireKnown(final String type) {
        if (type == null || !TYPES.contains(type.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("STORAGE_TYPE must be one of " + String.join(", ", TYPES) + "; it is '"
                    + type + "'.");
        }
    }

}
