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
package ai.philterd.philter.api.exceptions;

import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.api.responses.GenericResponse;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes Philter's error shape, {@code {"message", "reason", "field"}}, straight to a response. Used by
 * the exception handlers and by the filters that refuse a request before any handler runs, so every
 * error under /api is written the same way.
 */
public final class ApiErrors {

    /** The message an unknown path is answered with, shared so no other 404 can be told apart from it. */
    public static final String NOT_FOUND_MESSAGE = "Not found.";

    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    private ApiErrors() {
    }

    /**
     * Writes the error. Written to the response directly rather than returned, so it is sent whatever the
     * request's {@code Accept} header asked for. A null reason is the one the status implies.
     */
    public static void write(final HttpServletResponse response, final int status, final String message,
                             final String reason, final String field) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(GSON.toJson(new GenericResponse(message,
                reason == null ? ErrorReasons.forStatus(status) : reason, field)));
    }

    public static void write(final HttpServletResponse response, final int status, final String message,
                             final String reason) throws IOException {
        write(response, status, message, reason, null);
    }

    /** The 404 for anything not found, the same body an unknown path gets. */
    public static void notFound(final HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_MESSAGE, ErrorReasons.NOT_FOUND);
    }

}
