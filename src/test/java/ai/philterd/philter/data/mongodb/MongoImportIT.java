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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.sqlite.SqliteExport;
import ai.philterd.philter.data.sqlite.SqliteStorageUnderTest;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

/** How {@link MongoImport} turns SQLite rows into MongoDB documents. */
class MongoImportIT extends AbstractMongoIT {

    @TempDir
    Path directory;

    /**
     * SQLite columns no MongoDB document has, each for a reason: the order rows were written in, which arrives as the
     * order rows come in; a user's creation time, which MongoDB storage reads from the user's id; and the single
     * settings row's fixed key.
     */
    private static final Set<String> NOT_COPIED = Set.of("users.created_at", "admin_settings.id");

    /** The columns the PII counts are folded into their aggregates from. */
    private static final Set<String> PII_COUNT_COLUMNS = Set.of("context", "bucket_start", "pii_type", "count");

    /** A row that records which of its columns are read. */
    private static final class ReadColumns extends HashMap<String, Object> {

        private final Set<String> read = new TreeSet<>();

        @Override
        public Object get(final Object key) {
            read.add((String) key);
            return super.get(key);
        }
    }

    @Test
    @DisplayName("Every column of every copied SQLite table is copied, or left out for a stated reason")
    void everyColumnIsCopied() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        SqliteStorageUnderTest.create(path, new TestEncryptionService()).close();

        final Map<String, Set<String>> notRead = new TreeMap<>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            for (final String table : SqliteExport.TABLES) {
                final Map<String, String> columns = new LinkedHashMap<>();
                try (var rows = connection.createStatement().executeQuery("SELECT name, type FROM pragma_table_info('"
                        + table + "')")) {
                    while (rows.next()) {
                        columns.put(rows.getString("name"), rows.getString("type"));
                    }
                }
                final Set<String> read;
                if ("pii_counts".equals(table)) {
                    read = PII_COUNT_COLUMNS;
                } else {
                    // A value of each column's type that every converter accepts; text is a valid record id.
                    final ReadColumns row = new ReadColumns();
                    columns.forEach((column, type) -> row.put(column, switch (type) {
                        case "INTEGER" -> 1L;
                        case "REAL" -> 1.5;
                        case "BLOB" -> new byte[]{1};
                        default -> "0123456789abcdef01234567";
                    }));
                    assertNotNull(MongoImport.documentFor(table, row), "no converter for " + table);
                    read = row.read;
                }
                for (final String column : columns.keySet()) {
                    if (!read.contains(column) && !column.equals("seq") && !NOT_COPIED.contains(table + "." + column)) {
                        notRead.computeIfAbsent(table, t -> new TreeSet<>()).add(column);
                    }
                }
            }
        }
        assertEquals(Map.of(), notRead, "these SQLite columns are not copied to MongoDB; copy them in MongoImport, or "
                + "list them in NOT_COPIED with the reason");
    }

    @Test
    @DisplayName("Large documents are inserted in batches of bounded size")
    void largeDocumentsAreInsertedInBoundedBatches() {
        final MongoClient client = mock(MongoClient.class, delegatesTo(mongoClient));
        final MongoDatabase database = spy(mongoClient.getDatabase("philter"));
        doReturn(database).when(client).getDatabase("philter");
        final MongoCollection<Document> jobs = spy(database.getCollection("pending_documents"));
        doReturn(jobs).when(database).getCollection("pending_documents");

        // Five documents of 6 MB each: about 30 MB, which must not be sent, or held, at once.
        final int size = 6 * 1024 * 1024;
        final long copied = new MongoImport(client, new TestEncryptionService()).copy("pending_documents", rows -> {
            for (int i = 0; i < 5; i++) {
                final Map<String, Object> row = new HashMap<>();
                row.put("id", String.format("%024x", i + 1));
                row.put("user_id", "0123456789abcdef01234567");
                row.put("status", "COMPLETE");
                row.put("input", new byte[size]);
                row.put("input_encrypted_key", "key");
                rows.accept(row);
            }
        });

        assertEquals(5, copied);
        assertEquals(5, mongoClient.getDatabase("philter").getCollection("pending_documents").countDocuments());
        // The inserts the import made (the driver's one-argument insertMany calls its two-argument one). A batch is
        // sent once it reaches 16 MB, so three 6 MB documents go together, then the last two.
        final List<Integer> batches = mockingDetails(jobs).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("insertMany")
                        && invocation.getArguments().length == 1)
                .map(invocation -> ((List<?>) invocation.getArgument(0)).size()).toList();
        assertEquals(List.of(3, 2), batches);
    }

}
