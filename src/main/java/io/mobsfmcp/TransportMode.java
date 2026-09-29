// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP transport the server exposes. Resolved before Spring starts because it decides
 * whether a web server is created at all.
 * <p>
 * Precedence: {@code --transport=stdio|http} (or {@code --transport stdio|http}) argument,
 * then the {@code MCP_TRANSPORT} environment variable, then {@link #HTTP}.
 */
public enum TransportMode {

	STDIO, HTTP;

	public static final String ARG = "--transport";

	public static final String ENV = "MCP_TRANSPORT";

	/** Result of resolving the transport: the mode plus the args with the transport flag removed. */
	public record Resolution(TransportMode mode, String[] remainingArgs) {
	}

	public static Resolution resolve(String[] args, Map<String, String> env) {
		String fromArg = null;
		List<String> remaining = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.startsWith(ARG + "=")) {
				fromArg = arg.substring(ARG.length() + 1);
			}
			else if (arg.equals(ARG)) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException("Missing value for " + ARG + " (use stdio|http)");
				}
				fromArg = args[++i];
			}
			else {
				remaining.add(arg);
			}
		}
		String value = fromArg != null ? fromArg : env.get(ENV);
		TransportMode mode = (value == null || value.isBlank()) ? HTTP : parse(value);
		return new Resolution(mode, remaining.toArray(String[]::new));
	}

	static TransportMode parse(String value) {
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "stdio" -> STDIO;
			case "http" -> HTTP;
			default -> throw new IllegalArgumentException("Invalid transport '" + value + "' (use stdio|http)");
		};
	}

}
