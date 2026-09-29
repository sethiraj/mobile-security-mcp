// SPDX-License-Identifier: MIT
package io.mobsfmcp.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import io.mobsfmcp.config.MobsfProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns the ways an agent can hand over an app file into a {@link Resource} MobSF can be
 * given: a path inside the mounted upload directory, an HTTPS URL, or inline base64.
 * <p>
 * Each way is deliberately restricted: paths may not escape the upload directory, URL
 * downloads never reach private networks (SSRF) and every input has a size limit.
 */
@Component
public class FileInputResolver {

	/** Extensions MobSF can analyse. Anything else is rejected before the upload. */
	static final List<String> ALLOWED_EXTENSIONS = List.of("apk", "xapk", "apks", "aab", "ipa", "appx", "zip", "jar",
			"so", "dylib", "a");

	static final long MAX_BASE64_BYTES = 10L * 1024 * 1024;

	private final MobsfProperties properties;

	private final HttpClient httpClient;

	/** Null in stdio mode, where POST /uploads does not exist. */
	private final UploadStore uploadStore;

	@Autowired
	public FileInputResolver(MobsfProperties properties, ObjectProvider<UploadStore> uploadStore) {
		this(properties, HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build(),
				uploadStore.getIfAvailable());
	}

	public FileInputResolver(MobsfProperties properties) {
		this(properties, HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build(), null);
	}

	FileInputResolver(MobsfProperties properties, HttpClient httpClient, UploadStore uploadStore) {
		this.properties = properties;
		this.httpClient = httpClient;
		this.uploadStore = uploadStore;
	}

	/** A file ready to be uploaded, plus the name MobSF should record and any temp file to clean up. */
	public record ResolvedFile(Resource resource, String fileName, Path temporaryFile) implements AutoCloseable {

		@Override
		public void close() {
			if (temporaryFile != null) {
				try {
					Files.deleteIfExists(temporaryFile);
				}
				catch (IOException ignored) {
					// best effort: the file lives in the upload directory and will be overwritten
				}
			}
		}
	}

	public ResolvedFile resolve(String path, String url, String base64, String fileName) {
		return resolve(path, url, base64, null, fileName);
	}

	public ResolvedFile resolve(String path, String url, String base64, String uploadId, String fileName) {
		int given = (isSet(path) ? 1 : 0) + (isSet(url) ? 1 : 0) + (isSet(base64) ? 1 : 0) + (isSet(uploadId) ? 1 : 0);
		if (given == 0) {
			throw new InvalidFileInputException("Provide exactly one of 'path' (a file under " + properties.uploadDir()
					+ "), 'url' (https), 'base64' or 'uploadId' (from POST /uploads).");
		}
		if (given > 1) {
			throw new InvalidFileInputException("Provide only one of 'path', 'url', 'base64' or 'uploadId'.");
		}
		if (isSet(uploadId)) {
			return fromUploadId(uploadId, fileName);
		}
		if (isSet(path)) {
			return fromPath(path, fileName);
		}
		if (isSet(url)) {
			return fromUrl(url, fileName);
		}
		return fromBase64(base64, fileName);
	}

	private ResolvedFile fromUploadId(String uploadId, String fileName) {
		if (uploadStore == null) {
			throw new InvalidFileInputException(
					"'uploadId' only works in http mode, where POST /uploads is available. Use 'path' or 'url'.");
		}
		Resource resource = uploadStore.resolve(uploadId);
		String name = pickName(fileName,
				isSet(uploadStore.originalName(uploadId)) ? uploadStore.originalName(uploadId) : resource.getFilename());
		checkExtension(name);
		// the stored file is consumed by this call
		return new ResolvedFile(resource, name, toPathOrNull(resource));
	}

