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

import ai.philterd.philter.data.schema.Migration;

/**
 * Version 3: a queued document's job holds {@code leftover_check_requested}, {@code leftovers}, and
 * {@code email_report}. A job without them did not ask for the check and has nothing to report, so nothing is
 * changed; the version keeps MongoDB storage at the same schema version as SQLite storage.
 */
final class MongoLeftoverReportMigration implements Migration {

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "Pending documents hold leftover_check_requested, leftovers, and email_report";
    }

    @Override
    public void apply() {
    }

}
