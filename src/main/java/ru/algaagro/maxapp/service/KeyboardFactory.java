package ru.algaagro.maxapp.service;

import java.util.ArrayList;
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
        rows.add(List.of(openAppButton("🌿 Открыть каталог", appProperties.getMiniAppUrl())));
        if (admin) {
            rows.add(List.of(callbackButton("🛠 Админ-панель", "admin:menu")));
        }
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> adminMenu() {
        List<List<Map<String, Object>>> rows = List.of(
                List.of(callbackButton("📦 Номенклатура", "admin:import"), callbackButton("🧾 Заказы", "admin:orders:0")),
                List.of(callbackButton("👥 Пользователи", "admin:users:0"), callbackButton("📣 Пост", "admin:post")),
                List.of(callbackButton("🔗 Кнопки постов", "admin:buttons"), callbackButton("🏠 В меню", "menu:main"))
        );
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> importKeyboard() {
        return inlineKeyboard(List.of(
                List.of(callbackButton("✅ Готово", "import:done"), callbackButton("❌ Отмена", "flow:cancel"))
        ));
    }

    public List<Map<String, Object>> postMediaKeyboard() {
        return inlineKeyboard(List.of(
                List.of(callbackButton("✅ Готово", "post:media:done"), callbackButton("❌ Отмена", "flow:cancel"))
        ));
    }

    public List<Map<String, Object>> postPreviewKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (PostButton button : postButtons) {
            rows.add(List.of(linkButton(button.getLabel(), button.getUrl())));
        }
        rows.add(List.of(callbackButton("🚀 Опубликовать", "post:publish"), callbackButton("🗑 Отменить", "post:cancel")));
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> buttonsManagementKeyboard(List<PostButton> postButtons) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(callbackButton("➕ Добавить кнопку", "buttons:add"), callbackButton("🔙 Назад", "admin:menu")));
        for (PostButton button : postButtons) {
            rows.add(List.of(callbackButton("🗑 " + button.getLabel(), "buttons:delete:" + button.getId())));
        }
        return inlineKeyboard(rows);
    }

    public List<Map<String, Object>> pager(String prefix, int currentPage, boolean hasPrev, boolean hasNext) {
        List<Map<String, Object>> row = new ArrayList<>();
        if (hasPrev) {
            row.add(callbackButton("⬅️", prefix + ":" + (currentPage - 1)));
        }
        row.add(callbackButton("🔙 Админка", "admin:menu"));
        if (hasNext) {
            row.add(callbackButton("➡️", prefix + ":" + (currentPage + 1)));
        }
        return inlineKeyboard(List.of(row));
    }

    public Map<String, Object> callbackButton(String text, String payload) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "callback");
        button.put("text", text);
        button.put("payload", payload);
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

    public List<Map<String, Object>> inlineKeyboard(List<List<Map<String, Object>>> buttons) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "inline_keyboard");
        attachment.put("payload", Map.of("buttons", buttons));
        return List.of(attachment);
    }
}
