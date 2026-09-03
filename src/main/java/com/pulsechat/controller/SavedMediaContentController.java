package com.pulsechat.controller;

import com.pulsechat.model.SavedMedia;
import com.pulsechat.repo.SavedMediaRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/media/saved")
public class SavedMediaContentController {
    private final SavedMediaRepository repo;

    public SavedMediaContentController(SavedMediaRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/content/{id}")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable String id) throws IOException {
        SavedMedia item = repo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Saved media not found."));

        if (item.getUrl() == null || item.getUrl().isBlank()) {
            throw new NoSuchElementException("Saved media is unavailable.");
        }

        URI uri;
        try {
            uri = URI.create(item.getUrl().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid saved media URL.");
        }

        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).endsWith("res.cloudinary.com")) {
            throw new IllegalArgumentException("Saved media proxy only supports stored Cloudinary media.");
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Media storage returned HTTP " + status + ".");
        }

        String contentType = item.getMimeType();
        if (contentType == null || contentType.isBlank()) contentType = connection.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        long length = connection.getContentLengthLong();

        StreamingResponseBody stream = output -> {
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
            } finally {
                connection.disconnect();
            }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
        if (length >= 0) builder.contentLength(length);
        return builder.body(stream);
    }
}
