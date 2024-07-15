package ai.philterd.philter.api.exceptions;

import ai.philterd.phileas.model.exceptions.InvalidPolicyException;
import ai.philterd.phileas.model.exceptions.api.BadRequestException;
import ai.philterd.phileas.model.exceptions.api.ServiceUnavailableException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.FileNotFoundException;
import java.io.IOException;

@ControllerAdvice
public class RestApiExceptions {

	private static final Logger LOGGER = LogManager.getLogger(RestApiExceptions.class);

	@ResponseBody
	@ExceptionHandler({BadRequestException.class, FileNotFoundException.class, InvalidPolicyException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(value = HttpStatus.BAD_REQUEST)
	public String handleMissingParameterException(Exception ex) {
		final String message = "A required parameter is missing or contains an invalid value.";
		LOGGER.error(message, ex);
		return message;
	}

	@ResponseBody
	@ExceptionHandler(ServiceUnavailableException.class)
	@ResponseStatus(value = HttpStatus.SERVICE_UNAVAILABLE)
	public String handleServiceUnavailableException(ServiceUnavailableException ex) {
		LOGGER.error("Unable to determine model service status - indicates service initialization or failure if status persists.", ex);
	    return ex.getMessage();
	}

	@ResponseBody
	@ExceptionHandler({IOException.class, Exception.class})
	@ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
	public String handleUnknownException(Exception ex) {
		LOGGER.error("An unknown error has occurred.", ex);
	    return "An unknown error has occurred.";
	}
	
}
