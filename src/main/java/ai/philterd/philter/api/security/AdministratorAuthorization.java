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
package ai.philterd.philter.api.security;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.security.access.AccessDeniedException;

/**
 * Rechecks, against the stored account, that the user making a change is still an active, unlocked
 * administrator. The API's checks run when a request starts; this runs where the change is made, so
 * a user demoted or deactivated in between cannot complete it. Unlike {@link DashboardAuthorization},
 * it does not depend on the dashboard's session.
 */
public final class AdministratorAuthorization {

    private AdministratorAuthorization() {
    }

    public static void requireActiveAdministrator(final MongoClient client, final ObjectId actingUserId) {

        final Document user = actingUserId == null ? null
                : client.getDatabase("philter").getCollection("users").find(Filters.eq("_id", actingUserId)).first();

        if (user == null || !"admin".equalsIgnoreCase(user.getString("role"))
                || user.getBoolean("deactivated", false) || user.getBoolean("mfa_locked", false)) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }

    }

}
