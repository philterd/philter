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

import ai.philterd.philter.api.responses.GenericResponse;
import com.google.gson.Gson;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes Tomcat's own error responses as {@code {"message": ...}} JSON, the shape every other Philter error
 * has, instead of Tomcat's HTML page. These are errors Tomcat raises before a request reaches Spring, such as
 * a path with an encoded {@code /}, {@code \} or NUL, a malformed request line, or headers over its size limit.
 *
 * <p>Keeps {@link ErrorReportValve}'s guards: nothing is written below 400, after a body has been written,
 * for an error already reported, or when I/O is no longer possible. The message is fixed per status and never
 * echoes the request, so nothing from a rejected path is reflected back.
 */
public class JsonErrorReportValve extends ErrorReportValve {

    private static final Gson GSON = new Gson();

    @Override
    protected void report(final Request request, final Response response, final Throwable throwable) {

        final int statusCode = response.getStatus();
        if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }

        final AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }

        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            final Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(GSON.toJson(new GenericResponse(message(statusCode))));
                response.finishResponse();
            }
        } catch (final IOException | IllegalStateException e) {
            // The client has gone or the response was committed; there is nothing more to send.
        }

    }

    static String message(final int statusCode) {
        if (statusCode == HttpStatus.BAD_REQUEST.value()) {
            // Tomcat answers 400 both for a request line it cannot accept and for headers over its size limit.
            return "The request could not be processed: it is malformed, its headers are too large, or its path "
                    + "contains a character that is not allowed, such as an encoded /, \\ or NUL.";
        }
        final HttpStatus status = HttpStatus.resolve(statusCode);
        return status == null ? "The request could not be processed." : status.getReasonPhrase() + ".";
    }

}
