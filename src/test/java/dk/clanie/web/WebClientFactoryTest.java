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

import static dk.clanie.web.WebClientFactory.WIRETAP_LOGGER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dk.clanie.test.logging.CapturedLoggingEvents;
import dk.clanie.test.logging.LogCapturer;
import dk.clanie.web.exception.BadRequestException;
import dk.clanie.web.exception.ConflictException;
import dk.clanie.web.exception.ForbiddenException;
import dk.clanie.web.exception.FoundException;
import dk.clanie.web.exception.InternalServerErrorException;
import dk.clanie.web.exception.NotFoundException;
import dk.clanie.web.exception.TooManyRequestsException;
import dk.clanie.web.exception.UnauthorizedException;
import dk.clanie.web.exception.UnprocessableContentException;
import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Unit tests for {@link WebClientFactory}.
 *
 * Tests use a lightweight Reactor Netty HTTP server started on a random port
 * and verify that responses are mapped to the correct exceptions by the
 * response filter installed by {@link WebClientFactory}.
 */
public class WebClientFactoryTest {

	private static final String ERROR_BODY = "{\n  \"ErrorCode\": \"NoMarketDataAccess\",\n  \"Message\": \"Not entitled\"\n}";
	private static final String CORRELATION_ID = "e3b0c442-98fc-1c14-9afb-f4c8996fb924";

	private DisposableServer server;
	private String baseUrl;
	private WebClientFactory clientFactory;

	@BeforeEach
	void setUp() {
		// Start a server that echo the status codes given on the path.
		// Expected path is /status/{code} - anything else gives an 404 (Not Found) response.
		server = HttpServer.create()
				.port(0)
				.handle((request, response) -> {
					String uri = request.uri();
					// An error body longer than what the exception message may carry.
					if ("/long-error-body".equals(uri)) {
						response.status(400);
						return response.sendString(Mono.just("x".repeat(HttpErrorMapping.MAX_BODY_IN_EXCEPTION_MESSAGE * 3)));
					}
					// An error carrying the server's own id for the failed request, as
					// Saxo's gateway does - that id is what their support desk asks for.
					if ("/correlated-error".equals(uri)) {
						response.status(403);
						response.header(HttpErrorMapping.CORRELATION_HEADER, CORRELATION_ID);
						return response.sendString(Mono.just(ERROR_BODY));
					}
					// The same, behind an error body too long to fit in the message.
					if ("/correlated-long-error".equals(uri)) {
						response.status(403);
						response.header(HttpErrorMapping.CORRELATION_HEADER, CORRELATION_ID);
						return response.sendString(Mono.just("x".repeat(HttpErrorMapping.MAX_BODY_IN_EXCEPTION_MESSAGE * 3)));
					}
					// An error body the server gzipped without being asked to, as Saxo's
					// gateway does.
					if ("/gzipped-error-body".equals(uri)) {
						response.status(403);
						return response.sendByteArray(Mono.just(gzip(ERROR_BODY)));
					}
					// The same body, this time with the encoding declared as the RFC asks.
					if ("/declared-gzipped-error-body".equals(uri)) {
						response.status(403);
						response.header(HttpHeaderNames.CONTENT_ENCODING.toString(), "gzip");
						return response.sendByteArray(Mono.just(gzip(ERROR_BODY)));
					}
					// Error responses carrying an explanation, like a real API's error payload.
					if (uri != null && uri.startsWith("/status-with-body/")) {
						response.status(Integer.parseInt(uri.substring("/status-with-body/".length())));
						return response.sendString(Mono.just(ERROR_BODY));
					}
					if (uri != null && uri.startsWith("/status/")) {
						String codeStr = uri.substring("/status/".length());
						int code;
						try {
							code = Integer.parseInt(codeStr);
							response.status(code);
							if (code == 200) {
								return response.sendString(Mono.just("hello"));
							}
							if (code == 302) {
								response.header(HttpHeaderNames.LOCATION.toString(), "http://example.com/redirect");
							} 
							return response.sendString(Mono.empty());
						} catch (NumberFormatException e) {
							// Fall through to 404 response
						}
					}
					response.status(404);
					return response.sendString(Mono.empty());
				})
				.bindNow();
		baseUrl = "http://localhost:" + server.port();
		clientFactory = new WebClientFactory(WebClient.builder());
	}

	@AfterEach
	void tearDown() {
		if (server != null) server.disposeNow();
	}

