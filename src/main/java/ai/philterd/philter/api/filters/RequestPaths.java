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
package ai.philterd.philter.api.filters;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * The path a request is routed by, for deciding how a filter or interceptor treats it. Spring MVC routes on the
 * decoded path, so a decision made on the raw request URI can be avoided by percent-encoding a character:
 * {@code /%61pi/documents} is routed to {@code /api/documents} but does not start with {@code /api/}. Every
 * access decision uses this path instead.
 */
public final class RequestPaths {

    private static final UrlPathHelper DECODED = new UrlPathHelper();

    static {
        DECODED.setUrlDecode(true);
        DECODED.setRemoveSemicolonContent(true);
    }

    private RequestPaths() {
    }

    /**
     * The decoded path of the request within the application, without the context path or query string.
     * @param request The request.
     * @return The path, such as {@code /api/documents}.
     */
    public static String of(final HttpServletRequest request) {
        return DECODED.getPathWithinApplication(request);
    }

}
