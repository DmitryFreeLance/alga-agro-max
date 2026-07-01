package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Component
public class MaxApiClient {

    private static final Logger log = LoggerFactory.getLogger(MaxApiClient.class);

    private final AppProperties appProperties;
    private final JsonHelper jsonHelper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ConcurrentMap<String, String> uploadedImageTokens = new ConcurrentHashMap<>();
    private volatile String cachedBotPublicUrl;

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

    public String getBotPublicUrl() {
        if (cachedBotPublicUrl != null && !cachedBotPublicUrl.isBlank()) {
            return cachedBotPublicUrl;
        }
        if (!enabled()) {
            return null;
        }
        try {
            HttpRequest request = baseRequest(appProperties.getMax().getApiBaseUrl() + "/me")
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
            JsonNode botInfo = jsonHelper.readTree(response.body());
            String username = botInfo.path("username").asText("").trim();
            if (username.isBlank()) {
                return null;
            }
            cachedBotPublicUrl = "https://max.ru/" + username;
            return cachedBotPublicUrl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MAX /me request interrupted: {}", e.getMessage());
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("MAX /me request failed: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> classpathImageAttachment(String classpathLocation) {
        if (classpathLocation == null || classpathLocation.isBlank()) {
            return null;
        }
        String token = uploadedImageTokens.computeIfAbsent(classpathLocation, this::uploadClasspathImageToken);
        if (token == null || token.isBlank()) {
            return null;
        }
        return Map.of(
                "type", "image",
                "payload", Map.of("token", token)
        );
    }

    public Map<String, Object> urlImageAttachment(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String token = uploadedImageTokens.computeIfAbsent("url::" + imageUrl.trim(), key -> uploadImageTokenFromUrl(imageUrl.trim()));
        if (token == null || token.isBlank()) {
            return null;
        }
        return Map.of(
                "type", "image",
                "payload", Map.of("token", token)
        );
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MAX API request interrupted for {}: {}", path, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.warn("MAX API request failed for {}: {}", path, e.getMessage());
        }
    }

    private String uploadClasspathImageToken(String classpathLocation) {
        if (!enabled()) {
            return null;
        }
        try {
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            if (!resource.exists()) {
                log.warn("MAX image upload skipped, classpath resource not found: {}", classpathLocation);
                return null;
            }
            HttpRequest uploadRequest = baseRequest(appProperties.getMax().getApiBaseUrl() + "/uploads?type=image")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(uploadResponse);
            JsonNode uploadJson = jsonHelper.readTree(uploadResponse.body());
            String uploadUrl = uploadJson.path("url").asText("");
            if (uploadUrl.isBlank()) {
                throw new IllegalStateException("MAX image upload URL is empty");
            }

            byte[] bytes;
            try (InputStream inputStream = resource.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }
            return uploadImageBytes(uploadUrl, resource.getFilename(), "image/png", bytes, classpathLocation);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MAX classpath image upload interrupted for {}: {}", classpathLocation, e.getMessage());
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("MAX classpath image upload failed for {}: {}", classpathLocation, e.getMessage());
            return null;
        }
    }

    private String uploadImageTokenFromUrl(String imageUrl) {
        if (!enabled()) {
            return null;
        }
        try {
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (downloadResponse.statusCode() / 100 != 2) {
                throw new IllegalStateException("Image download failed " + downloadResponse.statusCode());
            }
            HttpRequest uploadRequest = baseRequest(appProperties.getMax().getApiBaseUrl() + "/uploads?type=image")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> uploadResponse = httpClient.send(uploadRequest, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(uploadResponse);
            JsonNode uploadJson = jsonHelper.readTree(uploadResponse.body());
            String uploadUrl = uploadJson.path("url").asText("");
            if (uploadUrl.isBlank()) {
                throw new IllegalStateException("MAX image upload URL is empty");
            }
            String filename = resolveFilename(imageUrl, downloadResponse.headers());
            String contentType = resolveContentType(downloadResponse.headers(), filename);
            return uploadImageBytes(uploadUrl, filename, contentType, downloadResponse.body(), imageUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("MAX URL image upload interrupted for {}: {}", imageUrl, e.getMessage());
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("MAX URL image upload failed for {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private String uploadImageBytes(String uploadUrl, String filename, String contentType, byte[] bytes, String sourceLabel) throws IOException, InterruptedException {
        String boundary = "----AlgaAgroMaxBoundary" + System.nanoTime();
        HttpRequest fileRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(buildMultipartBody(boundary, filename, contentType, bytes)))
                .build();
        HttpResponse<String> fileResponse = httpClient.send(fileRequest, HttpResponse.BodyHandlers.ofString());
        if (fileResponse.statusCode() / 100 != 2) {
            throw new IllegalStateException("MAX image upload failed " + fileResponse.statusCode() + ": " + fileResponse.body());
        }
        JsonNode fileJson = jsonHelper.readTree(fileResponse.body());
        String token = firstText(fileJson,
                "token",
                "data.token",
                "payload.token",
                "file.token",
                "media.token",
                "image.token",
                "attachment.token",
                "result.token",
                "uploads.0.token",
                "files.0.token",
                "data.uploads.0.token",
                "data.files.0.token");
        if (token.isBlank()) {
            log.info("MAX image upload completed without token for {}. Response body={}",
                    sourceLabel,
                    TextUtils.trimTo(fileResponse.body(), 1200));
            return "";
        }
        return token;
    }

    private byte[] buildMultipartBody(String boundary, String filename, String contentType, byte[] bytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Disposition: form-data; name=\"data\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        outputStream.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        outputStream.write(bytes);
        outputStream.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return outputStream.toByteArray();
    }

    private String resolveFilename(String imageUrl, HttpHeaders headers) {
        String path = URI.create(imageUrl).getPath();
        String candidate = path == null ? "" : path.substring(path.lastIndexOf('/') + 1).trim();
        if (!candidate.isBlank()) {
            return candidate;
        }
        String contentType = headers.firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase(Locale.ROOT).contains("png")) {
            return "broadcast.png";
        }
        if (contentType.toLowerCase(Locale.ROOT).contains("webp")) {
            return "broadcast.webp";
        }
        return "broadcast.jpg";
    }

    private String resolveContentType(HttpHeaders headers, String filename) {
        String headerValue = headers.firstValue("Content-Type").orElse("").trim();
        if (!headerValue.isBlank()) {
            return headerValue;
        }
        return MediaTypeFactory.getMediaType(filename)
                .map(MediaType::toString)
                .orElse("image/jpeg");
    }

    private String firstText(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode current = node;
            boolean missing = false;
            for (String segment : path.split("\\.")) {
                if (segment.matches("\\d+")) {
                    int index = Integer.parseInt(segment);
                    if (!current.isArray() || current.size() <= index) {
                        missing = true;
                        break;
                    }
                    current = current.get(index);
                } else {
                    current = current.path(segment);
                }
                if (current.isMissingNode() || current.isNull()) {
                    missing = true;
                    break;
                }
            }
            if (!missing) {
                String value = current.asText("").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
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