	private static Path toPathOrNull(Resource resource) {
		try {
			return resource.isFile() ? resource.getFile().toPath() : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	private ResolvedFile fromPath(String path, String fileName) {
		Path uploadDir = properties.uploadDir().toAbsolutePath().normalize();
		Path candidate = Path.of(path).toAbsolutePath().normalize();
		if (!candidate.startsWith(uploadDir)) {
			throw new InvalidFileInputException("'" + path + "' is outside the allowed upload directory " + uploadDir
					+ ". Copy the file there (in Docker: mount it with -v <host dir>:" + uploadDir + ").");
		}
		if (!Files.isRegularFile(candidate)) {
			throw new InvalidFileInputException("No file at " + candidate
					+ ". List the directory first, and remember the path is the one inside the container.");
		}
		String name = pickName(fileName, candidate.getFileName().toString());
		checkExtension(name);
		return new ResolvedFile(new FileSystemResource(candidate), name, null);
	}

	private ResolvedFile fromUrl(String url, String fileName) {
		URI uri = validateUrl(url);
		String name = pickName(fileName, lastSegment(uri));
		checkExtension(name);
		Path target = temporaryPath(name);
		try {
			HttpResponse<InputStream> response = httpClient.send(
					HttpRequest.newBuilder(uri).timeout(properties.readTimeout()).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() / 100 != 2) {
				throw new InvalidFileInputException("Download of " + uri + " failed with HTTP " + response.statusCode());
			}
			long limit = properties.maxUploadBytes();
			try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
				long copied = copyAtMost(in, out, limit);
				if (copied > limit) {
					throw new InvalidFileInputException(
							"Download of " + uri + " exceeds the limit of " + properties.maxUploadMb() + " MB.");
				}
			}
		}
		catch (IOException ex) {
			deleteQuietly(target);
			throw new InvalidFileInputException("Could not download " + uri + ": " + ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			deleteQuietly(target);
			throw new InvalidFileInputException("Download of " + uri + " was interrupted.");
		}
		catch (RuntimeException ex) {
			deleteQuietly(target);
			throw ex;
		}
		return new ResolvedFile(new FileSystemResource(target), name, target);
	}

	private ResolvedFile fromBase64(String base64, String fileName) {
		if (!isSet(fileName)) {
			throw new InvalidFileInputException("'fileName' is required with 'base64' so MobSF knows the file type.");
		}
		checkExtension(fileName);
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidFileInputException("'base64' is not valid base64 data: " + ex.getMessage());
		}
		if (bytes.length > MAX_BASE64_BYTES) {
			throw new InvalidFileInputException("base64 input is limited to " + (MAX_BASE64_BYTES / 1024 / 1024)
					+ " MB; use 'path' or 'url' for bigger apps.");
		}
		return new ResolvedFile(new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return fileName;
			}
		}, fileName, null);
	}

	URI validateUrl(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		}
		catch (URISyntaxException ex) {
			throw new InvalidFileInputException("'" + url + "' is not a valid URL.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https")) {
			throw new InvalidFileInputException("Only https URLs are allowed (got '" + scheme + "').");
		}
		if (uri.getHost() == null) {
			throw new InvalidFileInputException("'" + url + "' has no host.");
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException ex) {
			throw new InvalidFileInputException("Cannot resolve host '" + uri.getHost() + "'.");
		}
		for (InetAddress address : addresses) {
			if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
					|| address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocal(address)) {
				throw new InvalidFileInputException("Refusing to download from " + uri.getHost()
						+ ": it resolves to a private or loopback address. Use 'path' for local files.");
			}
		}
		return uri;
	}

	private static boolean isUniqueLocal(InetAddress address) {
		byte[] bytes = address.getAddress();
		return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
	}

	private void checkExtension(String fileName) {
		String extension = StringUtils.getFilenameExtension(fileName);
		if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
			throw new InvalidFileInputException("'" + fileName + "' is not a supported app file. MobSF accepts: "
					+ String.join(", ", ALLOWED_EXTENSIONS) + ".");
		}
	}

	private Path temporaryPath(String name) {
		Path directory = properties.uploadDir().toAbsolutePath().normalize().resolve(".downloads");
		try {
			Files.createDirectories(directory);
		}
		catch (IOException ex) {
			throw new InvalidFileInputException(
					"Cannot write to " + directory + ": " + ex.getMessage() + ". Check the mounted upload directory.");
		}
		return directory.resolve(System.nanoTime() + "-" + sanitize(name));
	}

	private static long copyAtMost(InputStream in, OutputStream out, long limit) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		long total = 0;
		int read;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > limit) {
				return total;
			}
			out.write(buffer, 0, read);
		}
		return total;
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException ignored) {
			// best effort
		}
	}

	private static String pickName(String fileName, String fallback) {
		String name = isSet(fileName) ? fileName : fallback;
		return sanitize(name);
	}

	private static String sanitize(String name) {
		String cleaned = name.replace('\\', '/');
		cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
		return cleaned.isBlank() ? "app.apk" : cleaned;
	}

	private static String lastSegment(URI uri) {
		String path = uri.getPath() == null ? "" : uri.getPath();
		String segment = path.substring(path.lastIndexOf('/') + 1);
		return segment.isBlank() ? "download.apk" : segment;
	}

	private static boolean isSet(String value) {
		return value != null && !value.isBlank();
	}

}
