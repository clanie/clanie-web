package dk.clanie.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

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
import dk.clanie.web.exception.UnprocessableEntityException;
import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Unit tests for {@link RestClientFactory}.
 *
 * Tests use a lightweight Reactor Netty HTTP server started on a random port
 * and verify that responses are mapped to the correct exceptions by the
 * response handler installed by {@link RestClientFactory}.
 */
public class RestClientFactoryTest {

	private DisposableServer server;
	private String baseUrl;
	private RestClientFactory clientFactory;


	@BeforeEach
	void setUp() {
		// Start a server that echo the status codes given on the path.
		// Expected path is /status/{code} - anything else gives an 404 (Not Found) response.
		server = HttpServer.create()
				.port(0)
				.handle((request, response) -> {
					String uri = request.uri();
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
		clientFactory = new RestClientFactory(RestClient.builder());
	}


	@AfterEach
	void tearDown() {
		if (server != null) server.disposeNow();
	}


	@Test
	void testSuccessfulResponse() {
		RestClient client = clientFactory.newRestClient(baseUrl, false);
		String body = client.get().uri("/status/200").retrieve().body(String.class);
		assertEquals("hello", body);
	}


	@Test
	void testFoundResponse() {
		RestClient client = clientFactory.newRestClient(baseUrl, false);
		Throwable ex = assertThrows(FoundException.class, () ->
		client.get().uri("/status/302").retrieve().body(String.class));
		assertEquals("http://example.com/redirect", ((FoundException) ex).getLocation());
	}


	@ParameterizedTest(name = "status {0} -> {1}")
	@MethodSource("testResponseCodeMappingArguments")
	void testResponseCodeMapping(int statusCode, Class<? extends Throwable> expectedException) {
		String uri = "/status/" + statusCode;
		RestClient client = clientFactory.newRestClient(baseUrl, false);
		assertThrows(expectedException, () ->
		client.get().uri(uri).retrieve().body(String.class));
	}

	static Stream<Arguments> testResponseCodeMappingArguments() {
		return Stream.of(
				// status, expected exception
				Arguments.of(400, BadRequestException.class),
				Arguments.of(401, UnauthorizedException.class),
				Arguments.of(403, ForbiddenException.class),
				Arguments.of(404, NotFoundException.class),
				Arguments.of(409, ConflictException.class),
				Arguments.of(422, UnprocessableEntityException.class),
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
		RestClient client = clientFactory.newRestClient(baseUrl, true);

		// Capture log events while making a request
		CapturedLoggingEvents captured = LogCapturer.capture(RestClientFactory.class, () -> {
			String body = client.get().uri("/status/200").retrieve().body(String.class);
			assertEquals("hello", body);
		});

		// Verify that log events were captured
		List<ILoggingEvent> logsList = captured.getEvents();
		assertThat(logsList).as("Expected log events when wiretap is enabled").isNotEmpty();

		// Verify request was logged
		assertThat(logsList).as("Expected to find request log with 'Request: GET'")
		.anyMatch(event -> event.getLevel() == Level.TRACE 
		&& event.getFormattedMessage().contains("Request: GET"));

		// Verify response was logged
		assertThat(logsList).as("Expected to find response log with 'Response: 200'")
		.anyMatch(event -> event.getLevel() == Level.TRACE 
		&& event.getFormattedMessage().contains("Response: 200"));
	}


	@Test
	void testNoWiretapDoesNotLog() {
		// Create client with wiretap disabled
		RestClient client = clientFactory.newRestClient(baseUrl, false);

		// Capture log events while making a request
		CapturedLoggingEvents captured = LogCapturer.capture(RestClientFactory.class, () -> {
			String body = client.get().uri("/status/200").retrieve().body(String.class);
			assertEquals("hello", body);
		});

		// Verify that NO request/response log events were captured
		List<ILoggingEvent> logsList = captured.getEvents();

		// Check for request logs
		assertThat(logsList).as("Expected NO request log when wiretap is disabled")
		.noneMatch(event -> event.getFormattedMessage().contains("Request: GET"));

		// Check for response logs
		assertThat(logsList).as("Expected NO response log when wiretap is disabled")
		.noneMatch(event -> event.getFormattedMessage().contains("Response: 200"));
	}


}
