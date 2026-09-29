// SPDX-License-Identifier: MIT
package io.mobsfmcp.upload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import io.mobsfmcp.config.MobsfProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileInputResolverTest {

	@TempDir
	Path uploadDir;

	private FileInputResolver resolver() {
		return new FileInputResolver(new MobsfProperties("http://localhost:8000", "key", uploadDir,
				uploadDir.resolve("reports"), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), 500));
	}

	@Test
	void acceptsFileInsideUploadDirectory() throws Exception {
		Path apk = Files.writeString(uploadDir.resolve("diva.apk"), "PK-not-really");

		try (var resolved = resolver().resolve(apk.toString(), null, null, null)) {
			assertThat(resolved.fileName()).isEqualTo("diva.apk");
			assertThat(resolved.resource().contentLength()).isEqualTo(13);
			assertThat(resolved.temporaryFile()).isNull();
		}
	}

	@Test
	void rejectsPathTraversalOutsideUploadDirectory() throws Exception {
		Path outside = Files.writeString(uploadDir.getParent().resolve("secret.apk"), "x");

		assertThatThrownBy(() -> resolver().resolve(uploadDir + "/../" + outside.getFileName(), null, null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("outside the allowed upload directory");
	}

	@Test
	void rejectsMissingFileAndUnsupportedExtension() throws Exception {
		Files.writeString(uploadDir.resolve("notes.txt"), "x");

		assertThatThrownBy(() -> resolver().resolve(uploadDir.resolve("nope.apk").toString(), null, null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("No file at");
		assertThatThrownBy(() -> resolver().resolve(uploadDir.resolve("notes.txt").toString(), null, null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("not a supported app file");
	}

	@Test
	void requiresExactlyOneInput() throws Exception {
		Path apk = Files.writeString(uploadDir.resolve("a.apk"), "x");

		assertThatThrownBy(() -> resolver().resolve(null, null, null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("exactly one");
		assertThatThrownBy(() -> resolver().resolve(apk.toString(), "https://example.com/a.apk", null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("only one");
	}

	@Test
	void base64NeedsFileNameAndStaysUnderTheSizeLimit() {
		String data = Base64.getEncoder().encodeToString("apk-bytes".getBytes());

		assertThatThrownBy(() -> resolver().resolve(null, null, data, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("'fileName' is required");

		try (var resolved = resolver().resolve(null, null, data, "tiny.apk")) {
			assertThat(resolved.fileName()).isEqualTo("tiny.apk");
			assertThat(resolved.resource().getFilename()).isEqualTo("tiny.apk");
		}
	}

	@Test
	void urlsMustBeHttpsAndPublic() {
		assertThatThrownBy(() -> resolver().resolve(null, "http://example.com/a.apk", null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("Only https");
		assertThatThrownBy(() -> resolver().resolve(null, "https://127.0.0.1/a.apk", null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("private or loopback");
		assertThatThrownBy(() -> resolver().resolve(null, "https://192.168.0.5/a.apk", null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("private or loopback");
		assertThatThrownBy(() -> resolver().resolve(null, "https://[::1]/a.apk", null, null))
			.isInstanceOf(InvalidFileInputException.class)
			.hasMessageContaining("private or loopback");
	}

	@Test
	void fileNameCannotEscapeThroughDirectorySeparators() throws Exception {
		Path apk = Files.writeString(uploadDir.resolve("b.apk"), "x");

		try (var resolved = resolver().resolve(apk.toString(), null, null, "../../evil.apk")) {
			assertThat(resolved.fileName()).isEqualTo("evil.apk");
		}
	}

}
