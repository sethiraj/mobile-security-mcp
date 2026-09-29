// SPDX-License-Identifier: MIT
package io.mobsfmcp.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import io.mobsfmcp.config.MobsfProperties;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Holds apps uploaded over HTTP until a tool call uses them. Files are stored under
 * {@code <uploadDir>/.uploads} under a generated id, so a client can never choose the
 * path on disk, and the original name is kept only as the file's extension.
 */
@Component
public class UploadStore {

	static final String DIRECTORY = ".uploads";

	private final MobsfProperties properties;

	public UploadStore(MobsfProperties properties) {
		this.properties = properties;
	}

	public String store(InputStream content, String fileName) {
		String extension = StringUtils.getFilenameExtension(fileName);
		String uploadId = UploadController.newUploadId() + (extension == null ? "" : "." + extension.toLowerCase());
		Path target = directory().resolve(uploadId);
		try {
			Files.createDirectories(target.getParent());
			long copied = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
			if (copied > properties.maxUploadBytes()) {
				Files.deleteIfExists(target);
				throw new InvalidFileInputException(
						"Upload is larger than the limit of " + properties.maxUploadMb() + " MB.");
			}
			// keep the name the client used, so MobSF records it instead of the generated id
			Files.writeString(nameFile(uploadId), sanitize(fileName));
		}
		catch (IOException ex) {
			throw new InvalidFileInputException("Could not store the upload: " + ex.getMessage());
		}
		return uploadId;
	}

	/** The file name the client uploaded with, or null when it was not recorded. */
	public String originalName(String uploadId) {
		try {
			Path path = nameFile(uploadId);
			return Files.isRegularFile(path) ? Files.readString(path).strip() : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	private Path nameFile(String uploadId) {
		return directory().resolve(uploadId + ".name");
	}

	private static String sanitize(String fileName) {
		String cleaned = fileName == null ? "" : fileName.replace('\\', '/');
		cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
		return cleaned.isBlank() ? "app.apk" : cleaned;
	}

	/** Resolves an id handed back by {@code POST /uploads}; rejects anything path-like. */
	public Resource resolve(String uploadId) {
		if (uploadId == null || !uploadId.matches("[a-f0-9]{32}(\\.[A-Za-z0-9]{1,8})?")) {
			throw new InvalidFileInputException(
					"'" + uploadId + "' is not a valid uploadId. Use the value returned by POST /uploads.");
		}
		Path path = directory().resolve(uploadId);
		if (!Files.isRegularFile(path)) {
			throw new InvalidFileInputException("Upload " + uploadId
					+ " was not found; it may have been used already or the server restarted. Upload it again.");
		}
		return new FileSystemResource(path);
	}

	private Path directory() {
		return properties.uploadDir().toAbsolutePath().normalize().resolve(DIRECTORY);
	}

}
