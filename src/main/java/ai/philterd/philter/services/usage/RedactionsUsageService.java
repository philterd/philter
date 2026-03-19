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
package ai.philterd.philter.services.usage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

public interface RedactionsUsageService {

    Map<String, Long> getTokensPreviousXDays(int previousDays) throws IOException;

    Map<String, Long> getTokensPreviousCalendarMonth() throws IOException;

    Map<String, Long> getTokens(LocalDate startDate, LocalDate endDate) throws IOException;

    Map<String, Long> getRedactionsPreviousXDays(int previousDays) throws IOException;

    Map<String, Long> getRedactionsPreviousCalendarMonth() throws IOException;

    Map<String, Long> getRedactions(LocalDate startDate, LocalDate endDate) throws IOException;

}
