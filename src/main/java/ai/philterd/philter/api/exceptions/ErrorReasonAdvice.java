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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Gives every error body a handler returns a {@code reason}, the one its status implies, when the handler
 * did not give a more specific one. A handler that knows why it refused says so; this makes sure a client
 * can always read a reason, whichever handler answered.
 */
@ControllerAdvice
public class ErrorReasonAdvice implements ResponseBodyAdvice<Object> {

    /** Without HTML escaping, as the handlers write their messages. */
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    @Override
    public boolean supports(final MethodParameter returnType, final Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(final Object body, final MethodParameter returnType, final MediaType contentType,
                                  final Class<? extends HttpMessageConverter<?>> converterType,
                                  final ServerHttpRequest request, final ServerHttpResponse response) {

        if (!(response instanceof final ServletServerHttpResponse servlet)) {
            return body;
        }
        final int status = servlet.getServletResponse().getStatus();
        if (status < 400 || body == null) {
            return body;
        }

        if (body instanceof final GenericResponse error) {
            return error.getReason() != null ? error
                    : new GenericResponse(error.getMessage(), ErrorReasons.forStatus(status), error.getField());
        }

        // Some handlers write their JSON themselves; an error object among them gets the reason too.
        if (body instanceof final String text && text.startsWith("{")) {
            try {
                final JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) {
                    final JsonObject object = parsed.getAsJsonObject();
                    if (object.has("message") && !object.has("reason")) {
                        object.addProperty("reason", ErrorReasons.forStatus(status));
                        return GSON.toJson(object);
                    }
                }
            } catch (final RuntimeException notJson) {
                return body;
            }
        }

        return body;

    }

}
