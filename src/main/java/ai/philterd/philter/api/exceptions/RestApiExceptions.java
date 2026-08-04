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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.FileNotFoundException;
import java.io.IOException;

@ControllerAdvice
public class RestApiExceptions {

	private static final Logger LOGGER = LogManager.getLogger(RestApiExceptions.class);

	@ResponseBody
	@ExceptionHandler({BadRequestException.class, FileNotFoundException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	public String handleBadRequestException(Exception ex) {
		final String message = "A required parameter is missing or contains an invalid value.";
		LOGGER.error(message, ex);
		return message;
	}

	@ResponseBody
	@ExceptionHandler(UnsupportedMediaTypeException.class)
	@ResponseStatus(value = HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	public String handleUnsupportedMediaTypeException(final UnsupportedMediaTypeException ex) {
		// The body contradicts the declared type. Refusing beats redacting a document that was
		// never parsed and returning a clean-looking response.
		return ex.getMessage();
	}

	@ResponseBody
	@ExceptionHandler(PayloadTooLargeException.class)
	@ResponseStatus(value = HttpStatus.PAYLOAD_TOO_LARGE)
	public String handlePayloadTooLargeException(final PayloadTooLargeException ex) {
		// The caller sent too much data, which is a client error. Without this it reached the
		// catch-all below and came back as a 500 that named nothing.
		return ex.getMessage();
	}

	@ResponseBody
	@ExceptionHandler(MissingServletRequestParameterException.class)
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	public String handleMissingRequestParameterException(final MissingServletRequestParameterException ex) {
		// Spring throws this before the handler method runs, so nothing has been read or written.
		// It is a client error, so it is reported as 400 naming the parameter rather than falling
		// through to the catch-all below, which would report a 500 and say nothing useful.
		return "The required parameter '" + ex.getParameterName() + "' is missing.";
	}

	@ResponseBody
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	public String handleParameterTypeMismatchException(final MethodArgumentTypeMismatchException ex) {
		// The parameter name is declared in the controller; the submitted value is not echoed back.
		return "The parameter '" + ex.getName() + "' has an invalid value.";
	}

	@ResponseBody
	@ExceptionHandler(ServiceUnavailableException.class)
	@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
	public String handleServiceUnavailableException(ServiceUnavailableException ex) {
		LOGGER.error("Unable to determine model service status - indicates service initialization or failure if status persists.", ex);
	    return ex.getMessage();
	}

	@ResponseBody
	@ExceptionHandler(UnauthorizedException.class)
	@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
	public String handleUnauthorizedException(UnauthorizedException ex) {
		LOGGER.error("Unauthorized access.", ex);
		return ex.getMessage();
	}

	@ResponseBody
	@ExceptionHandler(MissingRequestHeaderException.class)
	@ResponseStatus(value = HttpStatus.UNAUTHORIZED)
	public String handleMissingRequestHeaderException(MissingRequestHeaderException ex) {
		if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(ex.getHeaderName())) {
			return "Unauthorized.";
		}
		LOGGER.error("A required header is missing: {}", ex.getHeaderName(), ex);
		return "A required header is missing.";
	}

	@ResponseBody
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	@ResponseStatus(value = HttpStatus.METHOD_NOT_ALLOWED)
	public String handleMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
		// A client used an HTTP method this endpoint does not support (for example, a method that was
		// intentionally removed). This is a client error, so it is reported as 405, not a 500.
		return "The requested HTTP method is not supported for this endpoint.";
	}

	@ResponseBody
	@ExceptionHandler({IOException.class, Exception.class})
	@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
	public String handleUnknownException(Exception ex) {
		LOGGER.error("An unknown error has occurred.", ex);
	    return "An unknown error has occurred.";
	}
	
}
