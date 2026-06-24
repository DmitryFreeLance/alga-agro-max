package ru.algaagro.maxapp.service;

import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.PostButton;

@Component
public class KeyboardFactory {

    private final AppProperties appProperties;

    public KeyboardFactory(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<Map<String, Object>> mainMenu(boolean admin) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(openAppButton("📦 Открыть каталог", appProperties.getMiniAppUrl())));
        rows.add(List.of(linkButton("💬 Связаться с менеджером", normalizeHttpLink(appProperties.getManagerContactUrl()))));
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
                List.of(messageButton("🔗 Кнопки постов"))
        );
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> importKeyboard() {
        return inlineKeyboard(List.of(
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

    public List<Map<String, Object>> postPreviewKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (PostButton button : postButtons) {
            rows.add(List.of(linkButton(button.getLabel(), button.getUrl())));
        }
        rows.add(List.of(messageButton("🚀 Опубликовать"), messageButton("🗑 Отменить")));
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> buttonsManagementKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(messageButton("➕ Добавить кнопку")));
        for (PostButton button : postButtons) {
            rows.add(List.of(messageButton("🗑 Удалить кнопку " + button.getId())));
        }
        rows.add(List.of(messageButton("🛠 Админка")));
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

    private String normalizeHttpLink(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (value.isBlank()) {
            return "https://max.ru/id27849376";
        }
        String normalized = value.replace("max://", "https://");
        String lowercase = normalized.toLowerCase(Locale.ROOT);
        if (lowercase.startsWith("http://") || lowercase.startsWith("https://")) {
            return normalized;
        }
        if (lowercase.startsWith("max.ru/")) {
            return "https://" + normalized;
        }
        return "https://max.ru/id27849376";
    }

    public List<Map<String, Object>> inlineKeyboard(List<List<Map<String, Object>>> buttons) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "inline_keyboard");
        attachment.put("payload", Map.of("buttons", buttons));
        return List.of(attachment);
    }
}
