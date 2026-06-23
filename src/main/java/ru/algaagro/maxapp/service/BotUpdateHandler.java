package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.AppUser;
import ru.algaagro.maxapp.model.BotSession;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.model.PostButton;
import ru.algaagro.maxapp.model.SessionState;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class BotUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(BotUpdateHandler.class);

    private final UserService userService;
    private final BotSessionService botSessionService;
    private final KeyboardFactory keyboardFactory;
    private final MaxApiClient maxApiClient;
    private final PostButtonService postButtonService;
    private final ExcelImportService excelImportService;
    private final OrderService orderService;
    private final AppProperties appProperties;

    public BotUpdateHandler(
            UserService userService,
            BotSessionService botSessionService,
            KeyboardFactory keyboardFactory,
            MaxApiClient maxApiClient,
            PostButtonService postButtonService,
            ExcelImportService excelImportService,
            OrderService orderService,
            AppProperties appProperties
    ) {
        this.userService = userService;
        this.botSessionService = botSessionService;
        this.keyboardFactory = keyboardFactory;
        this.maxApiClient = maxApiClient;
        this.postButtonService = postButtonService;
        this.excelImportService = excelImportService;
        this.orderService = orderService;
        this.appProperties = appProperties;
    }

    public void handle(JsonNode update) {
        String type = update.path("update_type").asText(update.path("type").asText(""));
        Long userId = extractUserId(update);
        Long chatId = extractChatId(update);
        if (userId == null) {
            log.warn("Skip MAX update without user id. type={}, payload={}", type, update.toString());
            return;
        }
        AppUser user = userService.touchUser(userId, extractDisplayName(update), extractUsername(update));
        String callbackPayload = extractCallbackPayload(update);
        String callbackId = extractCallbackId(update);
        if ("message_callback".equals(type) || type.contains("callback") || callbackPayload != null || callbackId != null) {
            log.info("Received callback update. type={}, userId={}, payload={}", type, userId, callbackPayload);
            handleCallback(update, user, chatId);
            return;
        }
        if ("bot_started".equals(type)) {
            sendWelcome(user.getMaxUserId(), user.isAdmin());
            return;
        }
        if ("message_created".equals(type)) {
            handleMessage(update, user, chatId);
        }
    }

    private void handleMessage(JsonNode update, AppUser user, Long chatId) {
        String text = extractText(update);
        if (isStartCommand(text)) {
            botSessionService.reset(user.getMaxUserId());
            sendWelcome(user.getMaxUserId(), user.isAdmin());
            return;
        }

        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        if (user.isAdmin() && handleAdminTextCommand(text, user, session)) {
            return;
        }
        if (session.getState() == SessionState.IMPORT_WAITING_FILES && user.isAdmin()) {
            if (captureImportFile(update, session)) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "📥 Файл добавлен. Можете отправить еще Excel или нажать «Готово».",
                        keyboardFactory.importKeyboard(),
                        "html");
            } else {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "Я жду Excel-файл. Отправьте документ `.xlsx`, затем нажмите «Готово».",
                        keyboardFactory.importKeyboard(),
                        "html");
            }
            return;
        }

        if (session.getState() == SessionState.POST_WAITING_MEDIA && user.isAdmin()) {
            if (capturePostMedia(update, session)) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "🎞 Медиа добавлено. Можно отправить еще материалы или нажать «Готово».",
                        keyboardFactory.postMediaKeyboard(),
                        "html");
            } else {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "Я жду фото, видео или медиагруппу для поста.",
                        keyboardFactory.postMediaKeyboard(),
                        "html");
            }
            return;
        }

        if (session.getState() == SessionState.POST_WAITING_TEXT && user.isAdmin()) {
            if (text == null || text.isBlank()) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "✍️ Теперь нужен текст поста. Можно в несколько строк.",
                        null,
                        "html");
                return;
            }
            Map<String, Object> payload = botSessionService.getPayload(session);
            payload.put("text", text);
            botSessionService.update(session, SessionState.IDLE, payload);
            sendPostPreview(user.getMaxUserId(), payload);
            return;
        }

        if (session.getState() == SessionState.BUTTON_WAITING_TEXT && user.isAdmin()) {
            handleButtonDraftMessage(user, session, text);
            return;
        }

        if (user.isAdmin() && text != null && text.startsWith("/addbutton ")) {
            String[] parts = text.substring("/addbutton ".length()).split("\\|", 2);
            if (parts.length == 2) {
                postButtonService.createButton(parts[0].trim(), parts[1].trim());
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "🔗 Кнопка добавлена. Теперь она будет прикрепляться к предпросмотру постов.",
                        keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                        "html");
                return;
            }
        }

        if (user.isAdmin() && text != null && text.startsWith("/grant ")) {
            String idText = text.substring("/grant ".length()).trim();
            try {
                long targetUserId = Long.parseLong(idText);
                boolean granted = userService.grantAdmin(targetUserId);
                maxApiClient.sendToUser(user.getMaxUserId(),
                        granted ? "⭐ Права администратора выданы." : "Пользователь еще не запускал бота.",
                        keyboardFactory.adminMenu(),
                        "html");
            } catch (NumberFormatException e) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "Неверный формат. Используйте `/grant 123456`.",
                        keyboardFactory.adminMenu(),
                        "markdown");
            }
            return;
        }

        if (user.isAdmin() && text != null && text.startsWith("/addbutton")) {
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Формат команды: `/addbutton Название | https://example.com`",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "markdown");
            return;
        }

        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                🌾 <b>ООО «АЛГА АГРО»</b>

                Это официальный бот компании: каталог, подбор товаров по культурам, оформление заказа и быстрые публикации для команды.
                Чтобы перейти в каталог, воспользуйтесь системной кнопкой мини-приложения внизу слева внутри MAX.
                Нажмите «Начать» в любой момент, чтобы вернуться в главное меню.
                """,
                keyboardFactory.mainMenu(user.isAdmin()),
                "html");
    }

    private boolean handleAdminTextCommand(String text, AppUser user, BotSession session) {
        String normalized = TextUtils.normalizeToken(text);
        if (normalized.isBlank()) {
            return false;
        }
        switch (normalized) {
            case "админ панель" -> {
                openAdminMenu(user);
                return true;
            }
            case "номенклатура" -> {
                startImportFlow(user, null);
                return true;
            }
            case "заказы" -> {
                showOrders(user, 0);
                return true;
            }
            case "пользователи" -> {
                showUsers(user, 0);
                return true;
            }
            case "пост" -> {
                startPostFlow(user, null);
                return true;
            }
            case "кнопки постов" -> {
                showButtons(user, null);
                return true;
            }
            case "в меню", "назад" -> {
                sendWelcome(user.getMaxUserId(), true);
                return true;
            }
            case "добавить кнопку" -> {
                startButtonFlow(user, null);
                return true;
            }
            case "готово" -> {
                if (session.getState() == SessionState.IMPORT_WAITING_FILES) {
                    runImport(user, null);
                    return true;
                }
                if (session.getState() == SessionState.POST_WAITING_MEDIA) {
                    askPostText(user, null);
                    return true;
                }
                return false;
            }
            case "отмена", "отменить" -> {
                cancelFlow(user, null);
                return true;
            }
            case "опубликовать" -> {
                publishPost(user, null);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean captureImportFile(JsonNode update, BotSession session) {
        List<Map<String, Object>> attachments = extractAttachments(update);
        List<Map<String, Object>> filePayload = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            String type = String.valueOf(attachment.getOrDefault("type", ""));
            Map<String, Object> payload = castMap(attachment.get("payload"));
            String fileName = firstNonBlank(payload, "file_name", "name", "title");
            String url = firstNonBlank(payload, "url", "download_url", "file_url");
            if ("file".equals(type) && fileName != null && fileName.toLowerCase().endsWith(".xlsx") && url != null) {
                filePayload.add(Map.of("name", fileName, "url", url));
            }
        }
        if (filePayload.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> files = castList(payload.get("files"));
        files.addAll(filePayload);
        payload.put("files", files);
        botSessionService.update(session, SessionState.IMPORT_WAITING_FILES, payload);
        return true;
    }

    private boolean capturePostMedia(JsonNode update, BotSession session) {
        List<Map<String, Object>> attachments = extractAttachments(update);
        if (attachments.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> media = castList(payload.get("media"));
        media.addAll(attachments);
        payload.put("media", media);
        botSessionService.update(session, SessionState.POST_WAITING_MEDIA, payload);
        return true;
    }

    private void handleCallback(JsonNode update, AppUser user, Long chatId) {
        String callbackPayload = extractCallbackPayload(update);
        String callbackId = extractCallbackId(update);
        if (callbackPayload == null) {
            log.warn("Callback without payload for userId={}, update={}", user.getMaxUserId(), update.toString());
            maxApiClient.answerCallback(callbackId, "Не удалось распознать действие кнопки");
            return;
        }

        if ("menu:main".equals(callbackPayload)) {
            sendWelcome(user.getMaxUserId(), user.isAdmin());
            maxApiClient.answerCallback(callbackId, "Главное меню открыто");
            return;
        }

        if ("admin:menu".equals(callbackPayload) && user.isAdmin()) {
            openAdminMenu(user);
            maxApiClient.answerCallback(callbackId, "Админка открыта");
            return;
        }

        if (!user.isAdmin() && callbackPayload.startsWith("admin:")) {
            maxApiClient.answerCallback(callbackId, "Эта кнопка доступна только администраторам");
            return;
        }

        switch (callbackPayload) {
            case "admin:import" -> startImportFlow(user, callbackId);
            case "admin:post" -> startPostFlow(user, callbackId);
            case "import:done" -> runImport(user, callbackId);
            case "post:media:done" -> askPostText(user, callbackId);
            case "post:publish" -> publishPost(user, callbackId);
            case "post:cancel", "flow:cancel" -> cancelFlow(user, callbackId);
            case "admin:buttons" -> showButtons(user, callbackId);
            case "buttons:add" -> explainButtonCommand(user, callbackId);
            default -> {
                log.info("Dispatch dynamic callback. userId={}, payload={}", user.getMaxUserId(), callbackPayload);
                handleDynamicCallback(callbackPayload, callbackId, user, chatId);
            }
        }
    }

    private void handleDynamicCallback(String payload, String callbackId, AppUser user, Long chatId) {
        if (payload.startsWith("admin:users:")) {
            int page = parseTailNumber(payload, "admin:users:");
            showUsers(user, page);
            maxApiClient.answerCallback(callbackId, "Пользователи обновлены");
            return;
        }
        if (payload.startsWith("admin:orders:")) {
            int page = parseTailNumber(payload, "admin:orders:");
            showOrders(user, page);
            maxApiClient.answerCallback(callbackId, "Заказы обновлены");
            return;
        }
        if (payload.startsWith("buttons:delete:")) {
            long id = Long.parseLong(payload.substring("buttons:delete:".length()));
            boolean deleted = postButtonService.deleteButton(id);
            maxApiClient.sendToUser(user.getMaxUserId(),
                    deleted ? "🗑 Кнопка удалена." : "Кнопка не найдена.",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            maxApiClient.answerCallback(callbackId, deleted ? "Удалено" : "Не найдено");
            return;
        }
        if (payload.startsWith("admin:grant:")) {
            long targetUserId = Long.parseLong(payload.substring("admin:grant:".length()));
            boolean granted = userService.grantAdmin(targetUserId);
            maxApiClient.sendToUser(user.getMaxUserId(),
                    granted ? "⭐ Права администратора выданы." : "Пользователь еще не запускал бота.",
                    keyboardFactory.adminMenu(),
                    "html");
            maxApiClient.answerCallback(callbackId, granted ? "Готово" : "Не найдено");
        }
    }

    private void startImportFlow(AppUser user, String callbackId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("files", new ArrayList<>());
        botSessionService.update(botSessionService.getOrCreate(user.getMaxUserId()), SessionState.IMPORT_WAITING_FILES, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                📦 <b>Загрузка номенклатуры</b>

                Отправьте один или несколько Excel-файлов `.xlsx`.
                Когда закончите, нажмите «Готово». После этого бот отправит строки в ИИ:
                сначала в Kie.ai GPT-5.5, а если ответ не успеет прийти за 150 секунд, автоматически переключится на Gemini 3.5 Flash.
                """,
                keyboardFactory.importKeyboard(),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду Excel-файлы");
    }

    private void runImport(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> files = castList(payload.get("files"));
        if (files.isEmpty()) {
            maxApiClient.answerCallback(callbackId, "Сначала загрузите хотя бы один Excel-файл");
            return;
        }
        var job = excelImportService.createJob(user.getMaxUserId(), files);
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🤖 Импорт запущен. Анализирую Excel и автоматически расставляю фильтры по культурам, категориям и назначению.",
                keyboardFactory.adminMenu(),
                "html");
        excelImportService.processAsync(job,
                () -> maxApiClient.sendToUser(user.getMaxUserId(), "✅ " + job.getSummary(), keyboardFactory.adminMenu(), "html"),
                error -> maxApiClient.sendToUser(user.getMaxUserId(),
                        "⚠️ ИИ не удалось распознать, попробуйте позже.\n\nТехническая заметка: " + TextUtils.trimTo(error, 700),
                        keyboardFactory.adminMenu(),
                        "html"));
        maxApiClient.answerCallback(callbackId, "Импорт запущен");
    }

    private void startPostFlow(AppUser user, String callbackId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("media", new ArrayList<>());
        botSessionService.update(botSessionService.getOrCreate(user.getMaxUserId()), SessionState.POST_WAITING_MEDIA, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                📣 <b>Подготовка поста</b>

                Отправьте фото, видео или медиагруппу. Можно несколькими сообщениями.
                Когда закончите, нажмите «Готово», и я попрошу текст поста.
                """,
                keyboardFactory.postMediaKeyboard(),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду медиа");
    }

    private void askPostText(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> media = castList(payload.get("media"));
        if (media.isEmpty()) {
            maxApiClient.answerCallback(callbackId, "Сначала добавьте медиа для поста");
            return;
        }
        botSessionService.update(session, SessionState.POST_WAITING_TEXT, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                "✍️ Отлично. Теперь отправьте текст поста одним сообщением.",
                null,
                "html");
        maxApiClient.answerCallback(callbackId, "Жду текст");
    }

    private void publishPost(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> media = castList(payload.get("media"));
        String text = (String) payload.get("text");
        Long finalTarget = appProperties.getMax().getPostTargetChatId();
        if (finalTarget == null) {
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "⚠️ Для публикации нужно указать `MAX_POST_TARGET_CHAT_ID` в окружении контейнера.",
                    keyboardFactory.adminMenu(),
                    "html");
            maxApiClient.answerCallback(callbackId, "Не настроен chat_id для публикации");
            return;
        }
        List<Map<String, Object>> attachments = new ArrayList<>(media);
        List<PostButton> postButtons = postButtonService.getActiveButtons();
        if (!postButtons.isEmpty()) {
            attachments.addAll(keyboardFactory.inlineKeyboard(List.of(
                    postButtons.stream()
                            .map(button -> keyboardFactory.linkButton(button.getLabel(), button.getUrl()))
                            .toList()
            )));
        }
        maxApiClient.sendToChat(finalTarget, text, attachments, "html");
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🚀 Пост опубликован.",
                keyboardFactory.adminMenu(),
                "html");
        maxApiClient.answerCallback(callbackId, "Пост отправлен");
    }

    private void cancelFlow(AppUser user, String callbackId) {
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "Отменил текущий сценарий и вернул вас в админ-панель.",
                keyboardFactory.adminMenu(),
                "html");
        maxApiClient.answerCallback(callbackId, "Отменено");
    }

    private void showButtons(AppUser user, String callbackId) {
        List<PostButton> buttons = postButtonService.getActiveButtons();
        StringBuilder text = new StringBuilder("🔗 <b>Постоянные кнопки постов</b>\n\n");
        if (buttons.isEmpty()) {
            text.append("Пока ни одной кнопки не добавлено.\n");
        } else {
            buttons.forEach(button -> text.append("• ").append(button.getLabel()).append(" — ").append(button.getUrl()).append("\n"));
        }
        text.append("\nЧтобы быстро добавить кнопку, отправьте команду:\n<code>/addbutton Группа | https://max.ru/...</code>");
        maxApiClient.sendToUser(user.getMaxUserId(),
                text.toString(),
                keyboardFactory.buttonsManagementKeyboard(buttons),
                "html");
        maxApiClient.answerCallback(callbackId, "Список кнопок открыт");
    }

    private void explainButtonCommand(AppUser user, String callbackId) {
        startButtonFlow(user, callbackId);
    }

    private void showUsers(AppUser user, int page) {
        Page<AppUser> users = userService.listUsers(page, 8);
        StringBuilder text = new StringBuilder("👥 <b>Пользователи</b>\n\n");
        users.getContent().forEach(item -> text.append("• <b>").append(item.getDisplayName()).append("</b>")
                .append(item.isAdmin() ? " ⭐" : "")
                .append("\nID: <code>").append(item.getMaxUserId()).append("</code>\n")
                .append(item.getUsername() == null ? "" : "@" + item.getUsername() + "\n")
                .append("\n"));
        text.append("Страница ").append(page + 1).append(" из ").append(Math.max(users.getTotalPages(), 1))
                .append("\nВсего пользователей: ").append(userService.countUsers())
                .append("\n\nДля выдачи прав можно нажать кнопку у пользователя или отправить <code>/grant ID</code>.");
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (AppUser item : users.getContent()) {
            if (!item.isAdmin()) {
                rows.add(List.of(keyboardFactory.callbackButton("⭐ " + TextUtils.trimTo(item.getDisplayName(), 22), "admin:grant:" + item.getMaxUserId())));
            }
        }
        rows.add(List.of(keyboardFactory.callbackButton("⬅️", "admin:users:" + Math.max(page - 1, 0)),
                keyboardFactory.callbackButton("🔙 Админка", "admin:menu"),
                keyboardFactory.callbackButton("➡️", "admin:users:" + Math.min(page + 1, Math.max(users.getTotalPages() - 1, 0)))));
        maxApiClient.sendToUser(user.getMaxUserId(), text.toString(), keyboardFactory.inlineKeyboard(rows), "html");
    }

    private void showOrders(AppUser user, int page) {
        Page<CatalogOrder> orders = orderService.listOrders(page, 6);
        StringBuilder text = new StringBuilder("🧾 <b>Заказы</b>\n\n");
        orders.getContent().forEach(order -> text.append("• <b>").append(order.getPublicCode()).append("</b>")
                .append(" — ").append(order.getCustomerName())
                .append("\n").append(order.getCustomerPhone())
                .append("\nПозиций: ").append(order.getItems().size())
                .append(", сумма: ").append(TextUtils.formatPrice(order.getTotalPrice()))
                .append("\n\n"));
        if (orders.isEmpty()) {
            text.append("Заказов пока нет.");
        }
        text.append("\nСтраница ").append(page + 1).append(" из ").append(Math.max(orders.getTotalPages(), 1))
                .append("\nВсего заказов: ").append(orderService.countOrders());
        maxApiClient.sendToUser(user.getMaxUserId(),
                text.toString(),
                keyboardFactory.pager("admin:orders", page, page > 0, page + 1 < orders.getTotalPages()),
                "html");
    }

    private void sendPostPreview(Long userId, Map<String, Object> payload) {
        List<Map<String, Object>> attachments = new ArrayList<>(castList(payload.get("media")));
        attachments.addAll(keyboardFactory.postPreviewKeyboard(postButtonService.getActiveButtons()));
        maxApiClient.sendToUser(userId,
                "👀 <b>Предпросмотр поста</b>\n\n" + payload.getOrDefault("text", ""),
                attachments,
                "html");
    }

    private void sendWelcome(Long userId, boolean admin) {
        String text = """
                🌿 <b>Официальный бот</b>
                <b>ООО «АЛГА АГРО»</b>

                Здесь мы собрали удобный каталог для агро-товаров: семена, средства защиты, питание и сопутствующие позиции с быстрым подбором по культурам.
                """;
        maxApiClient.sendToUser(userId, text, keyboardFactory.mainMenu(admin), "html");
    }

    private void openAdminMenu(AppUser user) {
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🛠 <b>Админ-панель АЛГА АГРО</b>\nВыберите нужный раздел ниже.",
                keyboardFactory.adminMenu(),
                "html");
    }

    private void startButtonFlow(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        botSessionService.update(session, SessionState.BUTTON_WAITING_TEXT, new HashMap<>());
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                🔗 <b>Новая кнопка для постов</b>

                Отправьте текст в формате:
                <code>Название кнопки - https://example.com</code>
                """,
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду текст кнопки");
    }

    private void handleButtonDraftMessage(AppUser user, BotSession session, String text) {
        if (text == null || text.isBlank()) {
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Нужен текст в формате:\n<code>Название кнопки - https://example.com</code>",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }
        String[] parts = text.split("\\s+-\\s+", 2);
        if (parts.length != 2 || parts[0].isBlank() || !isValidUrl(parts[1])) {
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Не получилось распознать формат. Отправьте так:\n<code>Группа - https://max.ru/...</code>",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }
        postButtonService.createButton(parts[0].trim(), parts[1].trim());
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "✅ Кнопка добавлена.",
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
    }

    private boolean isValidUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.getScheme() != null && (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isStartCommand(String text) {
        if (text == null) {
            return false;
        }
        String normalized = TextUtils.normalizeToken(text);
        return normalized.equals("/start") || normalized.equals("start") || normalized.equals("начать");
    }

    private Long extractUserId(JsonNode update) {
        return firstLong(update,
                "/message/sender/user_id",
                "/message/body/sender/user_id",
                "/callback/user/user_id",
                "/callback/user_id",
                "/callback/sender/user_id",
                "/sender/user_id",
                "/user/user_id",
                "/message/user/user_id");
    }

    private Long extractChatId(JsonNode update) {
        return firstLong(update, "/message/chat_id", "/chat_id", "/callback/chat_id", "/callback/message/chat_id");
    }

    private String extractDisplayName(JsonNode update) {
        return firstText(update,
                "/message/sender/name",
                "/callback/user/name",
                "/user/name");
    }

    private String extractUsername(JsonNode update) {
        return firstText(update,
                "/message/sender/username",
                "/callback/user/username",
                "/user/username");
    }

    private String extractText(JsonNode update) {
        return firstText(update, "/message/body/text", "/message/text", "/text");
    }

    private String extractCallbackPayload(JsonNode update) {
        return firstText(update, "/callback/payload", "/message_callback/payload", "/payload");
    }

    private String extractCallbackId(JsonNode update) {
        return firstText(update, "/callback/callback_id", "/callback/id", "/message_callback/callback_id");
    }

    private List<Map<String, Object>> extractAttachments(JsonNode update) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        JsonNode node = update.at("/message/body/attachments");
        if (!node.isArray()) {
            node = update.at("/message/attachments");
        }
        if (node.isArray()) {
            node.forEach(item -> attachments.add(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(item, Map.class)));
        }
        return attachments;
    }

    private String firstText(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (!node.isMissingNode() && !node.isNull()) {
                String value = node.asText();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private Long firstLong(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (!node.isMissingNode() && node.canConvertToLong()) {
                return node.longValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    private String firstNonBlank(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private int parseTailNumber(String payload, String prefix) {
        try {
            return Integer.parseInt(payload.substring(prefix.length()));
        } catch (Exception e) {
            log.warn("Cannot parse callback page from {}", payload);
            return 0;
        }
    }
}
