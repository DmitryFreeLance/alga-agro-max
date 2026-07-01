package ru.algaagro.maxapp.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.BroadcastLog;
import ru.algaagro.maxapp.repository.BroadcastLogRepository;

@Service
public class BroadcastService {

    private final BroadcastLogRepository broadcastLogRepository;
    private final UserService userService;
    private final MaxApiClient maxApiClient;

    public BroadcastService(
            BroadcastLogRepository broadcastLogRepository,
            UserService userService,
            MaxApiClient maxApiClient
    ) {
        this.broadcastLogRepository = broadcastLogRepository;
        this.userService = userService;
        this.maxApiClient = maxApiClient;
    }

    @Transactional
    public Map<String, Object> sendBroadcast(String text, String imageUrl) {
        String normalizedText = text == null ? "" : text.trim();
        String normalizedImageUrl = imageUrl == null ? "" : imageUrl.trim();
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("Текст рассылки обязателен");
        }
        List<Map<String, Object>> attachments = new ArrayList<>();
        Map<String, Object> imageAttachment = maxApiClient.urlImageAttachment(normalizedImageUrl);
        if (imageAttachment != null) {
            attachments.add(imageAttachment);
        }
        List<Long> recipients = new ArrayList<>(userService.findAllUserIds());
        for (Long userId : recipients) {
            maxApiClient.sendToUser(userId, normalizedText, attachments.isEmpty() ? null : attachments, "html");
        }
        BroadcastLog log = new BroadcastLog();
        log.setText(normalizedText);
        log.setImageUrl(normalizedImageUrl);
        log.setRecipientsCount(recipients.size());
        log.setMessageType(normalizedImageUrl.isBlank() ? "text" : "photo");
        BroadcastLog saved = broadcastLogRepository.save(log);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.getId());
        response.put("recipientsCount", saved.getRecipientsCount());
        response.put("createdAt", saved.getCreatedAt());
        return response;
    }

    public Map<String, Object> getStats() {
        List<BroadcastLog> history = broadcastLogRepository.findTop20ByOrderByCreatedAtDesc();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("subscribersCount", userService.countUsers());
        stats.put("broadcastsCount", broadcastLogRepository.count());
        stats.put("newUsersThisMonth", userService.countUsersCreatedThisMonth());
        stats.put("lastBroadcastAt", history.isEmpty() ? null : history.get(0).getCreatedAt());
        return stats;
    }

    public List<Map<String, Object>> history() {
        return broadcastLogRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(item -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", item.getId());
                    dto.put("text", item.getText());
                    dto.put("imageUrl", item.getImageUrl());
                    dto.put("recipientsCount", item.getRecipientsCount());
                    dto.put("messageType", item.getMessageType());
                    dto.put("createdAt", item.getCreatedAt());
                    return dto;
                })
                .toList();
    }
}
