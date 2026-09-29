// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the server itself, bound from {@code mobsf-mcp.*} (env:
 * {@code MCP_AUTH_TOKEN}, {@code MCP_ALLOW_ANONYMOUS}, {@code MCP_ALLOWED_ORIGINS}).
 *
 * @param authToken bearer token HTTP clients must present
 * @param allowAnonymous run HTTP without a token; only for a private machine
 * @param allowedOrigins browser origins allowed to call the server; empty means
 * "reject any request that carries an Origin header", which stops DNS-rebinding attacks
 * from a web page while leaving normal (origin-less) MCP clients working
 */
@ConfigurationProperties("mobsf-mcp")
public record ServerProperties(String authToken, @DefaultValue("false") boolean allowAnonymous,
		@DefaultValue("") List<String> allowedOrigins) {

	public boolean hasAuthToken() {
		return authToken != null && !authToken.isBlank();
	}

}
