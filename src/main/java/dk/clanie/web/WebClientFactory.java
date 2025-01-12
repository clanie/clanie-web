package dk.clanie.web;

import static dk.clanie.core.Utils.opt;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.FOUND;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

import java.util.function.Consumer;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import dk.clanie.web.exception.BadRequestException;
import dk.clanie.web.exception.ConflictException;
import dk.clanie.web.exception.ForbiddenException;
import dk.clanie.web.exception.FoundException;
import dk.clanie.web.exception.InternalServerErrorException;
import dk.clanie.web.exception.NotFoundException;
import dk.clanie.web.exception.UnauthorizedException;
import dk.clanie.web.exception.UnprocessableEntityException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Service
public class WebClientFactory {

	@Autowired
	private WebClient.Builder webClientBuilder;


	/**
	 * Gets a WebClient with given baseUrl and the filters required
	 * for making calls between services.
	 * 
	 * @param baseUrl
	 * @param wiretap log all requests and responses.
	 */
	public WebClient newWebClient(String baseUrl, boolean wiretap) {
		return newWebClient(baseUrl, null, wiretap);
	}


	/**
	 * Gets a WebClientBuilder with given baseUrl and the filters required
	 * for making calls between services.
	 * 
	 * @param baseUrl
	 * @param builderConsumer can be provided to further customize the WebClient.
	 * @param wiretap log all requests and responses.
	 */
	public WebClient newWebClient(String baseUrl, @Nullable Consumer<WebClient.Builder> builderConsumer, boolean wiretap) {
		WebClient.Builder myWebClientBuilder = webClientBuilder.clone();
		
		if (wiretap) {
			LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
			loggerContext.getLogger("reactor.netty.http.client").setLevel(Level.TRACE);
			HttpClient httpClient = HttpClient.create().wiretap(true);
			myWebClientBuilder = myWebClientBuilder.clientConnector(new ReactorClientHttpConnector(httpClient));
		}

		return myWebClientBuilder
				.baseUrl(baseUrl)
				.filter(responseCodeToExceptionMappingFilter())
				.apply(opt(builderConsumer).orElse(_ -> {}))
				.build();
	}


	private static ExchangeFilterFunction responseCodeToExceptionMappingFilter() {
		return ExchangeFilterFunction.ofResponseProcessor(
				cr -> {
					HttpStatusCode statusCode = cr.statusCode();
					if (statusCode.is2xxSuccessful()) return Mono.just(cr);
					RuntimeException ex = null;
					if (FOUND.equals(statusCode)) ex = new FoundException(cr.headers().header(HttpHeaders.LOCATION).get(0));
					else if (BAD_REQUEST.equals(statusCode)) ex = new BadRequestException("Bad Request");
					else if (UNAUTHORIZED.equals(statusCode)) ex = new UnauthorizedException("Unauthorized");
					else if (FORBIDDEN.equals(statusCode)) ex = new ForbiddenException("Forbidden");
					else if (NOT_FOUND.equals(statusCode)) ex = new NotFoundException("Not Found");
					else if (CONFLICT.equals(statusCode)) ex = new ConflictException("Conflict");
					else if (UNPROCESSABLE_ENTITY.equals(statusCode)) ex = new UnprocessableEntityException("Unprocessable Entity");
					else if (statusCode.is4xxClientError()) ex = new BadRequestException("Client Error " + cr.statusCode() + ": "+ statusCode);
					else if (INTERNAL_SERVER_ERROR.equals(statusCode)) ex = new InternalServerErrorException("Internal Server Error");
					else ex = new InternalServerErrorException("Server Error " + cr.statusCode() + ": "+ cr.statusCode());
					return Mono.error(ex);
				});
	}

}
