package ru.algaagro.maxapp.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.algaagro.maxapp.config.AppProperties;

@Service
public class FileStorageService {

    private final Path storageRoot;
    private final String publicBaseUrl;

    public FileStorageService(AppProperties appProperties) {
        this.storageRoot = Path.of(appProperties.getStorageDir()).toAbsolutePath().normalize();
        this.publicBaseUrl = normalizeBaseUrl(appProperties.getPublicBaseUrl());
    }

    public Map<String, Object> store(String scope, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Файл не был загружен");
        }
        try {
            String safeScope = scope == null || scope.isBlank() ? "misc" : scope.trim();
            String originalName = file.getOriginalFilename() == null ? "file" : Path.of(file.getOriginalFilename()).getFileName().toString();
            String extension = extractExtension(originalName);
            String storedName = Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8) + extension;
            Path targetDir = storageRoot.resolve(safeScope);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(storedName);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetFile);
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("scope", safeScope);
            dto.put("storedName", storedName);
            dto.put("originalName", originalName);
            dto.put("contentType", file.getContentType());
            dto.put("size", file.getSize());
            String relativeUrl = "/api/files/" + URLEncoder.encode(safeScope, StandardCharsets.UTF_8)
                    + "/" + URLEncoder.encode(storedName, StandardCharsets.UTF_8)
                    + "?filename=" + URLEncoder.encode(originalName, StandardCharsets.UTF_8);
            dto.put("downloadUrl", publicBaseUrl + relativeUrl);
            return dto;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить файл", e);
        }
    }

    public Resource loadAsResource(String scope, String storedName) {
        Path path = storageRoot.resolve(scope).resolve(storedName).normalize();
        if (!path.startsWith(storageRoot) || !Files.exists(path)) {
            throw new IllegalArgumentException("Файл не найден");
        }
        return new FileSystemResource(path);
    }

    private String extractExtension(String originalName) {
        int dotIndex = originalName.lastIndexOf('.');
        return dotIndex >= 0 ? originalName.substring(dotIndex) : "";
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
