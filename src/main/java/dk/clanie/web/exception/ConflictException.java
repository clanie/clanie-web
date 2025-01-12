package dk.clanie.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import lombok.experimental.StandardException;

@StandardException
@ResponseStatus(HttpStatus.CONFLICT)
@SuppressWarnings("serial")
public class ConflictException extends RuntimeException {

}
