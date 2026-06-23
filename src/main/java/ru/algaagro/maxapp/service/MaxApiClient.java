package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.util.JsonHelper;

@Component
public class MaxApiClient {

    private static final Logger log = LoggerFactory.getLogger(MaxApiClient.class);

    private final AppProperties appProperties;
    private final JsonHelper jsonHelper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public MaxApiClient(AppProperties appProperties, JsonHelper jsonHelper) {
        this.appProperties = appProperties;
        this.jsonHelper = jsonHelper;
    }

    public boolean enabled() {
        return appProperties.getMax().getBotToken() != null && !appProperties.getMax().getBotToken().isBlank();
    }

    public JsonNode getUpdates(String marker) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(appProperties.getMax().getApiBaseUrl())
                .append("/updates?timeout=").append(appProperties.getMax().getPollTimeoutSeconds())
                .append("&limit=").append(appProperties.getMax().getPollLimit());
        if (marker != null && !marker.isBlank()) {
            url.append("&marker=").append(URLEncoder.encode(marker, StandardCharsets.UTF_8));
        }
        HttpRequest request = baseRequest(url.toString())
                .GET()
                .timeout(Duration.ofSeconds(appProperties.getMax().getPollTimeoutSeconds() + 15L))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response);
        return jsonHelper.readTree(response.body());
    }

    public void sendToUser(Long userId, String text, List<Map<String, Object>> attachments, String format) {
        sendMessage("user_id=" + userId, text, attachments, format);
    }

    public void sendToChat(Long chatId, String text, List<Map<String, Object>> attachments, String format) {
        sendMessage("chat_id=" + chatId, text, attachments, format);
    }

    public void answerCallback(String callbackId, String notification) {
        if (callbackId == null || callbackId.isBlank()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notification", notification);
        sendPost("/answers?callback_id=" + URLEncoder.encode(callbackId, StandardCharsets.UTF_8), body);
    }

    private void sendMessage(String targetQuery, String text, List<Map<String, Object>> attachments, String format) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        if (attachments != null && !attachments.isEmpty()) {
            body.put("attachments", attachments);
        }
        if (format != null) {
            body.put("format", format);
        }
        sendPost("/messages?" + targetQuery, body);
    }

    private void sendPost(String path, Map<String, Object> body) {
        if (!enabled()) {
            log.warn("MAX bot token is empty, skip request {}", path);
            return;
        }
        try {
            HttpRequest request = baseRequest(appProperties.getMax().getApiBaseUrl() + path)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonHelper.writeValue(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MAX API request failed: {}", e.getMessage());
        }
    }

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", appProperties.getMax().getBotToken());
    }

    private void ensureSuccess(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("MAX API error " + response.statusCode() + ": " + response.body());
        }
    }
}
