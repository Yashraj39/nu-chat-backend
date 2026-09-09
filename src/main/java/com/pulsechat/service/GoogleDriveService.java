package com.pulsechat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;
import com.google.api.services.drive.DriveScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Google Drive storage adapter.
 *
 * The backend authenticates as the configured Google Drive owner, creates a
 * resumable upload session, and returns that session URL to the browser. The
 * browser then uploads the actual bytes directly to Google, so Render does
 * not carry the large file payload.
 */
@Service
public class GoogleDriveService {
    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3";
    private static final String DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files";
    private static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final UserCredentials credentials;
    private final String folderId;
    private final boolean publicFiles;

    public GoogleDriveService(
            ObjectMapper objectMapper,
            @Value("${google-drive.client-id:}") String clientId,
            @Value("${google-drive.client-secret:}") String clientSecret,
            @Value("${google-drive.refresh-token:}") String refreshToken,
            @Value("${google-drive.folder-id:}") String folderId,
            @Value("${google-drive.public-files:true}") boolean publicFiles
    ) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.folderId = folderId == null ? "" : folderId.trim();
        this.publicFiles = publicFiles;

        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(refreshToken)) {
            this.credentials = null;
        } else {
            this.credentials = UserCredentials.newBuilder()
                    .setClientId(clientId.trim())
                    .setClientSecret(clientSecret.trim())
                    .setRefreshToken(refreshToken.trim())
                    .build();
            this.credentials.createScoped(Collections.singleton(DriveScopes.DRIVE));
        }
    }

    public boolean isConfigured() {
        return credentials != null && !folderId.isBlank();
    }

    /** Creates a Google Drive resumable upload session without receiving file bytes. */
    public PreparedUpload prepareUpload(String fileName, String mimeType, long size) throws IOException, InterruptedException {
        requireConfigured();
        if (size <= 0) throw new IllegalArgumentException("Empty file.");
        if (size > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("File is too large. Maximum upload size is 25 MB.");

        String cleanName = sanitizeFilename(fileName);
        String cleanMime = normalizeMime(mimeType);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", cleanName);
        metadata.put("parents", List.of(folderId));
        metadata.put("mimeType", cleanMime);

        HttpRequest request = HttpRequest.newBuilder(URI.create(DRIVE_UPLOAD_API + "?uploadType=resumable&supportsAllDrives=true"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", cleanMime)
                .header("X-Upload-Content-Length", Long.toString(size))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(metadata)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Drive upload session failed (HTTP " + response.statusCode() + ").");
        }

        String location = response.headers().firstValue("Location").orElse("").trim();
        if (location.isBlank()) throw new IOException("Google Drive did not return an upload session URL.");
        return new PreparedUpload(location, cleanName, cleanMime, size);
    }

    /** Finalizes and validates a browser-direct upload, then makes it link-readable when enabled. */
    public DriveFile finalizeUpload(String fileId, String expectedName, String expectedMime, long expectedSize)
            throws IOException, InterruptedException {
        requireConfigured();
        String id = cleanFileId(fileId);
        Map<String, Object> metadata = getFileMetadata(id);

        boolean inTargetFolder = false;
        Object parentsValue = metadata.get("parents");
        if (parentsValue instanceof List<?> parents) {
            inTargetFolder = parents.stream().anyMatch(folderId::equals);
        }
        if (!inTargetFolder) throw new IllegalArgumentException("Uploaded file is outside the configured Drive folder.");
        if (Boolean.TRUE.equals(metadata.get("trashed"))) throw new IllegalArgumentException("Uploaded file was trashed.");

        String name = stringValue(metadata.get("name"));
        String mime = stringValue(metadata.get("mimeType"));
        long size = longValue(metadata.get("size"));
        if (expectedName != null && !expectedName.isBlank() && !expectedName.equals(name)) {
            throw new IllegalArgumentException("Uploaded filename does not match the requested filename.");
        }
        if (expectedMime != null && !expectedMime.isBlank() && !expectedMime.equalsIgnoreCase(mime)) {
            throw new IllegalArgumentException("Uploaded MIME type does not match the requested type.");
        }
        if (expectedSize > 0 && size != expectedSize) {
            throw new IllegalArgumentException("Uploaded file size does not match the selected file.");
        }
        if (size > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("File is too large. Maximum upload size is 25 MB.");

        if (publicFiles) {
            ensureAnyoneReader(id);
            metadata = getFileMetadata(id);
        }

        String downloadUrl = stringValue(metadata.get("webContentLink"));
        if (downloadUrl.isBlank()) {
            downloadUrl = "https://drive.google.com/uc?export=download&id=" + id;
        }
        String viewUrl = stringValue(metadata.get("webViewLink"));
        String thumbnailUrl = stringValue(metadata.get("thumbnailLink"));

        return new DriveFile(
                id,
                name,
                mime,
                size,
                downloadUrl,
                viewUrl,
                thumbnailUrl
        );
    }

    public Map<String, Object> getFileMetadata(String fileId) throws IOException, InterruptedException {
        requireConfigured();
        String id = cleanFileId(fileId);
        String url = DRIVE_API + "/files/" + id +
                "?supportsAllDrives=true&fields=id,name,mimeType,size,parents,trashed,webContentLink,webViewLink,thumbnailLink";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Drive metadata request failed (HTTP " + response.statusCode() + ").");
        }
        return objectMapper.readValue(response.body(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private void ensureAnyoneReader(String fileId) throws IOException, InterruptedException {
        Map<String, Object> permission = Map.of(
                "type", "anyone",
                "role", "reader",
                "allowFileDiscovery", false
        );
        String url = DRIVE_API + "/files/" + fileId + "/permissions?supportsAllDrives=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(permission)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Drive could not make the file link-readable (HTTP " + response.statusCode() + "). Check sharing policy.");
        }
    }

    private String accessToken() throws IOException {
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
            credentials.refreshAccessToken();
            token = credentials.getAccessToken();
        }
        if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
            throw new IOException("Google Drive access token is unavailable.");
        }
        return token.getTokenValue();
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Google Drive is not configured. Add GOOGLE_DRIVE_CLIENT_ID, GOOGLE_DRIVE_CLIENT_SECRET, GOOGLE_DRIVE_REFRESH_TOKEN and GOOGLE_DRIVE_FOLDER_ID.");
        }
    }

    private String cleanFileId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isBlank() || id.length() > 200 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Google Drive file ID.");
        }
        return id;
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) return "file";
        String name = original.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\p{Cntrl}]", "_")
                .trim();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) return "file";
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String normalizeMime(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private long longValue(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }

    public record PreparedUpload(String uploadUrl, String originalName, String mimeType, long size) {}
    public record DriveFile(String fileId, String originalName, String mimeType, long size,
                             String downloadUrl, String viewUrl, String thumbnailUrl) {}
}
