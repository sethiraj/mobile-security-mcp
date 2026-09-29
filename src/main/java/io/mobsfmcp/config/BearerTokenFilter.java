// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the HTTP endpoints: every request needs
 * {@code Authorization: Bearer <MCP_AUTH_TOKEN>}, and a request that carries an
 * {@code Origin} header is rejected unless that origin is allowed. The origin check stops
 * a web page in the user's browser from driving a server bound to localhost (DNS
 * rebinding); ordinary MCP clients send no Origin header.
 */
public class BearerTokenFilter extends OncePerRequestFilter {

	private final byte[] expected;

	private final List<String> allowedOrigins;

	public BearerTokenFilter(String token, List<String> allowedOrigins) {
		this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
		this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String origin = request.getHeader("Origin");
		if (origin != null && !allowedOrigins.contains(origin)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN,
					"Origin not allowed. Set MCP_ALLOWED_ORIGINS if a browser client should reach this server.");
			return;
		}
		String header = request.getHeader("Authorization");
		byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, actual)) {
			response.setHeader("WWW-Authenticate", "Bearer");
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid bearer token");
			return;
		}
		chain.doFilter(request, response);
	}

}
