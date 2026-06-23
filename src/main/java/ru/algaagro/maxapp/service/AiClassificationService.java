package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.util.JsonHelper;

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
        List<List<ExcelImportService.ImportRow>> chunks = chunk(rows, 20);
        for (List<ExcelImportService.ImportRow> chunk : chunks) {
            String prompt = buildPrompt(chunk, knownCultures);
            String rawResponse = callKie(prompt);
            if (rawResponse == null) {
                rawResponse = callGemini(prompt);
            }
            if (rawResponse == null) {
                throw new IllegalStateException("ИИ не удалось распознать, попробуйте позже.");
            }
            results.addAll(parseResponse(rawResponse, chunk));
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
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
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
                log.warn("Gemini returned status {}", response.statusCode());
                return null;
            }
            JsonNode json = jsonHelper.readTree(response.body());
            JsonNode content = json.path("choices").path(0).path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) {
                return content.asText();
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gemini request failed: {}", e.getMessage());
            return null;
        }
    }

    private List<ClassificationResult> parseResponse(String rawResponse, List<ExcelImportService.ImportRow> chunk) {
        String sanitized = rawResponse.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();
        JsonNode root = jsonHelper.readTree(sanitized);
        JsonNode items = root.has("products") ? root.path("products") : root;
        List<ClassificationResult> results = new ArrayList<>();
        if (!items.isArray()) {
            throw new IllegalStateException("AI response is not an array");
        }
        for (JsonNode item : items) {
            Map<String, Object> filterMap = new LinkedHashMap<>();
            item.path("filters").fields().forEachRemaining(entry -> filterMap.put(entry.getKey(), toSimpleValue(entry.getValue())));
            results.add(new ClassificationResult(
                    item.path("rowId").asText(),
                    item.path("normalizedName").asText(),
                    item.path("description").asText(""),
                    item.path("brand").asText(""),
                    item.path("category").asText("Прочее"),
                    item.path("subcategory").asText(""),
                    item.path("itemType").asText("Прочее"),
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
                Формат ответа:
                {
                  "products": [
                    {
                      "rowId": "file#sheet#row",
                      "normalizedName": "чистое название товара",
                      "description": "краткое описание для каталога",
                      "brand": "бренд или линейка",
                      "category": "Семена|Пестициды|Удобрения|Микроэлементы|Стимуляторы|Адъюванты|Протравители|Гербициды|Фунгициды|Инсектициды|Десиканты|Прочее",
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
                - Если данных мало, делай осторожный вывод по названию и колонкам.
                - Не придумывай лишнее описание, 1 короткое предложение.
                - Количество объектов в products должно строго совпадать с количеством строк.
                """);
        if (!knownCultures.isEmpty()) {
            builder.append("\nИзвестные культуры в каталоге: ").append(String.join(", ", knownCultures)).append("\n");
        }
        builder.append("\nСтроки для классификации:\n");
        for (ExcelImportService.ImportRow row : rows) {
            builder.append("- rowId=").append(row.rowId())
                    .append("; source=").append(row.sourceFile())
                    .append("; values=").append(row.columns())
                    .append("\n");
        }
        return builder.toString();
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
