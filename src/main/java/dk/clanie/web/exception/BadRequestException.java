package dk.clanie.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import lombok.experimental.StandardException;

@StandardException
@ResponseStatus(HttpStatus.BAD_REQUEST)
@SuppressWarnings("serial")
public class BadRequestException extends RuntimeException {

}
