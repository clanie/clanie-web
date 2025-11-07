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
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

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
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class RestClientFactory {

	private final RestClient.Builder restClientBuilder;

	/**
	 * Gets a RestClient with given baseUrl and the filters required
	 * for making calls between services.
	 * 
	 * @param baseUrl
	 * @param wiretap log all requests and responses.
	 */
	public RestClient newRestClient(String baseUrl, boolean wiretap) {
		return newRestClient(baseUrl, null, wiretap);
	}


	/**
	 * Gets a RestClient with given baseUrl and the filters required
	 * for making calls between services.
	 * 
	 * @param baseUrl
	 * @param builderConsumer can be provided to further customize the RestClient.
	 * @param wiretap log all requests and responses.
	 */
	public RestClient newRestClient(String baseUrl, @Nullable Consumer<RestClient.Builder> builderConsumer, boolean wiretap) {
		if (wiretap) {
			LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
			loggerContext.getLogger(RestClientFactory.class).setLevel(Level.TRACE);
		}

		RestClient.Builder builder = restClientBuilder.clone()
				.baseUrl(baseUrl)
				.defaultStatusHandler(
						statusCode -> !statusCode.is2xxSuccessful(),
						(_, response) -> {
							HttpStatusCode statusCode = response.getStatusCode();
							RuntimeException ex = null;
							if (FOUND.equals(statusCode)) {
								String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
								ex = new FoundException(location != null ? location : "");
							}
							else if (BAD_REQUEST.equals(statusCode)) ex = new BadRequestException("Bad Request");
							else if (UNAUTHORIZED.equals(statusCode)) ex = new UnauthorizedException("Unauthorized");
							else if (FORBIDDEN.equals(statusCode)) ex = new ForbiddenException("Forbidden");
							else if (NOT_FOUND.equals(statusCode)) ex = new NotFoundException("Not Found");
							else if (CONFLICT.equals(statusCode)) ex = new ConflictException("Conflict");
							else if (UNPROCESSABLE_ENTITY.equals(statusCode)) ex = new UnprocessableEntityException("Unprocessable Entity");
							else if (TOO_MANY_REQUESTS.equals(statusCode)) ex = new TooManyRequestsException("Too Many Requests");
							else if (statusCode.is4xxClientError()) ex = new BadRequestException("Client Error " + statusCode.value() + ": " + statusCode);
							else if (INTERNAL_SERVER_ERROR.equals(statusCode)) ex = new InternalServerErrorException("Internal Server Error");
							else ex = new InternalServerErrorException("Server Error " + statusCode.value() + ": " + statusCode);
							throw ex;
						});

		if (wiretap) {
			builder.requestInterceptor(loggingInterceptor());
		}

		return builder
				.apply(opt(builderConsumer).orElse(_ -> {}))
				.build();
	}


	private static ClientHttpRequestInterceptor loggingInterceptor() {
		return (HttpRequest request, byte[] body, org.springframework.http.client.ClientHttpRequestExecution execution) -> {
			log.trace("Request: {} {}", request.getMethod(), request.getURI());
			if (body.length > 0) {
				log.trace("Request body: {}", new String(body));
			}

			ClientHttpResponse response = execution.execute(request, body);

			log.trace("Response: {} {}", response.getStatusCode().value(), response.getStatusText());

			return response;
		};
	}


}
