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
package ai.philterd.philter.data.services;

import ai.philterd.philter.data.entities.AdminSettingsEntity;

import java.util.List;

/**
 * The deployment's administrator settings, one record for the whole deployment.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>There is at most one settings record, and each update changes only the settings it names.</li>
 *   <li>Secret settings are stored encrypted, and what is written is read back unchanged.</li>
 * </ul>
 */
public interface AdminSettingsDataService {

    AdminSettingsEntity findAdminSettings();

    /**
     * Settings to change. A null field is left as it is. For the Phield API key, {@code ""} removes it.
     */
    record Update(Boolean diffuseCountsEnabled, Boolean signingEnabled, String webhookAllowlist,
            Boolean phieldEnabled, String phieldUrl, String phieldSourceId,
            String phieldOrganization, String phieldApiKey,
            Boolean mfaAvailable, Boolean mfaRequired) {
    }

    /**
     * Validates and applies an {@link Update} from the API, after confirming the acting user is still an
     * active administrator. Nothing is written unless every value is valid. The change is audited as
     * {@code settings_updated}, naming the settings that changed and the acting API key, never the values.
     *
     * @return warnings about the saved settings, such as a Phield API key that will be sent over http.
     * @throws IllegalArgumentException with the reason, when a value is not valid.
     */
    List<String> update(final Update update, final String actingUserId, final String actingApiKeyId);

    /** As above, recording the change under {@code requestId}, the id of the request that made it. */
    List<String> update(final String requestId, final Update update, final String actingUserId,
            final String actingApiKeyId);

}
