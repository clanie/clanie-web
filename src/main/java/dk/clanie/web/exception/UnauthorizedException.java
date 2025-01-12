package dk.clanie.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import lombok.experimental.StandardException;

@StandardException
@ResponseStatus(HttpStatus.UNAUTHORIZED)
@SuppressWarnings("serial")
public class UnauthorizedException extends RuntimeException {

}
