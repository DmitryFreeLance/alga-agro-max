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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.CatalogStructure;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ProductResearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductResearchService.class);
    private static final int RESEARCH_AI_BATCH_SIZE = 20;
    private static final Duration BITRIX_SYNC_WAIT_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration BITRIX_SYNC_POLL_INTERVAL = Duration.ofSeconds(2);

    private final ExecutorService importExecutorService;
    private final CatalogProductRepository catalogProductRepository;
    private final ProductService productService;
    private final AiClassificationService aiClassificationService;
    private final JsonHelper jsonHelper;
    private final ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider;
    private final AtomicBoolean researchInProgress = new AtomicBoolean(false);

    public ProductResearchService(
            ExecutorService importExecutorService,
            CatalogProductRepository catalogProductRepository,
            ProductService productService,
            AiClassificationService aiClassificationService,
            JsonHelper jsonHelper,
            ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider
    ) {
        this.importExecutorService = importExecutorService;
        this.catalogProductRepository = catalogProductRepository;
        this.productService = productService;
        this.aiClassificationService = aiClassificationService;
        this.jsonHelper = jsonHelper;
        this.bitrixSyncServiceProvider = bitrixSyncServiceProvider;
    }

    public CompletableFuture<Void> researchUncategorizedProductsAsync(
            Long initiatedBy,
            Consumer<String> onProgress,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        if (!researchInProgress.compareAndSet(false, true)) {
            onFailure.accept("Research уже выполняется. Дождитесь завершения текущего запуска.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture
                .supplyAsync(() -> researchUncategorizedProducts(initiatedBy, onProgress), importExecutorService)
                .thenAccept(onSuccess)
                .exceptionally(error -> {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    log.error("Research failed for {}: {}", initiatedBy, cause.getMessage(), cause);
                    onFailure.accept(cause.getMessage());
                    return null;
                })
                .whenComplete((unused, error) -> researchInProgress.set(false));
    }

    private String researchUncategorizedProducts(Long initiatedBy, Consumer<String> onProgress) {
        waitForBitrixSyncIfNeeded();
        List<CatalogProduct> activeProducts = catalogProductRepository.findAllByActiveTrue();
        List<CatalogProduct> targets = activeProducts.stream()
                .sorted((left, right) -> String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName())))
                .toList();
        log.info("Research started by {}. targets={}", initiatedBy, targets.size());
        if (targets.isEmpty()) {
            return "🔎 В каталоге не найдено активных товаров для пересмотра.";
        }

        List<ExcelImportService.ImportRow> rows = new ArrayList<>();
        for (CatalogProduct product : targets) {
            rows.add(toImportRow(product));
        }
        log.info("Research prepared import rows. count={}", rows.size());

        LinkedHashSet<String> knownCultures = new LinkedHashSet<>();
        activeProducts.forEach(product -> knownCultures.addAll(productService.getStringList(product.getCulturesJson())));
        log.info("Research prepared known cultures. count={}", knownCultures.size());
        List<AiClassificationService.ClassificationResult> classified = classifyForResearch(rows, new ArrayList<>(knownCultures));
        log.info("Research classification completed. results={}", classified.size());

        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        Map<String, Integer> bySection = new LinkedHashMap<>();
        List<String> failedProducts = new ArrayList<>();
        List<String> resolvedProducts = new ArrayList<>();
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
                resolvedProducts.add("• " + firstNonBlank(product.getName(), "ID " + product.getId())
                        + " → " + resolution.category()
                        + (resolution.subcategory() == null || resolution.subcategory().isBlank() ? "" : " / " + resolution.subcategory())
                        + " [без изменений]");
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
                resolvedProducts.add("• " + firstNonBlank(product.getName(), "ID " + product.getId())
                        + " → " + resolution.category()
                        + (resolution.subcategory() == null || resolution.subcategory().isBlank() ? "" : " / " + resolution.subcategory())
                        + " [обновлено]");
            } catch (Exception e) {
                failed++;
                failedProducts.add(firstNonBlank(product.getName(), "ID " + product.getId()));
                log.warn("Research update failed for product {} (id={}): {}", product.getName(), product.getId(), e.getMessage());
                resolvedProducts.add("• " + firstNonBlank(product.getName(), "ID " + product.getId()) + " → ошибка обновления");
            }
        }

        emitProgressReports(onProgress, resolvedProducts, targets.size());

        StringBuilder summary = new StringBuilder();
        summary.append("🧠 <b>Research завершен</b>\n\n");
        summary.append("• Инициатор: <b>").append(initiatedBy == null ? "admin" : initiatedBy).append("</b>\n");
        summary.append("• Проверено товаров: <b>").append(targets.size()).append("</b>\n");
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
        summary.append("\nПодробная раскладка по товарам отправлена отдельными сообщениями в ходе research.");
        return summary.toString();
    }

    private void waitForBitrixSyncIfNeeded() {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService == null) {
            return;
        }
        if (!bitrixSyncService.isSyncInProgress()) {
            return;
        }
        log.info("Research is waiting for Bitrix sync to finish");
        long deadline = System.currentTimeMillis() + BITRIX_SYNC_WAIT_TIMEOUT.toMillis();
        while (bitrixSyncService.isSyncInProgress() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(BITRIX_SYNC_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Research прерван во время ожидания завершения синхронизации Bitrix.", e);
            }
        }
        if (bitrixSyncService.isSyncInProgress()) {
            throw new IllegalStateException("Bitrix синхронизация все еще выполняется. Повторите /research чуть позже.");
        }
        log.info("Research resumed after Bitrix sync");
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

    private void emitProgressReports(Consumer<String> onProgress, List<String> resolvedProducts, int total) {
        if (onProgress == null || resolvedProducts.isEmpty()) {
            return;
        }
        int batchSize = 25;
        int sent = 0;
        int part = 1;
        for (int start = 0; start < resolvedProducts.size(); start += batchSize) {
            int end = Math.min(resolvedProducts.size(), start + batchSize);
            sent = end;
            StringBuilder report = new StringBuilder();
            report.append("🧾 <b>Research-отчет</b> · часть ").append(part++).append("\n");
            report.append("Проверено: <b>").append(sent).append("</b> из <b>").append(total).append("</b>\n\n");
            for (String line : resolvedProducts.subList(start, end)) {
                report.append(line).append("\n");
            }
            onProgress.accept(report.toString().trim());
        }
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
        return java.util.stream.Stream.of(
                        product.getName(),
                        product.getDescription(),
                        product.getBrand(),
                        product.getCategory(),
                        product.getSubcategory(),
                        product.getItemType(),
                        firstRawValue(product, "Состав", "Действующее вещество", "действующее вещество"),
                        firstRawValue(product, "Норма расхода", "Расход", "дозировка")
                )
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
    }

    private Resolution resolveSection(CatalogProduct product, AiClassificationService.ClassificationResult result, ExcelImportService.ImportRow row) {
        String context = row.section() + " " + row.nameGuess() + " " + row.columns();
        String category = CatalogStructure.normalizeSectionName(result.category());
        String subcategory = firstNonBlank(
                CatalogStructure.inferSubcategory(category, firstNonBlank(result.subcategory(), context)),
                CatalogStructure.inferSubcategory(CatalogStructure.inferSection(context, result.subcategory()), context),
                result.subcategory(),
                inferSubcategory(row)
        );
        String itemType = normalizeCategory(firstNonBlank(result.itemType(), subcategory, category));

        if (category.isBlank() || CatalogStructure.OTHER.equalsIgnoreCase(category)) {
            category = inferCategory(row, subcategory);
        }

        subcategory = firstNonBlank(subcategory, CatalogStructure.inferSubcategory(category, context));
        if (itemType.isBlank() || "Прочее".equalsIgnoreCase(itemType)) {
            itemType = firstNonBlank(subcategory, category, "Товар");
        }
        return new Resolution(
                category.isBlank() ? CatalogStructure.OTHER : category,
                blankToNull(subcategory),
                itemType
        );
    }

    private String inferCategory(ExcelImportService.ImportRow row, String subcategory) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        return CatalogStructure.inferSection(context, subcategory);
    }

    private String inferSubcategory(ExcelImportService.ImportRow row) {
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        return CatalogStructure.inferSubcategory(CatalogStructure.inferSection(context, ""), context);
    }

    private boolean isSpecificSection(String value) {
        return !CatalogStructure.normalizePesticideSubcategory(value).isBlank();
    }

    private boolean isGenericPesticides(String value) {
        return CatalogStructure.PESTICIDES.equalsIgnoreCase(CatalogStructure.normalizeSectionName(value));
    }

    private String normalizeCategory(String value) {
        return firstNonBlank(CatalogStructure.normalizeSectionName(value), blankToNull(value), "");
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
