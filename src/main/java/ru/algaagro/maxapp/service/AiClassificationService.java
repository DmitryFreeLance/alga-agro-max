package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class AiClassificationService {

    private static final Logger log = LoggerFactory.getLogger(AiClassificationService.class);

    private final AppProperties appProperties;
    private final JsonHelper jsonHelper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AiClassificationService(AppProperties appProperties, JsonHelper jsonHelper) {
        this.appProperties = appProperties;
        this.jsonHelper = jsonHelper;
    }

    public List<ClassificationResult> classify(List<ExcelImportService.ImportRow> rows, List<String> knownCultures) {
        List<ClassificationResult> results = new ArrayList<>();
        int batchSize = Math.max(1, appProperties.getAi().getKieBatchSize());
        List<List<ExcelImportService.ImportRow>> chunks = chunk(rows, batchSize);
        log.info("AI classification started. rows={}, requests={}, batchSize={}", rows.size(), chunks.size(), batchSize);
        for (List<ExcelImportService.ImportRow> chunk : chunks) {
            String prompt = buildPrompt(chunk, knownCultures);
            log.info("Trying Gemini classification first. model={}, endpoint={}",
                    appProperties.getAi().getKieGeminiModel(),
                    appProperties.getAi().getKieGeminiEndpoint());
            String rawResponse = callGemini(prompt);
            if (rawResponse == null) {
                log.warn("Gemini classification failed, switching to GPT fallback. model={}",
                        appProperties.getAi().getKieModel());
                rawResponse = callKie(prompt);
            }
            if (rawResponse == null) {
                throw new IllegalStateException("ИИ не удалось распознать, попробуйте позже.");
            }
            try {
                results.addAll(parseResponse(rawResponse, chunk));
            } catch (IllegalStateException error) {
                log.warn("AI response parsing failed, using heuristic fallback. reason={}, payloadPreview={}",
                        error.getMessage(),
                        TextUtils.trimTo(rawResponse, 1500));
                results.addAll(buildHeuristicResults(chunk));
            }
        }
        return results;
    }

    private String callKie(String prompt) {
        String apiKey = appProperties.getAi().getKieApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        Map<String, Object> body = Map.of(
                "model", appProperties.getAi().getKieModel(),
                "stream", false,
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "input_text",
                                "text", prompt
                        ))
                ))
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(appProperties.getAi().getKieBaseUrl() + "/codex/v1/responses"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(appProperties.getAi().getKieTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonHelper.writeValue(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Kie AI returned status {}", response.statusCode());
                return null;
            }
            JsonNode json = jsonHelper.readTree(response.body());
            if (json.hasNonNull("output_text")) {
                return json.path("output_text").asText();
            }
            for (JsonNode output : json.path("output")) {
                for (JsonNode content : output.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        return content.path("text").asText();
                    }
                }
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Kie AI request interrupted: {}", e.getMessage());
            return null;
        } catch (IOException e) {
            log.warn("Kie AI request failed: {}", e.getMessage());
            return null;
        }
    }

    private String callGemini(String prompt) {
        String apiKey = appProperties.getAi().getKieGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        Map<String, Object> body = Map.of(
                "model", appProperties.getAi().getKieGeminiModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "Return only JSON, no markdown."),
                        Map.of("role", "user", "content", prompt)
                ),
                "stream", false
        );
        try {
            String url = appProperties.getAi().getKieBaseUrl() + appProperties.getAi().getKieGeminiEndpoint();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(appProperties.getAi().getGeminiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonHelper.writeValue(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Gemini returned status {}. body={}", response.statusCode(), TextUtils.trimTo(response.body(), 1200));
                return null;
            }
            log.info("Gemini classification response received. status={}, bodyPreview={}",
                    response.statusCode(),
                    TextUtils.trimTo(response.body(), 600));
            JsonNode json = jsonHelper.readTree(response.body());
            JsonNode content = json.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
            }
            if (content.isArray()) {
                String extracted = extractTextFromContentArray(content);
                if (!extracted.isBlank()) {
                    return extracted;
                }
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini request interrupted: {}", e.getMessage());
            return null;
        } catch (IOException e) {
            log.warn("Gemini request failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Gemini request parsing failed: {}", e.getMessage());
            return null;
        }
    }

    private List<ClassificationResult> parseResponse(String rawResponse, List<ExcelImportService.ImportRow> chunk) {
        JsonNode items = extractProductsNode(rawResponse);
        List<ClassificationResult> results = new ArrayList<>();
        if (!items.isArray()) {
            log.warn("AI response is not an array after normalization. payload={}", TextUtils.trimTo(rawResponse, 1500));
            throw new IllegalStateException("AI response is not an array");
        }
        for (JsonNode item : items) {
            Map<String, Object> filterMap = new LinkedHashMap<>();
            item.path("filters").fields().forEachRemaining(entry -> filterMap.put(entry.getKey(), toSimpleValue(entry.getValue())));
            String category = item.path("category").asText("");
            String subcategory = item.path("subcategory").asText("");
            String itemType = item.path("itemType").asText("");
            String description = item.path("description").asText("");
            if (category.isBlank() || "Прочее".equalsIgnoreCase(category)) {
                category = inferCategory(chunk.get(results.size()));
            }
            if (subcategory.isBlank()) {
                subcategory = inferSubcategory(chunk.get(results.size()));
            }
            if (itemType.isBlank() || "Прочее".equalsIgnoreCase(itemType)) {
                itemType = !subcategory.isBlank() ? subcategory : category;
            }
            if (description.isBlank()) {
                description = buildFallbackDescription(chunk.get(results.size()));
            }
            results.add(new ClassificationResult(
                    item.path("rowId").asText(),
                    item.path("normalizedName").asText(),
                    description,
                    item.path("brand").asText(""),
                    category.isBlank() ? "Прочее" : category,
                    subcategory,
                    itemType.isBlank() ? "Товар" : itemType,
                    readStringArray(item.path("cultures")),
                    readStringArray(item.path("purposes")),
                    readStringArray(item.path("tags")),
                    filterMap
            ));
        }
        if (results.size() != chunk.size()) {
            throw new IllegalStateException("AI returned mismatched number of rows");
        }
        return results;
    }

    private List<ClassificationResult> buildHeuristicResults(List<ExcelImportService.ImportRow> chunk) {
        List<ClassificationResult> results = new ArrayList<>();
        for (ExcelImportService.ImportRow row : chunk) {
            String category = inferCategory(row);
            String subcategory = inferSubcategory(row);
            String itemType = !subcategory.isBlank() ? subcategory : category;
            List<String> cultures = inferCultures(row);
            List<String> tags = inferTags(row);
            Map<String, Object> filterMap = inferFilterMap(row, cultures, tags);
            results.add(new ClassificationResult(
                    row.rowId(),
                    heuristicName(row),
                    buildFallbackDescription(row),
                    inferBrand(row),
                    category.isBlank() ? "Прочее" : category,
                    subcategory,
                    itemType.isBlank() ? "Товар" : itemType,
                    cultures,
                    inferPurposes(row, category),
                    tags,
                    filterMap
            ));
        }
        return results;
    }

    private JsonNode extractProductsNode(String rawResponse) {
        String sanitized = sanitizeResponse(rawResponse);
        JsonNode root = tryReadJsonNode(sanitized);
        if (root == null) {
            String jsonFragment = extractFirstJsonFragment(sanitized);
            root = tryReadJsonNode(jsonFragment);
        }
        if (root == null) {
            throw new IllegalStateException("AI response is not valid JSON");
        }
        JsonNode resolved = resolveProductsNode(root);
        if (resolved != null) {
            return resolved;
        }
        if (root.isTextual()) {
            JsonNode nested = extractProductsNode(root.asText());
            if (nested != null) {
                return nested;
            }
        }
        return root;
    }

    private JsonNode resolveProductsNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (node.isObject()) {
            JsonNode productsNode = node.path("products");
            if (productsNode.isArray()) {
                return productsNode;
            }
            if (productsNode.isTextual()) {
                JsonNode parsedProducts = tryReadJsonNode(sanitizeResponse(productsNode.asText()));
                JsonNode nested = resolveProductsNode(parsedProducts);
                if (nested != null) {
                    return nested;
                }
            }
            JsonNode choicesContent = node.path("choices").path(0).path("message").path("content");
            if (choicesContent.isTextual()) {
                JsonNode nested = extractProductsNode(choicesContent.asText());
                if (nested != null) {
                    return nested;
                }
            }
            if (choicesContent.isArray()) {
                String extracted = extractTextFromContentArray(choicesContent);
                if (!extracted.isBlank()) {
                    JsonNode nested = extractProductsNode(extracted);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            if (node.hasNonNull("output_text")) {
                JsonNode nested = extractProductsNode(node.path("output_text").asText());
                if (nested != null) {
                    return nested;
                }
            }
            JsonNode dataNode = node.path("data");
            JsonNode nestedData = resolveProductsNode(dataNode);
            if (nestedData != null) {
                return nestedData;
            }
            JsonNode itemsNode = node.path("items");
            if (itemsNode.isArray()) {
                return itemsNode;
            }
        }
        return null;
    }

    private String sanitizeResponse(String rawResponse) {
        return rawResponse == null ? "" : rawResponse.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private JsonNode tryReadJsonNode(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return jsonHelper.readTree(payload);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractTextFromContentArray(JsonNode contentArray) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : contentArray) {
            if (part.isTextual()) {
                builder.append(part.asText());
                continue;
            }
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                builder.append(text);
                continue;
            }
            String content = part.path("content").asText("");
            if (!content.isBlank()) {
                builder.append(content);
            }
        }
        return builder.toString().trim();
    }

    private String extractFirstJsonFragment(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char open;
        char close;
        if (objectStart == -1 && arrayStart == -1) {
            return "";
        }
        if (objectStart == -1 || (arrayStart != -1 && arrayStart < objectStart)) {
            start = arrayStart;
            open = '[';
            close = ']';
        } else {
            start = objectStart;
            open = '{';
            close = '}';
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private Object toSimpleValue(JsonNode value) {
        if (value.isArray()) {
            return readStringArray(value);
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    private List<String> readStringArray(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode node : arrayNode) {
                String value = node.asText();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private String buildPrompt(List<ExcelImportService.ImportRow> rows, List<String> knownCultures) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                Ты классифицируешь агро-товары для каталога.
                Верни строго JSON без markdown.
                Не добавляй пояснения до или после JSON.
                Формат ответа:
                {
                  "products": [
                    {
                      "rowId": "file#sheet#row",
                      "normalizedName": "чистое название товара",
                      "description": "краткое описание для каталога",
                      "brand": "бренд или линейка",
                      "category": "Семена|Пестициды|Агропитание|Адъюванты|Прочее",
                      "subcategory": "подкатегория",
                      "itemType": "тип карточки",
                      "cultures": ["пшеница"],
                      "purposes": ["защита"],
                      "tags": ["листовая подкормка", "озимая"],
                      "filters": {
                        "cultureGroup": ["зерновые"],
                        "season": ["озимые"],
                        "application": ["лист"]
                      }
                    }
                  ]
                }
                Правила:
                - Определи культуры, для которых товар применяется или к которым относится.
                - Если это семена, культура должна быть основной культурой товара.
                - Если это пестицид или удобрение, включай все подходящие культуры, если они явно следуют из названия или описания.
                - Для жидких комплексов, биостимуляторов, корректоров дефицита, NPK, борных, цинковых, кальциевых, серных, магниевых продуктов чаще всего category = Агропитание.
                - Для прилипателей, pH-контроля, пеногасителей, очистителей, стикеров и технологических добавок category = Адъюванты.
                - Используй значение поля "Раздел" как главный контекст категории и назначения.
                - normalizedName должен браться из колонки "Позиция" без служебного мусора.
                - description собирай предметно: что это за продукт + ключевой состав или норма расхода.
                - Не оставляй category = Прочее, если по "Позиции", "Составу", "Норме расхода" или "Разделу" можно понять тип товара.
                - Количество объектов в products должно строго совпадать с количеством строк.
                """);
        if (!knownCultures.isEmpty()) {
            builder.append("\nИзвестные культуры в каталоге: ").append(String.join(", ", knownCultures)).append("\n");
        }
        builder.append("\nСтроки для классификации:\n");
        for (ExcelImportService.ImportRow row : rows) {
            builder.append("- rowId=").append(row.rowId())
                    .append("; source=").append(row.sourceFile())
                    .append("; section=").append(row.section())
                    .append("; values=").append(row.columns())
                    .append("\n");
        }
        return builder.toString();
    }

    private String inferCategory(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        if (context.contains("пшениц") || context.contains("ячмен") || context.contains("горох")
                || context.contains("соя") || context.contains("гречих") || context.contains("рапс")
                || context.contains("рожь") || context.contains("тритикал")) {
            if (context.contains("озим") || context.contains("яров") || context.contains("семен") || context.contains("сорт")) {
                return "Семена";
            }
        }
        if (context.contains("адъюв") || context.contains("технологич") || context.contains("прилип") || context.contains("ph контроль") || context.contains("стик") || context.contains("клинер")) {
            return "Адъюванты";
        }
        if (context.contains("сем") || context.contains("npk") || context.contains("бор") || context.contains("цинк") || context.contains("кальц") || context.contains("магний")
                || context.contains("сер") || context.contains("молибден") || context.contains("листов") || context.contains("подкорм") || context.contains("биостим")
                || context.contains("дефицит") || context.contains("аминокислот")) {
            return "Агропитание";
        }
        if (context.contains("гербиц") || context.contains("фунгиц") || context.contains("инсекти") || context.contains("протрав")) {
            return "Пестициды";
        }
        return "Прочее";
    }

    private String inferSubcategory(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        if (context.contains("озим")) return "Озимые";
        if (context.contains("яров")) return "Яровые";
        if (context.contains("обработк") && context.contains("сем")) return "Биостимуляторы";
        if (context.contains("антистресс")) return "Антистрессанты";
        if (context.contains("npk")) return "NPK-комплексы";
        if (context.contains("дефицит")) return "Корректоры дефицита";
        if (context.contains("адъюв") || context.contains("технологич")) return "Технологические добавки";
        if (context.contains("стик") || context.contains("прилип")) return "Прилипатели";
        if (context.contains("ph контроль")) return "pH-контроль";
        return "";
    }

    private String buildFallbackDescription(ExcelImportService.ImportRow row) {
        String section = row.section();
        String culture = firstColumnValue(row.columns(), "культура");
        String composition = firstColumnValue(row.columns(), "состав", "composition");
        String dosage = firstColumnValue(row.columns(), "норма расхода", "расход", "дозиров");
        String seedGrade = firstColumnValue(row.columns(), "категория семян");
        List<String> parts = new ArrayList<>();
        if (culture != null && !culture.isBlank()) {
            parts.add("Культура: " + TextUtils.trimTo(culture, 120));
        }
        if (section != null && !section.isBlank()) {
            parts.add(section);
        }
        if (seedGrade != null && !seedGrade.isBlank()) {
            parts.add("Категория: " + seedGrade);
        }
        if (composition != null && !composition.isBlank()) {
            parts.add("Состав: " + TextUtils.trimTo(composition, 180));
        }
        if (dosage != null && !dosage.isBlank()) {
            parts.add("Расход: " + TextUtils.trimTo(dosage, 80));
        }
        return String.join(". ", parts);
    }

    private String heuristicName(ExcelImportService.ImportRow row) {
        String name = row.nameGuess() == null ? "" : row.nameGuess().trim();
        if (!name.isBlank() && !"Культура".equalsIgnoreCase(name)) {
            return name;
        }
        return firstColumnValue(row.columns(), "позиция", "наименование", "товар");
    }

    private String inferBrand(ExcelImportService.ImportRow row) {
        String source = TextUtils.normalizeToken(row.sourceFile());
        if (source.contains("uрl") || source.contains("upl")) {
            return "UPL";
        }
        if (source.contains("cpp")) {
            return "CPP";
        }
        return "";
    }

    private List<String> inferCultures(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        LinkedHashSet<String> cultures = new LinkedHashSet<>();
        if (context.contains("пшениц")) cultures.add("Пшеница");
        if (context.contains("ячмен")) cultures.add("Ячмень");
        if (context.contains("горох")) cultures.add("Горох");
        if (context.contains("соя")) cultures.add("Соя");
        if (context.contains("гречих")) cultures.add("Гречиха");
        if (context.contains("рапс")) cultures.add("Рапс");
        if (context.contains("рожь")) cultures.add("Рожь");
        if (context.contains("тритикал")) cultures.add("Тритикале");
        return new ArrayList<>(cultures);
    }

    private List<String> inferPurposes(ExcelImportService.ImportRow row, String category) {
        if ("Семена".equalsIgnoreCase(category)) {
            return List.of("посев");
        }
        if ("Пестициды".equalsIgnoreCase(category)) {
            return List.of("защита");
        }
        if ("Агропитание".equalsIgnoreCase(category)) {
            return List.of("питание");
        }
        return List.of();
    }

    private List<String> inferTags(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (context.contains("озим")) tags.add("озимая");
        if (context.contains("яров")) tags.add("яровая");
        if (context.contains("сильная пшеница")) tags.add("сильная пшеница");
        if (context.contains("зимостойк")) tags.add("зимостойкость");
        if (context.contains("морозоуст")) tags.add("морозоустойчивость");
        return new ArrayList<>(tags);
    }

    private Map<String, Object> inferFilterMap(ExcelImportService.ImportRow row, List<String> cultures, List<String> tags) {
        Map<String, Object> filterMap = new LinkedHashMap<>();
        if (!cultures.isEmpty()) {
            filterMap.put("cultureGroup", List.of("зерновые"));
        }
        if (tags.stream().anyMatch(tag -> TextUtils.normalizeToken(tag).contains("озим"))) {
            filterMap.put("season", List.of("Озимые"));
        } else if (tags.stream().anyMatch(tag -> TextUtils.normalizeToken(tag).contains("яров"))) {
            filterMap.put("season", List.of("Яровые"));
        }
        return filterMap;
    }

    private String firstColumnValue(Map<String, String> columns, String... needles) {
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String normalized = TextUtils.normalizeToken(entry.getKey());
            for (String needle : needles) {
                if (normalized.contains(TextUtils.normalizeToken(needle))) {
                    String value = entry.getValue();
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return "";
    }

    private List<List<ExcelImportService.ImportRow>> chunk(List<ExcelImportService.ImportRow> rows, int size) {
        List<List<ExcelImportService.ImportRow>> chunks = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += size) {
            chunks.add(rows.subList(i, Math.min(rows.size(), i + size)));
        }
        return chunks;
    }

    public record ClassificationResult(
            String rowId,
            String normalizedName,
            String description,
            String brand,
            String category,
            String subcategory,
            String itemType,
            List<String> cultures,
            List<String> purposes,
            List<String> tags,
            Map<String, Object> filterMap
    ) {
    }
}
