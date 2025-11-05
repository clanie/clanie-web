/**
 * Copyright (C) 2025, Claus Nielsen, clausn999@gmail.com
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package dk.clanie.web;

import static dk.clanie.core.Utils.opt;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.FOUND;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

import java.util.function.Consumer;

import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.lang.Nullable;
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
import dk.clanie.web.exception.TooManyRequestsException;
import dk.clanie.web.exception.UnauthorizedException;
import dk.clanie.web.exception.UnprocessableEntityException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@RequiredArgsConstructor
public class WebClientFactory {

	private final WebClient.Builder webClientBuilder;


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
		if (wiretap) {
			LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
			loggerContext.getLogger("reactor.netty.http.client").setLevel(Level.TRACE);
		}
		HttpClient httpClient = HttpClient.create()
				.followRedirect(false)
				.wiretap(wiretap);
		return webClientBuilder.clone()
				.clientConnector(new ReactorClientHttpConnector(httpClient))
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
					else if (TOO_MANY_REQUESTS.equals(statusCode)) ex = new TooManyRequestsException("Too Many Requests");
					else if (statusCode.is4xxClientError()) ex = new BadRequestException("Client Error " + cr.statusCode() + ": "+ statusCode);
					else if (INTERNAL_SERVER_ERROR.equals(statusCode)) ex = new InternalServerErrorException("Internal Server Error");
					else ex = new InternalServerErrorException("Server Error " + cr.statusCode() + ": "+ cr.statusCode());
					return Mono.error(ex);
				});
	}


}