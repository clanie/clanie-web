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

import java.io.IOException;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import dk.clanie.web.exception.FoundException;
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
							// A redirect carries its destination in a header, not a body.
							if (FOUND.equals(statusCode)) {
								String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
								throw new FoundException(location != null ? location : "");
							}
							// The response is an error, so nothing else will read the body.
							// Draining it here puts the server's own error code and message
							// into the exception, instead of just the bare status name.
							throw HttpErrorMapping.toException(statusCode, readBodyQuietly(response));
						});

		if (wiretap) {
			builder.requestInterceptor(loggingInterceptor());
		}

		return builder
				.apply(opt(builderConsumer).orElse(_ -> {}))
				.build();
	}


	/**
	 * The error response's body bytes, or {@code null} if they cannot be read. A body we
	 * failed to read must not replace the status the server actually sent, so read errors
	 * are swallowed. The bytes are handed on undecoded because an error body may be
	 * gzipped - {@link HttpErrorMapping} is what turns them into text.
	 */
	private static byte @Nullable [] readBodyQuietly(ClientHttpResponse response) {
		try {
			return response.getBody().readAllBytes();
		} catch (IOException e) {
			log.debug("Could not read error response body.", e);
			return null;
		}
	}


	private static ClientHttpRequestInterceptor loggingInterceptor() {
		return (HttpRequest request, byte[] body, org.springframework.http.client.ClientHttpRequestExecution execution) -> {
			log.trace("Request: {} {}", request.getMethod(), request.getURI());
			request.getHeaders().forEach((name, values) -> log.trace("Request header: {}={}", name, values));
			if (body.length > 0) {
				log.trace("Request body: {}", new String(body));
			}
			ClientHttpResponse response = execution.execute(request, body);
			log.trace("Response: {} {}", response.getStatusCode().value(), response.getStatusText());
			return response;
		};
	}


}
