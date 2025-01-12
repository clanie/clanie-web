package dk.clanie.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dk.clanie.web.exception.FoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {


	@ExceptionHandler(FoundException.class)
	public ResponseEntity<?> handleFoundException(FoundException e) {
		return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, e.getLocation())
				.build();
	}


}
