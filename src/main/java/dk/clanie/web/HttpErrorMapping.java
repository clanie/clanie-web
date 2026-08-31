/*
 * Copyright (C) 2026, Claus Nielsen, clausn999@gmail.com
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

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import dk.clanie.web.exception.BadRequestException;
import dk.clanie.web.exception.ConflictException;
import dk.clanie.web.exception.ForbiddenException;
import dk.clanie.web.exception.InternalServerErrorException;
import dk.clanie.web.exception.NotFoundException;
import dk.clanie.web.exception.TooManyRequestsException;
import dk.clanie.web.exception.UnauthorizedException;
import dk.clanie.web.exception.UnprocessableContentException;

/**
 * Maps HTTP error status codes to the exceptions this module throws, shared by
 * {@link WebClientFactory} and {@link RestClientFactory}.
 * <p>
 * The response body is carried into the exception message: a bare "Forbidden" says
 * nothing about <em>why</em> the server refused, while the body usually holds the API's
 * own error code and explanation. Redirects are not handled here - they carry their
 * destination in a header, and each factory reads that header its own way.
 */
public class HttpErrorMapping {

	/**
	 * Maximum number of response-body characters carried in the exception message. Enough
	 * to hold an API's error code and explanation without dumping a full error page into
	 * the log.
	 */
	static final int MAX_BODY_IN_EXCEPTION_MESSAGE = 500;

	/**
	 * Maximum number of bytes read out of a compressed error body. Only
	 * {@link #MAX_BODY_IN_EXCEPTION_MESSAGE} characters ever reach the message, so this
	 * only has to be generous enough not to cut an explanation short - and small enough
	 * that a hostile or broken server cannot inflate a few bytes into a heap dump.
	 */
	static final int MAX_DECOMPRESSED_BODY_BYTES = 64 * 1024;

	/**
	 * Response header carrying the server's own id for the failed request. Saxo's OpenAPI
	 * gateway sets it, and their support asks for it in every ticket - without it a report
	 * of a server-side fault cannot be traced back to the request that hit it. Only this
	 * one name is looked for: no other API this module talks to has been seen to send a
	 * correlation header, and guessing at names none of them use would be noise.
	 */
	static final String CORRELATION_HEADER = "X-Correlation";

	/**
	 * Maximum number of correlation-id characters carried in the exception message. Real
	 * ids are short; the cap only stops a broken or hostile server from pushing the
	 * server's actual explanation out of the message with a header of its own.
	 */
	static final int MAX_CORRELATION_ID_LENGTH = 100;


	private HttpErrorMapping() {}


	/** The body as text, decompressing it first when it is gzipped. */
	static @Nullable String decodeBody(byte @Nullable [] body) {
		if (body == null) return null;
		return new String(isGzipped(body) ? gunzipQuietly(body) : body, StandardCharsets.UTF_8);
	}


	/**
	 * Whether the body carries gzip's magic number. The bytes are sniffed rather than the
	 * {@code Content-Encoding} header read, because neither factory's HTTP client asks for
	 * compression, so neither decompresses on its own - a declared encoding and an
	 * undeclared one both arrive here still compressed, and the magic number covers both.
	 */
	private static boolean isGzipped(byte[] body) {
		return body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
	}


