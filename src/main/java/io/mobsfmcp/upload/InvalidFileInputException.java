// SPDX-License-Identifier: MIT
package io.mobsfmcp.upload;

/** The app file an agent supplied cannot be used; the message says how to fix the call. */
public class InvalidFileInputException extends RuntimeException {

	public InvalidFileInputException(String message) {
		super(message);
	}

}
