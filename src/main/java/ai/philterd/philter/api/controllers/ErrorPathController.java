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
package ai.philterd.philter.api.controllers;

import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.api.responses.GenericResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers errors forwarded to {@code /error} with {@code {"message": ...}} JSON, the shape every other Philter
 * error has, in place of Spring Boot's default body. These are errors raised outside a controller. A request
 * Spring Security's firewall refuses for its path is answered by the {@code RequestRejectedHandler} in
 * {@code SecurityConfig} instead. The message never echoes the request.
 */
@Hidden
@RestController
public class ErrorPathController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<GenericResponse> error(final HttpServletRequest request) {

        final Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        final int statusCode = code instanceof Integer value ? value : HttpStatus.INTERNAL_SERVER_ERROR.value();

        final HttpStatus status = HttpStatus.resolve(statusCode);
        final String message = status == null ? "The request could not be processed." : status.getReasonPhrase() + ".";

        // The content type is set rather than negotiated, so the body is sent whatever Accept asked for.
        return ResponseEntity.status(statusCode).contentType(MediaType.APPLICATION_JSON)
                .body(new GenericResponse(message, ErrorReasons.forStatus(statusCode)));

    }

}
