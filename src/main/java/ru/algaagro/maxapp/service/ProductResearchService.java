package ru.algaagro.maxapp.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ProductResearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductResearchService.class);
    private static final int RESEARCH_AI_BATCH_SIZE = 20;

    private final ExecutorService importExecutorService;
    private final CatalogProductRepository catalogProductRepository;
    private final ProductService productService;
    private final AiClassificationService aiClassificationService;
    private final JsonHelper jsonHelper;

    public ProductResearchService(
            ExecutorService importExecutorService,
            CatalogProductRepository catalogProductRepository,
            ProductService productService,
            AiClassificationService aiClassificationService,
            JsonHelper jsonHelper
    ) {
        this.importExecutorService = importExecutorService;
        this.catalogProductRepository = catalogProductRepository;
        this.productService = productService;
        this.aiClassificationService = aiClassificationService;
        this.jsonHelper = jsonHelper;
    }

    public CompletableFuture<Void> researchUncategorizedProductsAsync(Long initiatedBy, Consumer<String> onSuccess, Consumer<String> onFailure) {
        return CompletableFuture
                .supplyAsync(() -> researchUncategorizedProducts(initiatedBy), importExecutorService)
                .thenAccept(onSuccess)
                .exceptionally(error -> {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    onFailure.accept(cause.getMessage());
                    return null;
                });
    }

    private String researchUncategorizedProducts(Long initiatedBy) {
        List<CatalogProduct> activeProducts = catalogProductRepository.findAllByActiveTrue();
        List<CatalogProduct> targets = activeProducts.stream()
                .filter(this::needsResearch)
                .sorted((left, right) -> String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName())))
                .toList();
        log.info("Research started by {}. targets={}", initiatedBy, targets.size());
        if (targets.isEmpty()) {
            return "🔎 В разделе <b>«Прочее»</b> не найдено активных товаров для пересмотра.";
        }

        List<ExcelImportService.ImportRow> rows = new ArrayList<>();
        for (CatalogProduct product : targets) {
            rows.add(toImportRow(product));
        }

        LinkedHashSet<String> knownCultures = new LinkedHashSet<>();
        activeProducts.forEach(product -> knownCultures.addAll(productService.getStringList(product.getCulturesJson())));
        List<AiClassificationService.ClassificationResult> classified = classifyForResearch(rows, new ArrayList<>(knownCultures));

        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        Map<String, Integer> bySection = new LinkedHashMap<>();
        List<String> failedProducts = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            CatalogProduct product = targets.get(i);
            AiClassificationService.ClassificationResult result = classified.get(i);
            Resolution resolution = resolveSection(product, result, rows.get(i));
            String description = firstNonBlank(result.description(), product.getDescription());
            String brand = firstNonBlank(result.brand(), product.getBrand());
            boolean changed = !Objects.equals(blankToNull(product.getCategory()), blankToNull(resolution.category()))
                    || !Objects.equals(blankToNull(product.getSubcategory()), blankToNull(resolution.subcategory()))
                    || !Objects.equals(blankToNull(product.getItemType()), blankToNull(resolution.itemType()))
                    || !Objects.equals(blankToNull(product.getDescription()), blankToNull(description))
                    || !Objects.equals(blankToNull(product.getBrand()), blankToNull(brand));
            if (!changed) {
                unchanged++;
                bySection.merge(displaySection(resolution.category()), 1, Integer::sum);
                continue;
            }

            ProductService.AdminProductPayload payload = new ProductService.AdminProductPayload(
                    product.getExternalId(),
                    product.getSourceFile(),
                    product.getSku(),
                    product.getName(),
                    description,
                    brand,
                    resolution.category(),
                    resolution.subcategory(),
                    resolution.itemType(),
                    product.getUnitName(),
                    product.getPrice(),
                    product.getStockQuantity(),
                    product.getPackageType(),
                    product.getPackageDescription(),
                    product.getMinOrderQuantity(),
                    product.getOrderStep(),
                    productService.getStringList(product.getCulturesJson()),
                    productService.getStringList(product.getPurposesJson()),
                    productService.getStringList(product.getTagsJson()),
                    jsonHelper.readMap(product.getFilterMapJson()),
                    jsonHelper.readMap(product.getRawDataJson()),
                    product.isActive()
            );
            try {
                productService.updateProduct(product, payload, false);
                updated++;
                bySection.merge(displaySection(resolution.category()), 1, Integer::sum);
            } catch (Exception e) {
                failed++;
                failedProducts.add(firstNonBlank(product.getName(), "ID " + product.getId()));
                log.warn("Research update failed for product {} (id={}): {}", product.getName(), product.getId(), e.getMessage());
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("🧠 <b>Research завершен</b>\n\n");
        summary.append("• Инициатор: <b>").append(initiatedBy == null ? "admin" : initiatedBy).append("</b>\n");
        summary.append("• Проверено товаров из «Прочее»: <b>").append(targets.size()).append("</b>\n");
        summary.append("• Обновлено: <b>").append(updated).append("</b>\n");
        summary.append("• Без изменений: <b>").append(unchanged).append("</b>\n");
        summary.append("• Ошибок обновления: <b>").append(failed).append("</b>\n");
        if (!bySection.isEmpty()) {
            summary.append("• Разнесено по разделам: <b>")
                    .append(bySection.entrySet().stream()
                            .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("—"))
                    .append("</b>\n");
        }
        if (!failedProducts.isEmpty()) {
            summary.append("• Проблемные позиции: <b>")
                    .append(failedProducts.stream().limit(8).reduce((left, right) -> left + ", " + right).orElse("—"))
                    .append(failedProducts.size() > 8 ? " и еще " + (failedProducts.size() - 8) : "")
                    .append("</b>\n");
        }
        summary.append("\nЕсли часть позиций все еще осталась в «Прочее», значит по текущим данным ИИ не смог уверенно определить тип товара.");
        return summary.toString();
    }

    private List<AiClassificationService.ClassificationResult> classifyForResearch(
            List<ExcelImportService.ImportRow> rows,
            List<String> knownCultures
    ) {
        List<AiClassificationService.ClassificationResult> results = new ArrayList<>();
        for (int start = 0; start < rows.size(); start += RESEARCH_AI_BATCH_SIZE) {
            int end = Math.min(rows.size(), start + RESEARCH_AI_BATCH_SIZE);
            List<ExcelImportService.ImportRow> batch = rows.subList(start, end);
            try {
                log.info("Research AI batch started. start={}, end={}, size={}", start + 1, end, batch.size());
                results.addAll(aiClassificationService.classify(batch, knownCultures));
            } catch (Exception error) {
                log.warn("Research AI batch failed. start={}, end={}, reason={}", start + 1, end, error.getMessage());
                results.addAll(aiClassificationService.classifyHeuristically(batch));
            }
        }
        return results;
    }

    private boolean needsResearch(CatalogProduct product) {
        String category = TextUtils.normalizeToken(product.getCategory());
        return category.isBlank() || "прочее".equals(category);
    }

    private ExcelImportService.ImportRow toImportRow(CatalogProduct product) {
        Map<String, String> columns = new LinkedHashMap<>();
        putIfNotBlank(columns, "Позиция", product.getName());
        putIfNotBlank(columns, "Описание", product.getDescription());
        putIfNotBlank(columns, "Производитель", product.getBrand());
        putIfNotBlank(columns, "Раздел", product.getCategory());
        putIfNotBlank(columns, "Подкатегория", product.getSubcategory());
        putIfNotBlank(columns, "Тип", product.getItemType());
        putIfNotBlank(columns, "Ед.изм", product.getUnitName());
        putIfNotBlank(columns, "Фасовка", product.getPackageDescription());
        putIfNotBlank(columns, "Состав", firstRawValue(product, "Состав", "Действующее вещество", "действующее вещество"));
        putIfNotBlank(columns, "Норма расхода", firstRawValue(product, "Норма расхода", "Расход", "дозировка"));
        putIfNotBlank(columns, "Культуры", String.join(", ", productService.getStringList(product.getCulturesJson())));
        putIfNotBlank(columns, "Сырой текст", buildRawText(product));
        return new ExcelImportService.ImportRow(
                "product-" + product.getId(),
                firstNonBlank(product.getSourceFile(), "catalog"),
                "catalog",
                product.getId() == null ? 0 : product.getId().intValue(),
                columns,
                firstNonBlank(product.getName(), ""),
                firstNonBlank(product.getCategory(), "")
        );
    }

    private String buildRawText(CatalogProduct product) {
        return List.of(
                        product.getName(),
                        product.getDescription(),
                        product.getBrand(),
                        product.getCategory(),
                        product.getSubcategory(),
                        product.getItemType(),
                        firstRawValue(product, "Состав", "Действующее вещество", "действующее вещество"),
                        firstRawValue(product, "Норма расхода", "Расход", "дозировка")
                ).stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
    }

    private Resolution resolveSection(CatalogProduct product, AiClassificationService.ClassificationResult result, ExcelImportService.ImportRow row) {
        String category = normalizeCategory(result.category());
        String subcategory = normalizeCategory(firstNonBlank(result.subcategory(), inferSubcategory(row)));
        String itemType = normalizeCategory(firstNonBlank(result.itemType(), subcategory, category));

        if (isGenericPesticides(category) && isSpecificSection(subcategory)) {
            category = subcategory;
        } else if (isSpecificSection(category)) {
            subcategory = firstNonBlank(subcategory, category);
            itemType = firstNonBlank(itemType, subcategory, category);
        } else if (isGenericPesticides(category)) {
            category = "СЗР";
        } else if (category.isBlank() || "Прочее".equalsIgnoreCase(category)) {
            category = inferCategory(row, subcategory);
        }

        if (subcategory.isBlank() && isSpecificSection(category)) {
            subcategory = category;
        }
        if (itemType.isBlank() || "Прочее".equalsIgnoreCase(itemType)) {
            itemType = firstNonBlank(subcategory, category, "Товар");
        }
        return new Resolution(
                category.isBlank() ? "Прочее" : category,
                blankToNull(subcategory),
                itemType
        );
    }

    private String inferCategory(ExcelImportService.ImportRow row, String subcategory) {
        if (isSpecificSection(subcategory)) {
            return subcategory;
        }
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        if (context.contains("адъюв") || context.contains("адьюв") || context.contains("прилип") || context.contains("стик")) {
            return "Адъюванты";
        }
        if (context.contains("бор") || context.contains("цинк") || context.contains("магний") || context.contains("кальц")
                || context.contains("npk") || context.contains("подкорм") || context.contains("биостим")
                || context.contains("аминокислот") || context.contains("удобр") || context.contains("агрохим")) {
            return "Агропитание";
        }
        if (context.contains("озим") || context.contains("яров") || context.contains("гибрид") || context.contains("семен")) {
            return "Семена";
        }
        if (context.contains("мелиор") || context.contains("извест")) {
            return "Мелиоранты";
        }
        if (context.contains("гербиц") || context.contains("фунгиц") || context.contains("инсекти")
                || context.contains("десикант") || context.contains("протрав")
                || context.contains("роденти") || context.contains("репелент")
                || context.contains("регулятор рост") || context.contains("красител")) {
            return firstNonBlank(subcategory, "СЗР");
        }
        return "Прочее";
    }

    private String inferSubcategory(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        if (context.contains("гербиц")) return "Гербициды";
        if (context.contains("фунгиц")) return "Фунгициды";
        if (context.contains("инсекти")) return "Инсектициды";
        if (context.contains("десикант")) return "Десиканты";
        if (context.contains("протрав")) return "Протравители";
        if (context.contains("роденти")) return "Родентициды";
        if (context.contains("репелент")) return "Репеленты";
        if (context.contains("регулятор рост")) return "Регуляторы роста растений";
        if (context.contains("красител")) return "Красители семян";
        return "";
    }

    private boolean isSpecificSection(String value) {
        String normalized = TextUtils.normalizeToken(value);
        return normalized.contains("гербиц")
                || normalized.contains("фунгиц")
                || normalized.contains("инсекти")
                || normalized.contains("десикант")
                || normalized.contains("протрав")
                || normalized.contains("роденти")
                || normalized.contains("репелент")
                || normalized.contains("регулятор рост")
                || normalized.contains("красител");
    }

    private boolean isGenericPesticides(String value) {
        String normalized = TextUtils.normalizeToken(value);
        return normalized.contains("пестиц") || normalized.equals("сзр");
    }

    private String normalizeCategory(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "";
        }
        if ("Пестициды".equalsIgnoreCase(normalized)) {
            return "СЗР";
        }
        return normalized;
    }

    private String displaySection(String value) {
        return value == null || value.isBlank() ? "Прочее" : value;
    }

    private void putIfNotBlank(Map<String, String> columns, String key, String value) {
        if (value != null && !value.isBlank()) {
            columns.put(key, value.trim());
        }
    }

    private String firstRawValue(CatalogProduct product, String... keys) {
        Map<String, Object> rawData = jsonHelper.readMap(product.getRawDataJson());
        for (String key : keys) {
            Object value = rawData.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Resolution(
            String category,
            String subcategory,
            String itemType
    ) {
    }
}
