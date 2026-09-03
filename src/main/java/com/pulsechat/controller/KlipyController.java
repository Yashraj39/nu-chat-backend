package com.pulsechat.controller;

import jakarta.servlet.http.HttpServletRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/klipy")
public class KlipyController {
    private static final String KLIPY_API = "https://api.klipy.com";

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

    @GetMapping("/content")
    public ResponseEntity<StreamingResponseBody> content(@RequestParam String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid media URL.");
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || !isAllowedMediaHost(host)) {
            throw new IllegalArgumentException("Only Klipy media URLs are supported.");
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "PulseChat-Klipy-Proxy/1.0");

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Klipy media returned HTTP " + status + ".");
        }

        String contentType = connection.getContentType();
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
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300");
        if (length >= 0) builder.contentLength(length);
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
            if (input == null) body = new byte[0];
            else body = readAtMost(input, 2 * 1024 * 1024);
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
                .cacheControl(org.springframework.http.CacheControl.noCache())
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

    private static boolean isAllowedMediaHost(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("klipy.com") || h.endsWith(".klipy.com")
                || h.equals("klipycdn.com") || h.endsWith(".klipycdn.com");
    }
}