	/**
	 * The inflated body, or the bytes as they came when they only looked gzipped. A body
	 * we failed to inflate must not become no body at all - unreadable bytes still say
	 * more than a bare status name.
	 */
	private static byte[] gunzipQuietly(byte[] body) {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
			return in.readNBytes(MAX_DECOMPRESSED_BODY_BYTES);
		} catch (IOException e) {
			return body;
		}
	}


	/**
	 * The exception for the given error status, with the decoded {@code body} appended to
	 * its message.
	 * <p>
	 * Servers gzip error responses whether or not the client asked them to - Saxo's
	 * gateway does - and reading those bytes as text turns the API's own explanation into
	 * mojibake that no amount of log-reading can undo. So the bytes are decompressed
	 * first when they carry the gzip magic number.
	 */
	public static RuntimeException toException(HttpStatusCode statusCode, byte @Nullable [] body) {
		return toException(statusCode, decodeBody(body), null);
	}


	/**
	 * The exception for the given error status, with the decoded {@code body} and the
	 * server's correlation id, when {@code headers} carry one, appended to its message.
	 *
	 * @see #CORRELATION_HEADER
	 */
	public static RuntimeException toException(HttpStatusCode statusCode, byte @Nullable [] body, @Nullable HttpHeaders headers) {
		return toException(statusCode, decodeBody(body), correlationId(headers));
	}


	/** The exception for the given error status, with {@code body} appended to its message. */
	public static RuntimeException toException(HttpStatusCode statusCode, @Nullable String body) {
		return toException(statusCode, body, null);
	}


	private static RuntimeException toException(HttpStatusCode statusCode, @Nullable String body, @Nullable String correlationId) {
		if (BAD_REQUEST.equals(statusCode)) return new BadRequestException(message(correlationId, "Bad Request", body));
		if (UNAUTHORIZED.equals(statusCode)) return new UnauthorizedException(message(correlationId, "Unauthorized", body));
		if (FORBIDDEN.equals(statusCode)) return new ForbiddenException(message(correlationId, "Forbidden", body));
		if (NOT_FOUND.equals(statusCode)) return new NotFoundException(message(correlationId, "Not Found", body));
		if (CONFLICT.equals(statusCode)) return new ConflictException(message(correlationId, "Conflict", body));
		if (UNPROCESSABLE_CONTENT.equals(statusCode)) return new UnprocessableContentException(message(correlationId, "Unprocessable Content", body));
		if (TOO_MANY_REQUESTS.equals(statusCode)) return new TooManyRequestsException(message(correlationId, "Too Many Requests", body));
		if (statusCode.is4xxClientError()) return new BadRequestException(message(correlationId, "Client Error " + statusCode, body));
		if (INTERNAL_SERVER_ERROR.equals(statusCode)) return new InternalServerErrorException(message(correlationId, "Internal Server Error", body));
		return new InternalServerErrorException(message(correlationId, "Server Error " + statusCode, body));
	}


	/**
	 * The exception message: the status name, the response body, and the server's
	 * correlation id. The id goes on last, <em>after</em> the body has been truncated, so
	 * a long error page cannot push the one value Saxo's support asks for out of the
	 * message.
	 */
	private static String message(@Nullable String correlationId, String statusText, @Nullable String body) {
		return withCorrelationId(withBody(statusText, body), correlationId);
	}


	/** The correlation id from the response headers, or {@code null} when there is none. */
	static @Nullable String correlationId(@Nullable HttpHeaders headers) {
		if (headers == null) return null;
		String id = headers.getFirst(CORRELATION_HEADER);
		return id == null || id.isBlank() ? null : id.strip();
	}


	/** Appends the correlation id, truncated, to an already-built message. */
	static String withCorrelationId(String message, @Nullable String correlationId) {
		if (correlationId == null || correlationId.isBlank()) return message;
		String trimmed = correlationId.strip();
		if (trimmed.length() > MAX_CORRELATION_ID_LENGTH) {
			trimmed = trimmed.substring(0, MAX_CORRELATION_ID_LENGTH) + "\u2026";
		}
		return message + " [" + CORRELATION_HEADER + ": " + trimmed + "]";
	}


	/** Appends the response body, collapsed onto one line and truncated, to the status name. */
	static String withBody(String statusText, @Nullable String body) {
		if (body == null) return statusText;
		String trimmed = body.strip().replaceAll("\\s+", " ");
		if (trimmed.isEmpty()) return statusText;
		if (trimmed.length() > MAX_BODY_IN_EXCEPTION_MESSAGE) {
			trimmed = trimmed.substring(0, MAX_BODY_IN_EXCEPTION_MESSAGE) + "…";
		}
		return statusText + ": " + trimmed;
	}


}
