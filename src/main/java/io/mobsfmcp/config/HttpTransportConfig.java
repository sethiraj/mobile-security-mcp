// SPDX-License-Identifier: MIT
package io.mobsfmcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans that only exist when the server runs over Streamable HTTP.
 * <p>
 * HTTP mode refuses to start without {@code MCP_AUTH_TOKEN}: an unauthenticated MCP
 * server lets anyone who can reach the port upload files and run scans. Set
 * {@code MCP_ALLOW_ANONYMOUS=true} to opt out on a private machine.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class HttpTransportConfig {

	@Bean
	FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(ServerProperties properties,
			@Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
		if (!properties.hasAuthToken()) {
			if (!properties.allowAnonymous()) {
				throw new IllegalStateException("""
						MCP_AUTH_TOKEN is not set.

						In http mode anyone who can reach this port could upload files and start scans, so a \
						bearer token is required. Generate one (for example `openssl rand -hex 16`) and pass it as \
						MCP_AUTH_TOKEN; clients then send 'Authorization: Bearer <token>'.

						On a private machine you can set MCP_ALLOW_ANONYMOUS=true instead, or use stdio \
						(--transport=stdio), which needs no token.""");
			}
			return unregistered();
		}
		FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(
				new BearerTokenFilter(properties.authToken(), properties.allowedOrigins()));
		registration.addUrlPatterns(endpoint, endpoint + "/*", "/uploads", "/uploads/*", "/reports/*");
		registration.setOrder(0);
		return registration;
	}

	private static FilterRegistrationBean<BearerTokenFilter> unregistered() {
		FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>();
		registration.setEnabled(false);
		return registration;
	}

}
