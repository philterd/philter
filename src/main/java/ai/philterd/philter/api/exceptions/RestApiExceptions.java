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

import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.services.policies.PolicyNotFoundException;
import ai.philterd.philter.services.policies.PolicyResolutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@ControllerAdvice
public class RestApiExceptions {

	private static final Logger LOGGER = LogManager.getLogger(RestApiExceptions.class);

	private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

	/**
	 * Writes an error as {@code {"message": ...}}, the same shape as every other error Philter returns, so a
	 * client can read the message the same way whichever endpoint or handler refused it. Written to the
	 * response directly rather than returned, so it is sent whatever the request's {@code Accept} header
	 * asked for: content negotiation would otherwise refuse a JSON error to a caller that accepts only
	 * text/plain or a PDF.
	 */
	private static void write(final HttpServletResponse response, final HttpStatus status, final String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(GSON.toJson(new GenericResponse(message)));
	}

	// Each handler sets its status itself. @ResponseStatus states the same status so the generated OpenAPI
	// specification lists the response, which ApiDocumentationConfig then describes, and @ApiResponse
	// documents the JSON body the handler writes.

	/** The message describes what the caller sent and is written for the caller to read. */
	@ExceptionHandler(BadRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleBadRequestException(final BadRequestException ex, final HttpServletResponse response)
			throws IOException {
		LOGGER.error("Bad request: {}", ex.getMessage());
		write(response, HttpStatus.BAD_REQUEST, ex.getMessage() == null || ex.getMessage().isBlank()
				? "A required parameter is missing or contains an invalid value."
				: ex.getMessage());
	}

	/**
	 * Not thrown by Philter, so the message is a filesystem path or a parser trace rather than
	 * anything the caller wrote. A fixed message is returned, and only the exception type is logged.
	 */
	@ExceptionHandler({FileNotFoundException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnreadableRequest(final Exception ex, final HttpServletResponse response) throws IOException {
		// Only the type: a parser's message can quote the request body, such as a password.
		LOGGER.warn("The request could not be read: {}", ex.getClass().getSimpleName());
		write(response, HttpStatus.BAD_REQUEST, "The request body is missing or could not be read.");
	}

	@ExceptionHandler(ai.philterd.philter.data.services.QueueCapacityException.class)
	public void handleQueueCapacity(final ai.philterd.philter.data.services.QueueCapacityException ex,
	                                final HttpServletResponse response) throws IOException {
		response.setHeader("Retry-After", "5");
		write(response, HttpStatus.valueOf(ex.getStatus()), ex.getMessage());
	}

	@ExceptionHandler(PolicyResolutionException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handlePolicyResolutionException(final PolicyResolutionException ex, final HttpServletResponse response)
			throws IOException {
		LOGGER.error("Unable to resolve redaction policy.", ex);
		write(response, HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(PolicyNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	@ApiResponse(responseCode = "404", description = "Not Found", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handlePolicyNotFoundException(final Exception ex, final HttpServletResponse response) throws IOException {
		LOGGER.error("The named policy does not exist.", ex);
		write(response, HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(UnsupportedMediaTypeException.class)
	@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	@ApiResponse(responseCode = "415", description = "Unsupported Media Type", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnsupportedMediaTypeException(final UnsupportedMediaTypeException ex,
	                                                final HttpServletResponse response) throws IOException {
		// The body contradicts the declared type. Refusing beats redacting a document that was
		// never parsed and returning a clean-looking response.
		write(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
	}

	@ExceptionHandler(PayloadTooLargeException.class)
	@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
	@ApiResponse(responseCode = "413", description = "Content Too Large", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handlePayloadTooLargeException(final PayloadTooLargeException ex, final HttpServletResponse response)
			throws IOException {
		// The caller sent too much data, which is a client error. Without this it reached the
		// catch-all below and came back as a 500 that named nothing.
		write(response, HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleMissingRequestParameterException(final MissingServletRequestParameterException ex,
	                                                   final HttpServletResponse response) throws IOException {
		// Spring throws this before the handler method runs, so nothing has been read or written.
		// It is a client error, so it is reported as 400 naming the parameter rather than falling
		// through to the catch-all below, which would report a 500 and say nothing useful.
		write(response, HttpStatus.BAD_REQUEST, "The required parameter '" + ex.getParameterName() + "' is missing.");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	@ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleParameterTypeMismatchException(final MethodArgumentTypeMismatchException ex,
	                                                 final HttpServletResponse response) throws IOException {
		// The parameter name is declared in the controller; the submitted value is not echoed back.
		write(response, HttpStatus.BAD_REQUEST, "The parameter '" + ex.getName() + "' has an invalid value.");
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	@ApiResponse(responseCode = "503", description = "Service Unavailable", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleServiceUnavailableException(final ServiceUnavailableException ex,
	                                              final HttpServletResponse response) throws IOException {
		LOGGER.error("Unable to determine model service status - indicates service initialization or failure if status persists.", ex);
		write(response, HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler(UnauthorizedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnauthorizedException(final UnauthorizedException ex, final HttpServletResponse response)
			throws IOException {
		LOGGER.error("Unauthorized access.", ex);
		write(response, HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	@ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleMissingRequestHeaderException(final MissingRequestHeaderException ex,
	                                                final HttpServletResponse response) throws IOException {
		if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(ex.getHeaderName())) {
			write(response, HttpStatus.UNAUTHORIZED, "Unauthorized.");
			return;
		}
		LOGGER.error("A required header is missing: {}", ex.getHeaderName(), ex);
		write(response, HttpStatus.UNAUTHORIZED, "A required header is missing.");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	@ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
	@ApiResponse(responseCode = "405", description = "Method Not Allowed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleMethodNotSupportedException(final HttpRequestMethodNotSupportedException ex,
	                                              final HttpServletResponse response) throws IOException {
		// A client used an HTTP method this endpoint does not support (for example, a method that was
		// intentionally removed). This is a client error, so it is reported as 405, not a 500.
		write(response, HttpStatus.METHOD_NOT_ALLOWED, "The requested HTTP method is not supported for this endpoint.");
	}

	@ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
	@ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
	@ApiResponse(responseCode = "406", description = "Not Acceptable", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnacceptableMediaType(final Exception ex, final HttpServletResponse response) throws IOException {
		write(response, HttpStatus.NOT_ACCEPTABLE, "No representation matches the requested Accept header.");
	}

	@ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
	@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	@ApiResponse(responseCode = "415", description = "Unsupported Media Type", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnsupportedRequestMediaType(final Exception ex, final HttpServletResponse response)
			throws IOException {
		write(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request Content-Type is not supported for this endpoint.");
	}

	@ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
			org.springframework.web.servlet.NoHandlerFoundException.class})
	@ResponseStatus(HttpStatus.NOT_FOUND)
	@ApiResponse(responseCode = "404", description = "Not Found", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleNotFound(final Exception ex, final HttpServletResponse response) throws IOException {
		// No endpoint or static resource at this path. Without this, the catch-all below made it a 500.
		write(response, HttpStatus.NOT_FOUND, "Not found.");
	}

	@ExceptionHandler({IOException.class, Exception.class})
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	@ApiResponse(responseCode = "500", description = "Internal Server Error", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
			schema = @Schema(implementation = GenericResponse.class)))
	public void handleUnknownException(final Exception ex, final HttpServletResponse response) throws IOException {
		LOGGER.error("An unknown error has occurred.", ex);
		write(response, HttpStatus.INTERNAL_SERVER_ERROR, "An unknown error has occurred.");
	}

}
