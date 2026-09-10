package com.pulsechat.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/klipy")
public class KlipyController {
    private static final String KLIPY_API = "https://api.klipy.com";
    private static final int MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 12L * 1024 * 1024;

    @GetMapping(value = "/featured", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> featured(
            @RequestParam String key,
            @RequestParam(defaultValue = "24") String limit,
            @RequestParam(defaultValue = "high") String contentfilter,
            @RequestParam(defaultValue = "gif,mediumgif,tinygif") String media_filter
    ) throws IOException {
        return proxyApi("/v2/featured", key, limit, contentfilter, media_filter, null, null);
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> search(
            @RequestParam String key,
            @RequestParam String q,
            @RequestParam(defaultValue = "24") String limit,
            @RequestParam(defaultValue = "high") String contentfilter,
            @RequestParam(defaultValue = "gif,mediumgif,tinygif") String media_filter,
            @RequestParam(required = false) String searchfilter
    ) throws IOException {
        return proxyApi("/v2/search", key, limit, contentfilter, media_filter, q, searchfilter);
    }

    /**
     * Media is proxied through Render so the browser never needs direct Klipy access.
     * The URL is allow-listed to Klipy hosts to avoid turning this endpoint into an SSRF proxy.
     */
    @GetMapping("/media")
    public ResponseEntity<StreamingResponseBody> media(
            @RequestParam String url,
            jakarta.servlet.http.HttpServletRequest request
    ) throws IOException {
        String targetUrl = url == null ? "" : url.trim();
        if (targetUrl.isBlank() || targetUrl.length() > 4096) {
            throw new IllegalArgumentException("Invalid Klipy media URL.");
        }

        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Klipy media URL.");
        }

        String host = uri.getHost();
        if (host == null || !(host.equalsIgnoreCase("klipy.com") || host.toLowerCase().endsWith(".klipy.com"))) {
            throw new IllegalArgumentException("Only Klipy media URLs are allowed.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(targetUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "image/avif,image/webp,image/gif,image/*;q=0.8,*/*;q=0.1");
        connection.setRequestProperty("User-Agent", "PulseChat-Klipy-Media-Proxy/1.0");

        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && !range.isBlank()) connection.setRequestProperty(HttpHeaders.RANGE, range);

        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP || status == 307 || status == 308) {
            String location = connection.getHeaderField(HttpHeaders.LOCATION);
            connection.disconnect();
            if (location == null || location.isBlank()) throw new IOException("Klipy did not provide a media redirect.");
            return redirectToKlipyMedia(location, request, targetUrl);
        }

        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("Klipy returned HTTP " + status + ".");
        }

        long contentLength = connection.getContentLengthLong();
        if (contentLength > MAX_MEDIA_BYTES) {
            connection.disconnect();
            throw new IOException("Klipy media is too large.");
        }

        String contentType = connection.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        StreamingResponseBody stream = output -> {
            long total = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_MEDIA_BYTES) throw new IOException("Klipy media is too large.");
                    output.write(buffer, 0, read);
                }
                output.flush();
            } finally {
                connection.disconnect();
            }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400, stale-while-revalidate=604800")
                .header(HttpHeaders.VARY, HttpHeaders.RANGE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (contentLength >= 0) builder.contentLength(contentLength);

        String contentRange = connection.getHeaderField(HttpHeaders.CONTENT_RANGE);
        if (contentRange != null && !contentRange.isBlank()) builder.header(HttpHeaders.CONTENT_RANGE, contentRange);

        return builder.body(stream);
    }

    private ResponseEntity<StreamingResponseBody> redirectToKlipyMedia(String location,
                                                                         jakarta.servlet.http.HttpServletRequest request,
                                                                         String originalUrl) throws IOException {
        URI redirect;
        try {
            redirect = URI.create(location.trim());
        } catch (Exception e) {
            throw new IOException("Invalid Klipy media redirect.");
        }
        String host = redirect.getHost();
        if (host == null || !(host.equalsIgnoreCase("klipy.com") || host.toLowerCase().endsWith(".klipy.com"))) {
            throw new IOException("Klipy media redirect points to an unsupported host.");
        }

        // Re-open against the redirected Klipy CDN URL, still keeping the browser on Render.
        HttpURLConnection redirected = (HttpURLConnection) new URL(redirect.toString()).openConnection();
        redirected.setRequestMethod("GET");
        redirected.setConnectTimeout(10000);
        redirected.setReadTimeout(60000);
        redirected.setInstanceFollowRedirects(false);
        redirected.setRequestProperty("Accept", "image/avif,image/webp,image/gif,image/*;q=0.8,*/*;q=0.1");
        redirected.setRequestProperty("User-Agent", "PulseChat-Klipy-Media-Proxy/1.0");
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && !range.isBlank()) redirected.setRequestProperty(HttpHeaders.RANGE, range);

        int status = redirected.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            redirected.disconnect();
            throw new IOException("Klipy CDN returned HTTP " + status + ".");
        }
        long contentLength = redirected.getContentLengthLong();
        if (contentLength > MAX_MEDIA_BYTES) {
            redirected.disconnect();
            throw new IOException("Klipy media is too large.");
        }
        String contentType = redirected.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(contentType); }
        catch (Exception e) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }

        StreamingResponseBody stream = output -> {
            long total = 0;
            try (InputStream input = redirected.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_MEDIA_BYTES) throw new IOException("Klipy media is too large.");
                    output.write(buffer, 0, read);
                }
                output.flush();
            } finally { redirected.disconnect(); }
        };

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400, stale-while-revalidate=604800")
                .header(HttpHeaders.VARY, HttpHeaders.RANGE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (contentLength >= 0) builder.contentLength(contentLength);
        String contentRange = redirected.getHeaderField(HttpHeaders.CONTENT_RANGE);
        if (contentRange != null && !contentRange.isBlank()) builder.header(HttpHeaders.CONTENT_RANGE, contentRange);
        return builder.body(stream);
    }

    private ResponseEntity<byte[]> proxyApi(
            String path,
            String key,
            String limit,
            String contentfilter,
            String mediaFilter,
            String query,
            String searchfilter
    ) throws IOException {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Klipy API key is missing.");

        List<String> params = new ArrayList<>();
        add(params, "key", key);
        add(params, "limit", limit);
        add(params, "contentfilter", contentfilter);
        add(params, "media_filter", mediaFilter);
        add(params, "q", query);
        add(params, "searchfilter", searchfilter);

        URL target = new URL(KLIPY_API + path + "?" + String.join("&", params));
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PulseChat-Klipy-Proxy/1.0");

        int status = connection.getResponseCode();
        InputStream source = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] body;
        try (InputStream input = source) {
            body = input == null ? new byte[0] : readAtMost(input, MAX_API_RESPONSE_BYTES);
        } finally {
            connection.disconnect();
        }

        if (status < 200 || status >= 300) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }

    private static void add(List<String> params, String name, String value) {
        if (value != null && !value.isBlank()) {
            params.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAtMost(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("Klipy response is too large.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
