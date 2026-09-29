// SPDX-License-Identifier: MIT
package io.mobsfmcp.client;

/**
 * Failure talking to MobSF. The message is written for the agent: it says what went
 * wrong and what to change, and never contains the API key.
 */
public class MobsfException extends RuntimeException {

	public enum Kind {

		NOT_CONFIGURED, UNREACHABLE, UNAUTHORIZED, NOT_FOUND, BAD_REQUEST, SERVER_ERROR, INVALID_RESPONSE

	}

	private final Kind kind;

	public MobsfException(Kind kind, String message) {
		this(kind, message, null);
	}

	public MobsfException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

}