	@Test
	void testSuccessfulResponse() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		String body = client.get().uri("/status/200").retrieve().bodyToMono(String.class).block();
		assertThat(body).isEqualTo("hello");
	}

	@Test
	void testFoundResponse() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(FoundException.class, () ->
		client.get().uri("/status/302").retrieve().bodyToMono(String.class).block());
		assertThat(((FoundException) ex).getLocation()).isEqualTo("http://example.com/redirect");
	}

	@ParameterizedTest(name = "status {0} -> {1}")
	@MethodSource("testResponseCodeMappingArguments")
	void testResponseCodeMapping(int statusCode, Class<? extends Throwable> expectedException) {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		String uri = "/status/" + statusCode;
		// all parameterized cases are expected to throw an exception
		assertThrows(expectedException, () ->
		client.get().uri(uri).retrieve().bodyToMono(String.class).block());
	}

	@Test
	void errorResponseBodyIsCarriedIntoTheExceptionMessage() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/status-with-body/403").retrieve().bodyToMono(String.class).block());
		// The status name stays the prefix; the server's own explanation follows it,
		// collapsed onto one line.
		assertThat(ex.getMessage())
		.startsWith("Forbidden: ")
		.contains("NoMarketDataAccess")
		.contains("Not entitled")
		.doesNotContain("\n");
	}

	@Test
	void gzippedErrorResponseBodyIsDecompressedIntoTheExceptionMessage() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/gzipped-error-body").retrieve().bodyToMono(String.class).block());
		// Without decompression this reads as mojibake and the explanation is lost.
		assertThat(ex.getMessage())
		.startsWith("Forbidden: ")
		.contains("NoMarketDataAccess")
		.contains("Not entitled");
	}

	@Test
	void errorResponseBodyThatDeclaresItsGzipEncodingIsAlsoReadable() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/declared-gzipped-error-body").retrieve().bodyToMono(String.class).block());
		assertThat(ex.getMessage())
		.startsWith("Forbidden: ")
		.contains("NoMarketDataAccess");
	}

	@Test
	void correlationIdIsCarriedIntoTheExceptionMessage() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/correlated-error").retrieve().bodyToMono(String.class).block());
		// Without it, a report of a server-side fault cannot be traced back to the
		// request that hit it.
		assertThat(ex.getMessage())
		.contains("NoMarketDataAccess")
		.endsWith("[" + HttpErrorMapping.CORRELATION_HEADER + ": " + CORRELATION_ID + "]");
	}

	@Test
	void correlationIdSurvivesAnErrorBodyTooLongToFitInTheMessage() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/correlated-long-error").retrieve().bodyToMono(String.class).block());
		// The body is truncated first, so a long error page cannot push the id out.
		assertThat(ex.getMessage())
		.endsWith("[" + HttpErrorMapping.CORRELATION_HEADER + ": " + CORRELATION_ID + "]");
	}

	@Test
	void errorResponseWithoutBodyKeepsTheBareStatusName() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(ForbiddenException.class, () ->
		client.get().uri("/status/403").retrieve().bodyToMono(String.class).block());
		assertThat(ex.getMessage()).isEqualTo("Forbidden");
	}

	@Test
	void longErrorResponseBodyIsTruncated() {
		WebClient client = clientFactory.newWebClient(baseUrl, false);
		Throwable ex = assertThrows(BadRequestException.class, () ->
		client.get().uri("/long-error-body").retrieve().bodyToMono(String.class).block());
		assertThat(ex.getMessage())
		.hasSizeLessThan(HttpErrorMapping.MAX_BODY_IN_EXCEPTION_MESSAGE + 50)
		.endsWith("…");
	}

	private static byte[] gzip(String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
			gzip.write(text.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out.toByteArray();
	}


	static Stream<Arguments> testResponseCodeMappingArguments() {
		return Stream.of(
				// status, expected exception
				Arguments.of(400, BadRequestException.class),
				Arguments.of(401, UnauthorizedException.class),
				Arguments.of(403, ForbiddenException.class),
				Arguments.of(404, NotFoundException.class),
				Arguments.of(409, ConflictException.class),
				Arguments.of(422, UnprocessableContentException.class),
				Arguments.of(429, TooManyRequestsException.class),

				// Other 4xx -> BadRequestException by mapping
				Arguments.of(418, BadRequestException.class),

				Arguments.of(500, InternalServerErrorException.class),
				Arguments.of(503, InternalServerErrorException.class)
				);
	}

	@Test
	void testWiretapLogsRequests() {
		// Create client with wiretap enabled
		WebClient client = clientFactory.newWebClient(baseUrl, true);

		// Capture log events while making a request
		CapturedLoggingEvents captured = LogCapturer.capture(WIRETAP_LOGGER_NAME, () -> {
			String body = client.get().uri("/status/200").retrieve().bodyToMono(String.class).block();
			assertThat(body).isEqualTo("hello");
		});

		// Verify that log events were captured
		List<ILoggingEvent> logsList = captured.getEvents();
		assertThat(logsList).as("Expected log events when wiretap is enabled").isNotEmpty();

		// Verify request was logged
		assertThat(logsList).as("Expected to find request log with 'GET /status/200'")
		.anyMatch(event -> event.getLevel() == Level.DEBUG 
		&& event.getFormattedMessage().contains("GET /status/200"));

		// Verify response was logged
		assertThat(logsList).as("Expected to find response log with 'HTTP/1.1 200 OK'")
		.anyMatch(event -> event.getLevel() == Level.DEBUG 
		&& event.getFormattedMessage().contains("HTTP/1.1 200 OK"));
	}

	@Test
	void testNoWiretapDoesNotLog() {
		// Create client with wiretap disabled
		WebClient client = clientFactory.newWebClient(baseUrl, false);

		// Capture log events while making a request
		CapturedLoggingEvents captured = LogCapturer.capture(WIRETAP_LOGGER_NAME, () -> {
			String body = client.get().uri("/status/200").retrieve().bodyToMono(String.class).block();
			assertThat(body).isEqualTo("hello");
		});

		// Verify that NO request/response log events were captured
		List<ILoggingEvent> logsList = captured.getEvents();
		assertThat(logsList).as("Expected NO request log when wiretap is disabled")
		.isEmpty();
	}

}