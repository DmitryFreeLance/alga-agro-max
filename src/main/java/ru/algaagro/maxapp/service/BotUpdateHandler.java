package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    private static final String IMPORT_MODE_KEY = "importMode";

    private final UserService userService;
    private final BotSessionService botSessionService;
    private final KeyboardFactory keyboardFactory;
    private final MaxApiClient maxApiClient;
    private final PostButtonService postButtonService;
    private final ExcelImportService excelImportService;
    private final ProductResearchService productResearchService;
    private final OrderService orderService;
    private final BroadcastService broadcastService;
    private final AppProperties appProperties;

    public BotUpdateHandler(
            UserService userService,
            BotSessionService botSessionService,
            KeyboardFactory keyboardFactory,
            MaxApiClient maxApiClient,
            PostButtonService postButtonService,
            ExcelImportService excelImportService,
            ProductResearchService productResearchService,
            OrderService orderService,
            BroadcastService broadcastService,
            AppProperties appProperties
    ) {
        this.userService = userService;
        this.botSessionService = botSessionService;
        this.keyboardFactory = keyboardFactory;
        this.maxApiClient = maxApiClient;
        this.postButtonService = postButtonService;
        this.excelImportService = excelImportService;
        this.productResearchService = productResearchService;
        this.orderService = orderService;
        this.broadcastService = broadcastService;
        this.appProperties = appProperties;
    }

    public void handle(JsonNode update) {
        String type = update.path("update_type").asText(update.path("type").asText(""));
        Long userId = extractUserId(update);
        Long chatId = extractChatId(update);
        String text = extractText(update);
        String callbackPayload = extractCallbackPayload(update);
        logUpdateContext(type, userId, chatId, text, callbackPayload, update);
        if (userId == null) {
            log.warn("Skip MAX update without user id. type={}, payload={}", type, update.toString());
            return;
        }
        AppUser user = userService.touchUser(userId, extractDisplayName(update), extractUsername(update));
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
        if ("message_created".equals(type) || "message_edited".equals(type)) {
            handleMessage(update, user, chatId);
        }
    }

    private void logUpdateContext(String type, Long userId, Long chatId, String text, String callbackPayload, JsonNode update) {
        int attachmentsCount = extractAttachments(update).size();
        if (chatId != null) {
            log.info("MAX update received: type={}, chatId={}, userId={}, text={}, callbackPayload={}, attachments={}",
                    safeLogValue(type),
                    chatId,
                    userId,
                    safeLogValue(shortenForLog(text)),
                    safeLogValue(shortenForLog(callbackPayload)),
                    attachmentsCount);
        } else {
            log.info("MAX update received: type={}, userId={}, text={}, callbackPayload={}, attachments={}",
                    safeLogValue(type),
                    userId,
                    safeLogValue(shortenForLog(text)),
                    safeLogValue(shortenForLog(callbackPayload)),
                    attachmentsCount);
        }
    }

    private String shortenForLog(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 157) + "...";
    }

    private String safeLogValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private void handleMessage(JsonNode update, AppUser user, Long chatId) {
        String text = extractText(update);
        if (isStartCommand(text)) {
            botSessionService.reset(user.getMaxUserId());
            sendWelcome(user.getMaxUserId(), user.isAdmin());
            return;
        }
        if (isManagerContactCommand(text)) {
            sendManagerContact(user.getMaxUserId(), user.isAdmin());
            return;
        }
        if (user.isAdmin() && text != null && text.trim().startsWith("/research")) {
            startProductsResearch(user);
            return;
        }
        if (user.isAdmin() && text != null && text.trim().startsWith("/stop")) {
            stopProductsResearch(user, null);
            return;
        }

        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        if (user.isAdmin() && handleAdminTextCommand(text, user, session)) {
            return;
        }
        if (session.getState() == SessionState.IMPORT_WAITING_FILES && user.isAdmin()) {
            if (captureImportFile(update, session)) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "📥 Файл добавлен. Можете отправить еще Excel / Word / PDF или нажать «Готово».",
                        keyboardFactory.importKeyboard(currentImportMode(session)),
                        "html");
            } else {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "Я жду файл номенклатуры. Отправьте документ `.xlsx`, `.xlsm`, `.xls`, `.docx`, `.doc` или `.pdf`, затем нажмите «Готово».",
                        keyboardFactory.importKeyboard(currentImportMode(session)),
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

        if (session.getState() == SessionState.BROADCAST_WAITING_MEDIA && user.isAdmin()) {
            if (capturePostMedia(update, session)) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "🖼 Медиа для рассылки добавлено. Можно отправить еще материалы или нажать «Готово».",
                        keyboardFactory.postMediaKeyboard(),
                        "html");
            } else {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "Я жду фото, видео или медиагруппу для рассылки.",
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

        if (session.getState() == SessionState.BROADCAST_WAITING_TEXT && user.isAdmin()) {
            if (text == null || text.isBlank()) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "✍️ Теперь нужен текст рассылки. Отправьте его сообщением, затем нажмите «Готово».",
                        keyboardFactory.broadcastTextKeyboard(),
                        "html");
                return;
            }
            Map<String, Object> payload = botSessionService.getPayload(session);
            payload.put("text", text);
            payload.put("mode", "broadcast_waiting");
            botSessionService.update(session, SessionState.BROADCAST_WAITING_TEXT, payload);
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "📝 Текст рассылки сохранен. Можно прислать новую версию или нажать «Готово» для предпросмотра.",
                    keyboardFactory.broadcastTextKeyboard(),
                    "html");
            return;
        }

        if (isButtonFlow(session) && user.isAdmin()) {
            handleButtonDraftMessage(user, session, update, text);
            return;
        }

        if (user.isAdmin() && text != null && text.startsWith("/grant ")) {
            String idText = text.substring("/grant ".length()).trim();
            try {
                long targetUserId = Long.parseLong(idText);
                boolean granted = userService.grantAdmin(targetUserId);
                if (granted) {
                    notifyNewAdmin(targetUserId);
                }
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

        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                🌾 <b>ООО «Алга Агро Групп»</b>

                Это официальный бот компании: каталог, подбор товаров по культурам, оформление заказа и быстрые публикации для команды.
                Чтобы перейти в каталог, воспользуйтесь системной кнопкой мини-приложения внизу слева внутри MAX.
                Нажмите «Начать» в любой момент, чтобы вернуться в главное меню.
                """,
                keyboardFactory.mainMenu(user.getMaxUserId(), user.isAdmin()),
                "html");
    }

    private boolean handleAdminTextCommand(String text, AppUser user, BotSession session) {
        String normalized = TextUtils.normalizeToken(text);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.equals("админ панель") || normalized.equals("админка")) {
            openAdminMenu(user);
            return true;
        }
        if (normalized.equals("номенклатура")) {
            startImportFlow(user, null);
            return true;
        }
        if (normalized.equals("пользователи")) {
            showUsers(user, 0);
            return true;
        }
        if (normalized.startsWith("пользователи ")) {
            Integer page = parseCommandPage(normalized, "пользователи");
            if (page != null) {
                showUsers(user, page);
                return true;
            }
        }
        if (normalized.startsWith("назначить админом ")) {
            Long target = parseCommandLong(normalized, "назначить админом ");
            if (target != null) {
                grantAdminFromText(user, target);
                return true;
            }
        }
        if (normalized.equals("заказы")) {
            showOrders(user, 0);
            return true;
        }
        if (normalized.startsWith("заказы ")) {
            Integer page = parseCommandPage(normalized, "заказы");
            if (page != null) {
                showOrders(user, page);
                return true;
            }
        }
        if (normalized.equals("пост")) {
            startPostFlow(user, null);
            return true;
        }
        if (normalized.equals("рассылка")) {
            startBroadcastFlow(user, null);
            return true;
        }
        if (normalized.equals("кнопки постов")) {
            showButtons(user, null);
            return true;
        }
        if (normalized.equals("добавить кнопку")) {
            notifyButtonCreationDisabled(user, null);
            return true;
        }
        if (normalized.equals("подтвердить импорт")) {
            if (isImportPreviewFlow(session)) {
                confirmImportPreview(user, session);
                return true;
            }
        }
        if (normalized.equals("отменить импорт")) {
            if (isImportPreviewFlow(session)) {
                cancelImportPreview(user, session);
                return true;
            }
        }
        if (normalized.startsWith("удалить кнопку ")) {
            Long buttonId = parseCommandLong(normalized, "удалить кнопку ");
            if (buttonId != null) {
                deleteButtonFromText(user, buttonId);
                return true;
            }
        }
        if (normalized.equals("готово")) {
            if (session.getState() == SessionState.IMPORT_WAITING_FILES) {
                runImport(user, null);
                return true;
            }
            if (session.getState() == SessionState.POST_WAITING_MEDIA) {
                askPostText(user, null);
                return true;
            }
            if (session.getState() == SessionState.BROADCAST_WAITING_MEDIA) {
                askBroadcastText(user, null);
                return true;
            }
            if (session.getState() == SessionState.BROADCAST_WAITING_TEXT) {
                if (hasBroadcastDraftText(session)) {
                    Map<String, Object> payload = botSessionService.getPayload(session);
                    botSessionService.update(session, SessionState.IDLE, payload);
                    sendBroadcastPreview(user.getMaxUserId(), payload);
                } else {
                    maxApiClient.sendToUser(user.getMaxUserId(),
                            "Сначала отправьте текст рассылки, а потом нажмите «Готово».",
                            keyboardFactory.broadcastTextKeyboard(),
                            "html");
                }
                return true;
            }
            return false;
        }
        if (normalized.equals("отмена") || normalized.equals("отменить")) {
            if (isImportPreviewFlow(session)) {
                cancelImportPreview(user, session);
                return true;
            }
            cancelFlow(user, null);
            return true;
        }
        if (normalized.equals("опубликовать")) {
            if (isBroadcastPreviewFlow(session)) {
                publishBroadcast(user, null);
                return true;
            }
            publishPost(user, null);
            return true;
        }
        if (normalized.equals("отправить всем")) {
            if (isBroadcastPreviewFlow(session)) {
                publishBroadcast(user, null);
                return true;
            }
        }
        return false;
    }

    private boolean captureImportFile(JsonNode update, BotSession session) {
        List<Map<String, Object>> attachments = extractAttachments(update);
        List<Map<String, Object>> filePayload = new ArrayList<>();
        for (Map<String, Object> attachment : attachments) {
            String type = String.valueOf(attachment.getOrDefault("type", ""));
            Map<String, Object> payload = castMap(attachment.get("payload"));
            Map<String, Object> file = castMap(attachment.get("file"));
            Map<String, Object> media = castMap(attachment.get("media"));
            String fileName = firstNonBlank(
                    attachment,
                    "file_name", "name", "title", "filename"
            );
            if (fileName == null) {
                fileName = firstNonBlank(payload, "file_name", "name", "title", "filename");
            }
            if (fileName == null) {
                fileName = firstNonBlank(file, "file_name", "name", "title", "filename");
            }
            if (fileName == null) {
                fileName = firstNonBlank(media, "file_name", "name", "title", "filename");
            }
            String mimeType = firstNonBlank(
                    payload,
                    "mime_type", "content_type", "type"
            );
            if (mimeType == null) {
                mimeType = firstNonBlank(attachment, "mime_type", "content_type");
            }
            if (mimeType == null) {
                mimeType = firstNonBlank(file, "mime_type", "content_type");
            }
            String url = firstNonBlank(attachment, "url", "download_url", "file_url", "downloadUrl", "fileUrl");
            if (url == null) {
                url = firstNonBlank(payload, "url", "download_url", "file_url", "downloadUrl", "fileUrl", "src");
            }
            if (url == null) {
                url = firstNonBlank(file, "url", "download_url", "file_url", "downloadUrl", "fileUrl", "src");
            }
            if (url == null) {
                url = firstNonBlank(media, "url", "download_url", "file_url", "downloadUrl", "fileUrl", "src");
            }
            boolean excelByName = fileName != null && (
                    fileName.toLowerCase().endsWith(".xlsx")
                            || fileName.toLowerCase().endsWith(".xlsm")
                            || fileName.toLowerCase().endsWith(".xls")
            );
            boolean excelByMime = mimeType != null && (
                    mimeType.toLowerCase().contains("spreadsheet")
                            || mimeType.toLowerCase().contains("excel")
                            || mimeType.toLowerCase().contains("sheet")
            );
            boolean pdfByName = fileName != null && fileName.toLowerCase().endsWith(".pdf");
            boolean pdfByMime = mimeType != null && mimeType.toLowerCase().contains("pdf");
            boolean wordByName = fileName != null && (
                    fileName.toLowerCase().endsWith(".docx")
                            || fileName.toLowerCase().endsWith(".doc")
            );
            boolean wordByMime = mimeType != null && (
                    mimeType.toLowerCase().contains("msword")
                            || mimeType.toLowerCase().contains("wordprocessingml")
                            || mimeType.toLowerCase().contains("officedocument.wordprocessingml")
            );
            boolean fileLikeType = type.equalsIgnoreCase("file") || type.equalsIgnoreCase("document") || type.isBlank();
            if (fileLikeType && (excelByName || excelByMime || pdfByName || pdfByMime || wordByName || wordByMime) && url != null) {
                filePayload.add(Map.of("name", fileName == null ? "import.xlsx" : fileName, "url", url));
                continue;
            }
            log.info("Skipped import attachment. type={}, fileName={}, mimeType={}, hasUrl={}, keys={}, payloadKeys={}",
                    type,
                    fileName,
                    mimeType,
                    url != null,
                    attachment.keySet(),
                    payload.keySet());
        }
        if (filePayload.isEmpty()) {
            log.info("No importable catalog attachment found. attachments={}, update={}", attachments, update.toString());
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
            case "admin:broadcast" -> startBroadcastFlow(user, callbackId);
            case "import:done" -> runImport(user, callbackId);
            case "post:media:done" -> askPostText(user, callbackId);
            case "post:publish" -> publishPost(user, callbackId);
            case "broadcast:publish" -> publishBroadcast(user, callbackId);
            case "post:cancel", "flow:cancel" -> cancelFlow(user, callbackId);
            case "admin:buttons" -> showButtons(user, callbackId);
            case "buttons:add" -> notifyButtonCreationDisabled(user, callbackId);
            default -> {
                log.info("Dispatch dynamic callback. userId={}, payload={}", user.getMaxUserId(), callbackPayload);
                handleDynamicCallback(callbackPayload, callbackId, user, chatId);
            }
        }
    }

    private void handleDynamicCallback(String payload, String callbackId, AppUser user, Long chatId) {
        if ("research:continue".equals(payload) && user.isAdmin()) {
            continueProductsResearch(user, callbackId);
            return;
        }
        if ("research:stop".equals(payload) && user.isAdmin()) {
            stopProductsResearch(user, callbackId);
            return;
        }
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
        if (payload.startsWith("buttons:target:")) {
            applyButtonTargetSelection(user, callbackId, payload.substring("buttons:target:".length()));
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
            return;
        }
        if (payload.startsWith("import:mode:")) {
            updateImportMode(user, callbackId, payload.substring("import:mode:".length()));
        }
    }

    private void startImportFlow(AppUser user, String callbackId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("files", new ArrayList<>());
        payload.put(IMPORT_MODE_KEY, ExcelImportService.IMPORT_MODE_HYBRID);
        botSessionService.update(botSessionService.getOrCreate(user.getMaxUserId()), SessionState.IMPORT_WAITING_FILES, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                buildImportFlowMessage(ExcelImportService.IMPORT_MODE_HYBRID),
                keyboardFactory.importKeyboard(ExcelImportService.IMPORT_MODE_HYBRID),
                "html");
        if (callbackId != null) {
            maxApiClient.answerCallback(callbackId, "Жду файлы номенклатуры");
        }
    }

    private void runImport(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> files = castList(payload.get("files"));
        String importMode = currentImportMode(session);
        if (files.isEmpty()) {
            if (callbackId != null) {
                maxApiClient.answerCallback(callbackId, "Сначала загрузите хотя бы один Excel, Word или PDF файл");
            }
            return;
        }
        var job = excelImportService.createJob(user.getMaxUserId(), files, importMode);
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🤖 Импорт запущен. Анализирую Excel / Word / PDF, извлекаю поля и готовлю предварительное распределение по категориям.",
                null,
                "html");
        excelImportService.analyzeAsync(job,
                () -> {
                    Map<String, Object> previewPayload = new HashMap<>();
                    previewPayload.put("mode", "import_preview_waiting");
                    previewPayload.put("importJobId", job.getId());
                    botSessionService.update(botSessionService.getOrCreate(user.getMaxUserId()), SessionState.IDLE, previewPayload);
                    String summary = excelImportService.findJob(job.getId()).map(importJob -> importJob.getSummary()).orElse("Анализ завершен.");
                    maxApiClient.sendToUser(user.getMaxUserId(), summary, keyboardFactory.importPreviewKeyboard(), "html");
                },
                error -> {
                    botSessionService.reset(user.getMaxUserId());
                    maxApiClient.sendToUser(user.getMaxUserId(),
                        "⚠️ ИИ не удалось распознать, попробуйте позже.\n\nТехническая заметка: " + TextUtils.trimTo(error, 700),
                        keyboardFactory.adminMenu(),
                        "html");
                });
        if (callbackId != null) {
            maxApiClient.answerCallback(callbackId, "Импорт запущен");
        }
    }

    private void updateImportMode(AppUser user, String callbackId, String requestedMode) {
        ResolvedImportFlow resolved = resolveImportFlow(user);
        if (resolved == null) {
            maxApiClient.answerCallback(callbackId, "Эта кнопка доступна только администраторам");
            return;
        }
        BotSession session = resolved.session();
        if (session.getState() != SessionState.IMPORT_WAITING_FILES) {
            maxApiClient.answerCallback(callbackId, "Сначала откройте загрузку номенклатуры");
            return;
        }
        Map<String, Object> payload = botSessionService.getPayload(session);
        String normalizedMode = normalizeImportMode(requestedMode);
        payload.put(IMPORT_MODE_KEY, normalizedMode);
        botSessionService.update(session, SessionState.IMPORT_WAITING_FILES, payload);
        maxApiClient.sendToUser(resolved.user().getMaxUserId(),
                buildImportFlowMessage(normalizedMode),
                keyboardFactory.importKeyboard(normalizedMode),
                "html");
        maxApiClient.answerCallback(callbackId, normalizedMode.equals(ExcelImportService.IMPORT_MODE_FULL_FILE_KIE)
                ? "Включена полная отправка в KIE"
                : "Включен текущий режим");
    }

    private void startProductsResearch(AppUser user) {
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🔎 Запускаю AI-переопределение культур для всех товаров, кроме семян. В AI отправляется только название товара, а обновляются только культуры. После каждой партии можно продолжить следующую.",
                null,
                "html");
        productResearchService.startResearchAsync(
                user.getMaxUserId(),
                report -> maxApiClient.sendToUser(
                        user.getMaxUserId(),
                        report.text(),
                        report.hasMore() ? keyboardFactory.researchKeyboard(true) : null,
                        "html"),
                summary -> maxApiClient.sendToUser(user.getMaxUserId(), summary, keyboardFactory.adminMenu(), "html"),
                error -> maxApiClient.sendToUser(
                        user.getMaxUserId(),
                        "⚠️ Не удалось завершить research.\n\nТехническая заметка: " + TextUtils.trimTo(error, 700),
                        keyboardFactory.adminMenu(),
                        "html")
        );
    }

    private void continueProductsResearch(AppUser user, String callbackId) {
        if (callbackId != null) {
            maxApiClient.answerCallback(callbackId, "Запускаю следующую партию");
        }
        productResearchService.continueResearchAsync(
                user.getMaxUserId(),
                report -> maxApiClient.sendToUser(
                        user.getMaxUserId(),
                        report.text(),
                        report.hasMore() ? keyboardFactory.researchKeyboard(true) : null,
                        "html"),
                summary -> maxApiClient.sendToUser(user.getMaxUserId(), summary, keyboardFactory.adminMenu(), "html"),
                error -> maxApiClient.sendToUser(
                        user.getMaxUserId(),
                        "⚠️ Не удалось продолжить research.\n\nТехническая заметка: " + TextUtils.trimTo(error, 700),
                        keyboardFactory.adminMenu(),
                        "html")
        );
    }

    private void stopProductsResearch(AppUser user, String callbackId) {
        String message = productResearchService.stopResearch(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(), "⏹ " + message, keyboardFactory.adminMenu(), "html");
        if (callbackId != null) {
            maxApiClient.answerCallback(callbackId, "Research остановлен");
        }
    }

    private void confirmImportPreview(AppUser user, BotSession session) {
        Long importJobId = extractImportJobId(session);
        if (importJobId == null) {
            botSessionService.reset(user.getMaxUserId());
            maxApiClient.sendToUser(user.getMaxUserId(), "Не нашел подготовленный импорт. Загрузите файл заново.", keyboardFactory.adminMenu(), "html");
            return;
        }
        maxApiClient.sendToUser(user.getMaxUserId(),
                "📥 Подтверждение получено. Добавляю товары в каталог и обновляю витрину.",
                null,
                "html");
        excelImportService.applyAsync(importJobId,
                () -> {
                    botSessionService.reset(user.getMaxUserId());
                    String summary = excelImportService.findJob(importJobId).map(importJob -> importJob.getSummary()).orElse("Импорт применен.");
                    maxApiClient.sendToUser(user.getMaxUserId(), summary, keyboardFactory.adminMenu(), "html");
                },
                error -> {
                    botSessionService.reset(user.getMaxUserId());
                    maxApiClient.sendToUser(user.getMaxUserId(),
                            "⚠️ Не удалось применить импорт.\n\nТехническая заметка: " + TextUtils.trimTo(error, 700),
                            keyboardFactory.adminMenu(),
                            "html");
                });
    }

    private void cancelImportPreview(AppUser user, BotSession session) {
        Long importJobId = extractImportJobId(session);
        if (importJobId != null) {
            try {
                excelImportService.cancelJob(importJobId);
            } catch (Exception e) {
                log.warn("Failed to cancel import job {}: {}", importJobId, e.getMessage());
            }
        }
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "❌ Импорт отменен. Подготовленные данные не были добавлены в каталог.",
                keyboardFactory.adminMenu(),
                "html");
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

    private void startBroadcastFlow(AppUser user, String callbackId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", "broadcast_waiting");
        payload.put("media", new ArrayList<>());
        payload.put("text", "");
        botSessionService.update(botSessionService.getOrCreate(user.getMaxUserId()), SessionState.BROADCAST_WAITING_MEDIA, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                📨 <b>Подготовка рассылки</b>

                Шаг 1 из 3. Отправьте фото, видео или медиагруппу для рассылки.
                Когда закончите, нажмите «Готово».
                """,
                keyboardFactory.postMediaKeyboard(),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду медиа для рассылки");
    }

    private void askBroadcastText(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> media = castList(payload.get("media"));
        if (media.isEmpty()) {
            maxApiClient.answerCallback(callbackId, "Сначала добавьте медиа для рассылки");
            return;
        }
        payload.put("mode", "broadcast_waiting");
        botSessionService.update(session, SessionState.BROADCAST_WAITING_TEXT, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                ✍️ Шаг 2 из 3. Теперь отправьте текст рассылки.

                После того как текст будет готов, нажмите «Готово», и я покажу предпросмотр.
                """,
                keyboardFactory.broadcastTextKeyboard(),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду текст рассылки");
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

    private void publishBroadcast(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        List<Map<String, Object>> media = new ArrayList<>(castList(payload.get("media")));
        String text = cleanValue(payload.get("text"));
        if (text == null) {
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "⚠️ Для рассылки нужен текст сообщения.",
                    keyboardFactory.adminMenu(),
                    "html");
            maxApiClient.answerCallback(callbackId, "Нет текста рассылки");
            return;
        }
        broadcastService.sendBroadcastWithAttachments(text, media);
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "📨 Рассылка отправлена всем пользователям.",
                keyboardFactory.adminMenu(),
                "html");
        maxApiClient.answerCallback(callbackId, "Рассылка отправлена");
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
            text.append("Активных кнопок сейчас нет.\n");
        } else {
            buttons.forEach(button -> text.append("• #").append(button.getId()).append(" — ").append(button.getLabel()).append(" — ").append(button.getUrl()).append("\n"));
        }
        text.append("\nНовые кнопки сейчас зашиты в коде и через бота не добавляются.");
        maxApiClient.sendToUser(user.getMaxUserId(),
                text.toString(),
                keyboardFactory.buttonsManagementKeyboard(buttons),
                "html");
        maxApiClient.answerCallback(callbackId, "Список кнопок открыт");
    }

    private void explainButtonCommand(AppUser user, String callbackId) {
        startButtonFlow(user, callbackId);
    }

    private void notifyButtonCreationDisabled(AppUser user, String callbackId) {
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                Добавление кнопок через бота сейчас отключено.

                Доступна заранее зашитая кнопка:
                <code>\uD83D\uDED2 Каталог — https://max.ru/id9729390997_bot</code>

                Для постов она зафиксирована в коде и не редактируется через бота.
                """,
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
        maxApiClient.answerCallback(callbackId, "Добавление отключено");
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
                .append("\nВсего пользователей: ").append(userService.countUsers());
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        for (AppUser item : users.getContent()) {
            if (!item.isAdmin()) {
                rows.add(List.of(keyboardFactory.messageButton("⭐ Назначить админом " + item.getMaxUserId())));
            }
        }
        List<Map<String, Object>> pager = new ArrayList<>();
        if (page > 0) {
            pager.add(keyboardFactory.messageButton("⬅️ Пользователи " + page));
        }
        pager.add(keyboardFactory.messageButton("🛠 Админка"));
        if (page + 1 < Math.max(users.getTotalPages(), 1)) {
            pager.add(keyboardFactory.messageButton("➡️ Пользователи " + (page + 2)));
        }
        rows.add(pager);
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
        List<Map<String, Object>> pager = new ArrayList<>();
        if (page > 0) {
            pager.add(keyboardFactory.messageButton("⬅️ Заказы " + page));
        }
        pager.add(keyboardFactory.messageButton("🛠 Админка"));
        if (page + 1 < Math.max(orders.getTotalPages(), 1)) {
            pager.add(keyboardFactory.messageButton("➡️ Заказы " + (page + 2)));
        }
        maxApiClient.sendToUser(user.getMaxUserId(),
                text.toString(),
                keyboardFactory.inlineKeyboard(List.of(pager)),
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

    private void sendBroadcastPreview(Long userId, Map<String, Object> payload) {
        List<Map<String, Object>> attachments = new ArrayList<>(castList(payload.get("media")));
        attachments.addAll(keyboardFactory.broadcastPreviewKeyboard());
        maxApiClient.sendToUser(userId,
                "👀 <b>Предпросмотр рассылки</b>\n\n" + payload.getOrDefault("text", ""),
                attachments,
                "html");
    }

    private void sendWelcome(Long userId, boolean admin) {
        String text = """
                🌱 <b>ООО Алга Агро Групп</b>

                Мы предлагаем:
                🌾 Семена — озимые и яровые культуры
                🛡 Пестициды — защита растений по строгой структуре каталога
                🧪 Агрохимикаты и мелиоранты

                Выберите нужный раздел 👇
                """;
        List<Map<String, Object>> attachments = new ArrayList<>();
        Map<String, Object> logoAttachment = maxApiClient.classpathImageAttachment("static/miniapp/assets/logo.png");
        if (logoAttachment != null) {
            attachments.add(logoAttachment);
        }
        attachments.addAll(keyboardFactory.mainMenu(userId, admin));
        maxApiClient.sendToUser(userId, text, attachments, "html");
    }

    private void sendManagerContact(Long userId, boolean admin) {
        String text = """
                💬 <b>Связь с менеджером</b>

                Написать в MAX: <a href="%s">Марат ООО АЛГА АГРО ГРУПП</a>
                Телефон: <a href="tel:+79175955143">+7 917 595-51-43</a>
                """.formatted(appProperties.getManagerDeepLink());
        maxApiClient.sendToUser(userId, text, keyboardFactory.mainMenu(userId, admin), "html");
    }

    private void openAdminMenu(AppUser user) {
        maxApiClient.sendToUser(user.getMaxUserId(),
                "🛠 <b>Админ-панель Алга Агро Групп</b>\nВыберите нужный раздел ниже.",
                keyboardFactory.adminMenu(),
                "html");
    }

    private void startButtonFlow(AppUser user, String callbackId) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", "button_waiting");
        payload.put("buttonStage", "label_waiting");
        botSessionService.update(session, SessionState.IDLE, payload);
        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                🔗 <b>Новая кнопка для постов</b>

                Шаг 1 из 2. Отправьте <b>название кнопки</b>.

                Например: <code>Каталог</code>

                Можно и одним сообщением:
                <code>Каталог - https://example.com</code>
                """,
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
        maxApiClient.answerCallback(callbackId, "Жду текст кнопки");
    }

    private void handleButtonDraftMessage(AppUser user, BotSession session, JsonNode update, String text) {
        Map<String, Object> payload = botSessionService.getPayload(session);
        String stage = cleanValue(payload.get("buttonStage"));
        String resolvedInput = resolveButtonInput(update, text);
        ButtonDraft draft = parseButtonDraft(resolvedInput);
        String pendingTitle = cleanValue(payload.get("pendingButtonTitle"));
        String pendingUrl = cleanValue(payload.get("pendingButtonUrl"));

        if (draft.title() != null) {
            pendingTitle = draft.title();
        }
        if (draft.url() != null) {
            pendingUrl = draft.url();
        }

        if (pendingTitle != null && pendingUrl != null) {
            postButtonService.createButton(pendingTitle, pendingUrl);
            botSessionService.reset(user.getMaxUserId());
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "✅ Кнопка добавлена.",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }

        if (resolvedInput == null || resolvedInput.isBlank()) {
            log.info("Button flow received message without recognizable text/url. update={}", update.toString());
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Не вижу текст сообщения. Отправьте название кнопки, например:\n<code>Каталог</code>",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }

        if ("url_waiting".equals(stage) && pendingTitle != null && pendingUrl == null) {
            String normalizedUrl = normalizeButtonUrlCandidate(resolvedInput);
            if (normalizedUrl != null) {
                postButtonService.createButton(pendingTitle, normalizedUrl);
                botSessionService.reset(user.getMaxUserId());
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "✅ Кнопка добавлена.",
                        keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                        "html");
                return;
            }
            maxApiClient.sendToUser(user.getMaxUserId(),
                    """
                    Ссылка не распознана.

                    Отправьте ссылку в одном из форматов:
                    <code>https://max.ru/id9729390997_bot</code>
                    <code>max.ru/id9729390997_bot</code>
                    <code>id9729390997_bot</code>
                    """,
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }

        if ("target_waiting".equals(stage) && pendingTitle != null && pendingUrl == null) {
            String normalizedUrl = normalizeButtonUrlCandidate(resolvedInput);
            if (normalizedUrl != null) {
                postButtonService.createButton(pendingTitle, normalizedUrl);
                botSessionService.reset(user.getMaxUserId());
                maxApiClient.sendToUser(user.getMaxUserId(),
                        "✅ Кнопка добавлена.",
                        keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                        "html");
                return;
            }
            maxApiClient.sendToUser(user.getMaxUserId(),
                    """
                    Не удалось распознать адрес.

                    Выберите готовый вариант ниже или отправьте адрес в формате:
                    <code>id9729390997_bot</code>
                    """,
                    keyboardFactory.buttonTargetKeyboard(),
                    "html");
            return;
        }

        Map<String, Object> nextPayload = new HashMap<>(payload);
        nextPayload.put("mode", "button_waiting");
        if (pendingTitle != null) {
            nextPayload.put("pendingButtonTitle", pendingTitle);
        } else {
            nextPayload.remove("pendingButtonTitle");
        }
        if (pendingUrl != null) {
            nextPayload.put("pendingButtonUrl", pendingUrl);
        } else {
            nextPayload.remove("pendingButtonUrl");
        }
        if (pendingTitle != null && pendingUrl == null) {
            nextPayload.put("buttonStage", "target_waiting");
        } else {
            nextPayload.put("buttonStage", "label_waiting");
        }
        botSessionService.update(session, SessionState.IDLE, nextPayload);

        if (pendingUrl == null) {
            if (pendingTitle != null) {
                maxApiClient.sendToUser(user.getMaxUserId(),
                        """
                        Шаг 2 из 2. Выберите, куда должна вести кнопка.

                        Если нужен произвольный адрес, нажмите «Ввести вручную».
                        """,
                        keyboardFactory.buttonTargetKeyboard(),
                        "html");
                return;
            }
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Ссылка не распознана. Отправьте так:\n<code>Каталог - https://max.ru/...</code>",
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            return;
        }

        maxApiClient.sendToUser(user.getMaxUserId(),
                """
                Ссылка получена. Теперь пришлите название кнопки одним сообщением.

                Например: <code>Каталог</code>
                """,
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
    }

    private boolean isButtonFlow(BotSession session) {
        return "button_waiting".equals(String.valueOf(botSessionService.getPayload(session).get("mode")));
    }

    private boolean isImportPreviewFlow(BotSession session) {
        return "import_preview_waiting".equals(String.valueOf(botSessionService.getPayload(session).get("mode")));
    }

    private boolean isBroadcastPreviewFlow(BotSession session) {
        return "broadcast_waiting".equals(String.valueOf(botSessionService.getPayload(session).get("mode")))
                && session.getState() == SessionState.IDLE
                && hasBroadcastDraftText(session);
    }

    private boolean hasBroadcastDraftText(BotSession session) {
        return cleanValue(botSessionService.getPayload(session).get("text")) != null;
    }

    private Long extractImportJobId(BotSession session) {
        Object value = botSessionService.getPayload(session).get("importJobId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer parseCommandPage(String normalizedText, String prefix) {
        String tail = normalizedText.substring(prefix.length()).trim();
        if (tail.isBlank()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(tail) - 1, 0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseCommandLong(String normalizedText, String prefix) {
        String tail = normalizedText.substring(prefix.length()).trim();
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void grantAdminFromText(AppUser user, Long targetUserId) {
        boolean granted = userService.grantAdmin(targetUserId);
        if (granted) {
            notifyNewAdmin(targetUserId);
        }
        maxApiClient.sendToUser(user.getMaxUserId(),
                granted ? "⭐ Права администратора выданы." : "Пользователь еще не запускал бота.",
                keyboardFactory.adminMenu(),
                "html");
    }

    private void deleteButtonFromText(AppUser user, Long buttonId) {
        boolean deleted = postButtonService.deleteButton(buttonId);
        maxApiClient.sendToUser(user.getMaxUserId(),
                deleted ? "🗑 Кнопка удалена." : "Кнопка не найдена.",
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
    }

    private void notifyNewAdmin(Long targetUserId) {
        maxApiClient.sendToUser(targetUserId,
                """
                ⭐ <b>Вам выданы права администратора</b>

                Теперь вам доступна панель управления ботом.
                """,
                keyboardFactory.mainMenu(targetUserId, true),
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

    private boolean isManagerContactCommand(String text) {
        if (text == null) {
            return false;
        }
        String normalized = TextUtils.normalizeToken(text);
        return normalized.equals("связаться с менеджером")
                || normalized.equals("написать менеджеру")
                || normalized.equals("менеджер");
    }

    private Long extractUserId(JsonNode update) {
        return firstLong(update,
                "/message_callback/user/user_id",
                "/message_callback/user_id",
                "/message_callback/sender/user_id",
                "/message_callback/from/user_id",
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
        return firstLong(update,
                "/message_callback/chat_id",
                "/message_callback/message/chat_id",
                "/message/chat_id",
                "/chat_id",
                "/callback/chat_id",
                "/callback/message/chat_id");
    }

    private String extractDisplayName(JsonNode update) {
        return firstText(update,
                "/message_callback/user/name",
                "/message_callback/sender/name",
                "/message_callback/from/name",
                "/message/sender/name",
                "/callback/user/name",
                "/user/name");
    }

    private String extractUsername(JsonNode update) {
        return firstText(update,
                "/message_callback/user/username",
                "/message_callback/sender/username",
                "/message_callback/from/username",
                "/message/sender/username",
                "/callback/user/username",
                "/user/username");
    }

    private String extractText(JsonNode update) {
        return firstText(update,
                "/message/body/text",
                "/message/text",
                "/text",
                "/message/body/link/url",
                "/message/link/url",
                "/link/url");
    }

    private String extractCallbackPayload(JsonNode update) {
        return firstText(update, "/callback/payload", "/message_callback/payload", "/payload");
    }

    private String extractCallbackId(JsonNode update) {
        return firstText(update,
                "/message_callback/callback_id",
                "/message_callback/id",
                "/callback/callback_id",
                "/callback/id");
    }

    private List<Map<String, Object>> extractAttachments(JsonNode update) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        JsonNode node = firstArrayNode(update,
                "/message/body/attachments",
                "/message/attachments",
                "/body/attachments",
                "/attachments");
        if (node != null && node.isArray()) {
            node.forEach(item -> attachments.add(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(item, Map.class)));
        } else {
            JsonNode single = firstObjectNode(update,
                    "/message/body/attachment",
                    "/message/attachment",
                    "/body/attachment",
                    "/attachment");
            if (single != null && single.isObject()) {
                attachments.add(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(single, Map.class));
            }
        }
        return attachments;
    }

    private JsonNode firstArrayNode(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private JsonNode firstObjectNode(JsonNode root, String... pointers) {
        for (String pointer : pointers) {
            JsonNode node = root.at(pointer);
            if (node.isObject()) {
                return node;
            }
        }
        return null;
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

    private String resolveButtonInput(JsonNode update, String text) {
        String linkUrl = firstText(update,
                "/message/body/link/url",
                "/message/link/url",
                "/link/url",
                "/message/body/url",
                "/message/url",
                "/url");
        if (text != null && !text.isBlank()) {
            String trimmed = text.trim();
            if (linkUrl != null && !linkUrl.isBlank() && !trimmed.contains(linkUrl)) {
                return (trimmed + " " + linkUrl.trim()).trim();
            }
            return trimmed;
        }
        if (linkUrl != null && !linkUrl.isBlank()) {
            return linkUrl.trim();
        }
        return null;
    }

    private String normalizeButtonUrlCandidate(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String candidate = rawInput.trim();
        if (candidate.isBlank()) {
            return null;
        }
        if (candidate.contains(" ")) {
            ButtonDraft draft = parseButtonDraft(candidate);
            if (draft.url() != null) {
                return draft.url();
            }
        }
        if (isValidUrl(candidate)) {
            return candidate;
        }
        if (candidate.startsWith("@")) {
            candidate = candidate.substring(1).trim();
        }
        String normalized = candidate
                .replaceFirst("^(?i)https?://", "")
                .replaceFirst("^(?i)max\\.ru/", "")
                .trim();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("join/") || normalized.startsWith("id") || normalized.contains("_bot") || normalized.contains("/")) {
            String url = "https://max.ru/" + normalized;
            return isValidUrl(url) ? url : null;
        }
        return null;
    }

    private void applyButtonTargetSelection(AppUser user, String callbackId, String targetType) {
        BotSession session = botSessionService.getOrCreate(user.getMaxUserId());
        Map<String, Object> payload = botSessionService.getPayload(session);
        String pendingTitle = cleanValue(payload.get("pendingButtonTitle"));
        if (pendingTitle == null) {
            maxApiClient.answerCallback(callbackId, "Сначала укажите название кнопки");
            startButtonFlow(user, null);
            return;
        }

        String targetUrl = switch (targetType) {
            case "self_bot" -> maxApiClient.getBotPublicUrl();
            case "mini_app" -> appProperties.getMiniAppUrl();
            case "manager" -> appProperties.getManagerContactUrl();
            case "manual" -> null;
            default -> null;
        };

        if ("manual".equals(targetType)) {
            Map<String, Object> nextPayload = new HashMap<>(payload);
            nextPayload.put("mode", "button_waiting");
            nextPayload.put("buttonStage", "url_waiting");
            botSessionService.update(session, SessionState.IDLE, nextPayload);
            maxApiClient.sendToUser(user.getMaxUserId(),
                    """
                    Отправьте адрес вручную.

                    Самый надежный формат в MAX:
                    <code>id9729390997_bot</code>

                    Также можно:
                    <code>join/XXXXXXXX</code>
                    <code>example.com/page</code>
                    """,
                    keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                    "html");
            maxApiClient.answerCallback(callbackId, "Жду адрес");
            return;
        }

        if (targetUrl == null || targetUrl.isBlank()) {
            maxApiClient.answerCallback(callbackId, "Не удалось подготовить ссылку");
            maxApiClient.sendToUser(user.getMaxUserId(),
                    "Не удалось автоматически получить ссылку для этого варианта. Выберите «Ввести вручную».",
                    keyboardFactory.buttonTargetKeyboard(),
                    "html");
            return;
        }

        postButtonService.createButton(pendingTitle, targetUrl);
        botSessionService.reset(user.getMaxUserId());
        maxApiClient.sendToUser(user.getMaxUserId(),
                "✅ Кнопка добавлена.",
                keyboardFactory.buttonsManagementKeyboard(postButtonService.getActiveButtons()),
                "html");
        maxApiClient.answerCallback(callbackId, "Кнопка сохранена");
    }

    private ButtonDraft parseButtonDraft(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return new ButtonDraft(null, null);
        }
        String input = rawInput.trim();
        Matcher matcher = URL_PATTERN.matcher(input);
        if (!matcher.find()) {
            return new ButtonDraft(cleanTextLabel(input), null);
        }
        String url = matcher.group(1).trim();
        if (!isValidUrl(url)) {
            return new ButtonDraft(cleanTextLabel(input), null);
        }
        String title = (input.substring(0, matcher.start()) + " " + input.substring(matcher.end())).trim();
        return new ButtonDraft(cleanTextLabel(title), url);
    }

    private String cleanTextLabel(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value
                .replaceAll("^[\\s\\-—–:|]+", "")
                .replaceAll("[\\s\\-—–:|]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String cleanValue(Object value) {
        if (value == null) {
            return null;
        }
        String cleaned = String.valueOf(value).trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private record ButtonDraft(String title, String url) {
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

    private String currentImportMode(BotSession session) {
        return normalizeImportMode(botSessionService.getPayload(session).get(IMPORT_MODE_KEY));
    }

    private ResolvedImportFlow resolveImportFlow(AppUser user) {
        BotSession currentSession = botSessionService.getOrCreate(user.getMaxUserId());
        if (user.isAdmin() && currentSession.getState() == SessionState.IMPORT_WAITING_FILES) {
            return new ResolvedImportFlow(user, currentSession);
        }
        Instant threshold = Instant.now().minus(Duration.ofMinutes(30));
        for (BotSession session : botSessionService.findByState(SessionState.IMPORT_WAITING_FILES)) {
            if (session.getUpdatedAt() != null && session.getUpdatedAt().isBefore(threshold)) {
                continue;
            }
            AppUser owner = userService.findByMaxUserId(session.getMaxUserId()).orElse(null);
            if (owner != null && owner.isAdmin()) {
                return new ResolvedImportFlow(owner, session);
            }
        }
        return null;
    }

    private String normalizeImportMode(Object rawMode) {
        String mode = rawMode == null ? "" : rawMode.toString().trim();
        return ExcelImportService.IMPORT_MODE_FULL_FILE_KIE.equalsIgnoreCase(mode)
                ? ExcelImportService.IMPORT_MODE_FULL_FILE_KIE
                : ExcelImportService.IMPORT_MODE_HYBRID;
    }

    private String buildImportFlowMessage(String importMode) {
        boolean fullFileMode = ExcelImportService.IMPORT_MODE_FULL_FILE_KIE.equals(importMode);
        String modeTitle = fullFileMode ? "Полная отправка файла в KIE" : "Как сейчас";
        String modeDescription = fullFileMode
                ? "Файл целиком отправляется в KIE для извлечения позиций. Полезно, если локальный парсер теряет товары в PDF или Word."
                : "Сначала используется текущий локальный разбор, а полная отправка в KIE подключается только как запасной вариант.";
        return """
                📦 <b>Загрузка номенклатуры</b>

                Отправьте один или несколько файлов номенклатуры: <b>Excel</b> (`.xlsx`, `.xlsm`, `.xls`), <b>Word</b> (`.docx`, `.doc`) или <b>PDF</b> (`.pdf`).

                <b>Режим:</b> %s
                %s
                """.formatted(modeTitle, modeDescription);
    }

    private record ResolvedImportFlow(
            AppUser user,
            BotSession session
    ) {
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
