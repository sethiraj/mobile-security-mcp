// SPDX-License-Identifier: MIT
package io.mobsfmcp;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TransportModeTest {

	@Test
	void defaultsToHttp() {
		assertThat(TransportMode.resolve(new String[0], Map.of()).mode()).isEqualTo(TransportMode.HTTP);
		assertThat(TransportMode.resolve(new String[0], Map.of("MCP_TRANSPORT", " ")).mode())
			.isEqualTo(TransportMode.HTTP);
	}

	@Test
	void readsEnvironmentVariable() {
		assertThat(TransportMode.resolve(new String[0], Map.of("MCP_TRANSPORT", "STDIO")).mode())
			.isEqualTo(TransportMode.STDIO);
	}

	@Test
	void argumentOverridesEnvironment() {
		var resolution = TransportMode.resolve(new String[] { "--transport=stdio" }, Map.of("MCP_TRANSPORT", "http"));
		assertThat(resolution.mode()).isEqualTo(TransportMode.STDIO);
		assertThat(resolution.remainingArgs()).isEmpty();
	}

	@Test
	void acceptsSpaceSeparatedArgumentAndKeepsOtherArgs() {
		var resolution = TransportMode.resolve(new String[] { "--server.port=9000", "--transport", "http" },
				Map.of("MCP_TRANSPORT", "stdio"));
		assertThat(resolution.mode()).isEqualTo(TransportMode.HTTP);
		assertThat(resolution.remainingArgs()).containsExactly("--server.port=9000");
	}

	@Test
	void rejectsUnknownValues() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TransportMode.resolve(new String[] { "--transport=grpc" }, Map.of()))
			.withMessageContaining("use stdio|http");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TransportMode.resolve(new String[0], Map.of("MCP_TRANSPORT", "sse")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> TransportMode.resolve(new String[] { "--transport" }, Map.of()));
	}

}
