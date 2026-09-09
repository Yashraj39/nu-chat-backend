package com.pulsechat.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/klipy")
public class KlipyController {
    private static final String KLIPY_API = "https://api.klipy.com";
    private static final int MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024;

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
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePublic())
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
