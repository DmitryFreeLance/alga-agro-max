package ru.algaagro.maxapp.service;

import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.PostButton;

@Component
public class KeyboardFactory {

    private static final String CHANNEL_URL = "https://max.ru/join/RROKM3Kboyx-q5p3j1kg68ZKY3hUw3RFZKYbZo6Yjxg";

    private final AppProperties appProperties;

    public KeyboardFactory(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<Map<String, Object>> mainMenu(Long userId, boolean admin) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(linkButton("📢 Канал", CHANNEL_URL)));
        rows.add(List.of(linkButton("📋 Открыть каталог", buildMiniAppUrl(userId))));
        rows.add(List.of(messageButton("💬 Связаться с менеджером")));
        if (admin) {
            rows.add(List.of(messageButton("🛠 Админ панель")));
        }
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> adminMenu() {
        List<List<Map<String, Object>>> rows = List.of(
                List.of(messageButton("📦 Номенклатура")),
                List.of(messageButton("👥 Пользователи")),
                List.of(messageButton("🧾 Заказы"), messageButton("📣 Пост")),
                List.of(messageButton("📨 Рассылка")),
                List.of(messageButton("🔗 Кнопки постов")),
                List.of(messageButton("🏠 В меню"))
        );
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> menuKeyboard() {
        return inlineKeyboard(List.of(
                List.of(messageButton("🏠 В меню"))
        ));
    }

    public List<Map<String, Object>> importKeyboard(String importMode) {
        boolean fullFileMode = "full_file_kie".equalsIgnoreCase(importMode);
        return inlineKeyboard(List.of(
                List.of(callbackButton(fullFileMode ? "○ Как сейчас" : "● Как сейчас", "import:mode:hybrid")),
                List.of(callbackButton(fullFileMode ? "● Полная отправка в KIE" : "○ Полная отправка в KIE", "import:mode:full_file_kie")),
                List.of(messageButton("✅ Готово"), messageButton("❌ Отмена"))
        ));
    }

    public List<Map<String, Object>> importPreviewKeyboard() {
        return inlineKeyboard(List.of(
                List.of(messageButton("✅ Подтвердить импорт"), messageButton("❌ Отменить импорт"))
        ));
    }

    public List<Map<String, Object>> postMediaKeyboard() {
        return inlineKeyboard(List.of(
                List.of(messageButton("✅ Готово"), messageButton("❌ Отмена"))
        ));
    }

    public List<Map<String, Object>> broadcastTextKeyboard() {
        return inlineKeyboard(List.of(
                List.of(messageButton("✅ Готово"), messageButton("❌ Отмена"))
        ));
    }

    public List<Map<String, Object>> postPreviewKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (PostButton button : postButtons) {
            rows.add(List.of(linkButton(button.getLabel(), button.getUrl())));
        }
        rows.add(List.of(messageButton("🚀 Опубликовать"), messageButton("🗑 Отменить")));
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> broadcastPreviewKeyboard() {
        return inlineKeyboard(List.of(
                List.of(messageButton("📨 Отправить всем"), messageButton("🗑 Отменить"))
        ));
    }

    public List<Map<String, Object>> buttonsManagementKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(messageButton("➕ Добавить кнопку"), messageButton("🗑 Удалить кнопку")));
        rows.add(List.of(messageButton("🛠 Админка")));
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> buttonsDeleteKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (PostButton button : postButtons) {
            if (button.getId() != null && !isBuiltInPostButton(button)) {
                rows.add(List.of(messageButton("🗑 Удалить кнопку " + button.getId())));
            }
        }
        rows.add(List.of(messageButton("🔗 Кнопки постов"), messageButton("🛠 Админка")));
        return inlineKeyboard(rows);
    }

    private boolean isBuiltInPostButton(PostButton button) {
        return PostButtonService.DEFAULT_CATALOG_LABEL.equals(button.getLabel())
                || PostButtonService.DEFAULT_SUGAR_BEET_LABEL.equals(button.getLabel());
    }

    public List<Map<String, Object>> buttonDraftPreviewKeyboard(String label, String url) {
        return inlineKeyboard(List.of(
                List.of(linkButton(label, url)),
                List.of(messageButton("✅ Сохранить кнопку"), messageButton("❌ Отмена"))
        ));
    }

    public List<Map<String, Object>> buttonTargetKeyboard() {
        return inlineKeyboard(List.of(
                List.of(callbackButton("🤖 Этот бот", "buttons:target:self_bot")),
                List.of(callbackButton("📱 Мини-апп", "buttons:target:mini_app")),
                List.of(callbackButton("💬 Менеджер", "buttons:target:manager")),
                List.of(callbackButton("✍️ Ввести вручную", "buttons:target:manual")),
                List.of(callbackButton("❌ Отмена", "flow:cancel"))
        ));
    }

    public List<Map<String, Object>> researchKeyboard(String scope, boolean hasMore) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        String normalizedScope = scope == null || scope.isBlank() ? "research:cultures" : scope.trim();
        if (hasMore) {
            rows.add(List.of(
                    callbackButton("▶️ Продолжить до конца", normalizedScope + ":continue"),
                    callbackButton("⏹ Остановить", normalizedScope + ":stop")
            ));
        } else {
            rows.add(List.of(callbackButton("⏹ Остановить", normalizedScope + ":stop")));
        }
        return inlineKeyboard(rows);
    }

    public Map<String, Object> callbackButton(String text, String payload) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "callback");
        button.put("text", text);
        button.put("payload", payload);
        return button;
    }

    public Map<String, Object> messageButton(String text) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "message");
        button.put("text", text);
        return button;
    }

    public Map<String, Object> linkButton(String text, String url) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "link");
        button.put("text", text);
        button.put("url", url);
        return button;
    }

    public Map<String, Object> openAppButton(String text, String url) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "open_app");
        button.put("text", text);
        button.put("web_app", url);
        return button;
    }

    private String buildMiniAppUrl(Long userId) {
        String baseUrl = normalizeHttpLink(appProperties.getMiniAppUrl(), "https://algaagro.ru/miniapp/");
        if (userId == null || userId <= 0) {
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "maxUserId=" + URLEncoder.encode(String.valueOf(userId), StandardCharsets.UTF_8);
    }

    private String normalizeHttpLink(String rawUrl) {
        return normalizeHttpLink(rawUrl, "https://max.ru/id27849376");
    }

    private String normalizeHttpLink(String rawUrl, String fallbackUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isBlank()) {
            return fallbackUrl;
        }
        String normalized = value.replace("max://", "https://");
        String lowercase = normalized.toLowerCase(Locale.ROOT);
        if (lowercase.startsWith("http://") || lowercase.startsWith("https://")) {
            return normalized;
        }
        if (lowercase.startsWith("max.ru/")) {
            return "https://" + normalized;
        }
        return fallbackUrl;
    }

    public List<Map<String, Object>> inlineKeyboard(List<List<Map<String, Object>>> buttons) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "inline_keyboard");
        attachment.put("payload", Map.of("buttons", buttons));
        return List.of(attachment);
    }
}
