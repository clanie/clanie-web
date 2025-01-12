package dk.clanie.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import lombok.Getter;

@ResponseStatus(HttpStatus.FOUND)
@SuppressWarnings("serial")
public class FoundException extends RuntimeException {

	/**
	 * Value of the Location header in the response.
	 */
	@Getter
	private String location;

	public FoundException(String location) {
		super();
		this.location = location;
	}

}
