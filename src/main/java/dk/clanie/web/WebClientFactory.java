/*
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
import static org.springframework.http.HttpStatus.FOUND;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import dk.clanie.web.exception.FoundException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@RequiredArgsConstructor
public class WebClientFactory {

	static final String WIRETAP_LOGGER_NAME = "reactor.netty.http.client";

	private static final byte[] EMPTY_BODY = new byte[0];

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
			loggerContext.getLogger(WIRETAP_LOGGER_NAME).setLevel(Level.TRACE);
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
					// A redirect carries its destination in a header, not a body.
					if (FOUND.equals(statusCode)) {
						return Mono.error(new FoundException(cr.headers().header(HttpHeaders.LOCATION).get(0)));
					}
					// The response is an error, so nothing else will read the body. Draining
					// it here puts the server's explanation - the API's own error code and
					// message - into the exception, instead of just the bare status name.
					// Read it as bytes, not as a String: a gzipped error body decoded as
					// text is mojibake, and only the bytes still carry the explanation.
					return cr.bodyToMono(byte[].class)
							.defaultIfEmpty(EMPTY_BODY)
							.onErrorReturn(EMPTY_BODY)
							.map(body -> HttpErrorMapping.toException(statusCode, body))
							.flatMap(Mono::error);
				});
	}


}