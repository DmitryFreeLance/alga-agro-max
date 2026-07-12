package ru.algaagro.maxapp.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import ru.algaagro.maxapp.util.CultureCatalog;
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
    private final ConcurrentHashMap<Long, ResearchSession> sessions = new ConcurrentHashMap<>();

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

    public CompletableFuture<Void> startResearchAsync(
            Long initiatedBy,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        ResearchSession existing = sessions.get(initiatedBy);
        if (existing != null) {
            onFailure.accept("Research уже выполняется. Используйте «Продолжить» или «Остановить» в последнем сообщении.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                waitForBitrixSyncIfNeeded();
                List<CatalogProduct> activeProducts = catalogProductRepository.findAllByActiveTrue().stream()
                        .filter(this::shouldResearchProduct)
                        .sorted((left, right) -> String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName())))
                        .toList();
                if (activeProducts.isEmpty()) {
                    onCompleted.accept("🔎 В каталоге не найдено активных товаров для переопределения культур.");
                    return;
                }
                LinkedHashSet<String> knownCultures = new LinkedHashSet<>();
                knownCultures.addAll(CultureCatalog.researchFixedOptions());
                activeProducts.forEach(product -> knownCultures.addAll(
                        CultureCatalog.normalizeForResearch(productService.getStringList(product.getCulturesJson()))));
                ResearchSession session = new ResearchSession(initiatedBy, activeProducts, new ArrayList<>(knownCultures));
                sessions.put(initiatedBy, session);
                log.info("Research session initialized. userId={}, targets={}", initiatedBy, activeProducts.size());
                processNextBatch(session, onBatchReady, onCompleted, onFailure);
            } catch (Exception e) {
                sessions.remove(initiatedBy);
                onFailure.accept(e.getMessage());
            }
        }, importExecutorService);
    }

    public CompletableFuture<Void> continueResearchAsync(
            Long initiatedBy,
            boolean runToCompletion,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        ResearchSession session = sessions.get(initiatedBy);
        if (session == null) {
            onFailure.accept("Нет активного research. Запустите /research заново.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (runToCompletion) {
                processRemainingBatches(session, onCompleted, onFailure);
            } else {
                processNextBatch(session, onBatchReady, onCompleted, onFailure);
            }
        }, importExecutorService);
    }

    public String stopResearch(Long initiatedBy) {
        ResearchSession session = sessions.remove(initiatedBy);
        if (session == null) {
            return "Активный research не найден.";
        }
        session.stopped = true;
        return "Research остановлен. Проверено " + session.processed + " из " + session.targets.size() + " товаров.";
    }

    public boolean isResearchInProgress() {
        return !sessions.isEmpty();
    }

    public boolean hasResearchSession(Long initiatedBy) {
        return initiatedBy != null && sessions.containsKey(initiatedBy);
    }

    private void processNextBatch(
            ResearchSession session,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        if (!session.processing.compareAndSet(false, true)) {
            onFailure.accept("Эта партия еще обрабатывается. Дождитесь завершения текущего шага.");
            return;
        }
        try {
            if (session.stopped) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept("Research остановлен.");
                return;
            }
            if (session.processed >= session.targets.size()) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept(buildFinalSummary(session));
                return;
            }
            BatchReport report = processBatch(session);
            boolean hasMore = report.hasMore();
            onBatchReady.accept(report);
            if (!hasMore) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept(buildFinalSummary(session));
            }
        } catch (Exception e) {
            sessions.remove(session.initiatedBy);
            onFailure.accept(e.getMessage());
        } finally {
            session.processing.set(false);
        }
    }

    private void processRemainingBatches(
            ResearchSession session,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        if (!session.processing.compareAndSet(false, true)) {
            onFailure.accept("Эта партия еще обрабатывается. Дождитесь завершения текущего шага.");
            return;
        }
        try {
            if (session.stopped) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept("Research остановлен.");
                return;
            }
            while (!session.stopped && session.processed < session.targets.size()) {
                processBatch(session);
            }
            sessions.remove(session.initiatedBy);
            onCompleted.accept(session.stopped ? "Research остановлен." : buildFinalSummary(session));
        } catch (Exception e) {
            sessions.remove(session.initiatedBy);
            onFailure.accept(e.getMessage());
        } finally {
            session.processing.set(false);
        }
    }

    private BatchReport processBatch(ResearchSession session) {
        int start = session.processed;
        int end = Math.min(session.targets.size(), start + RESEARCH_AI_BATCH_SIZE);
        List<CatalogProduct> batchProducts = session.targets.subList(start, end);
        List<ExcelImportService.ImportRow> rows = batchProducts.stream().map(this::toImportRow).toList();
        log.info("Research AI batch started. userId={}, start={}, end={}, size={}", session.initiatedBy, start + 1, end, rows.size());

        List<AiClassificationService.ClassificationResult> classified;
        try {
            classified = aiClassificationService.classifyCulturesByName(rows, session.knownCultures);
        } catch (Exception error) {
            log.warn("Research AI batch failed. userId={}, start={}, end={}, reason={}", session.initiatedBy, start + 1, end, error.getMessage());
            classified = aiClassificationService.classifyHeuristically(rows);
        }

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < batchProducts.size(); i++) {
            CatalogProduct product = batchProducts.get(i);
            AiClassificationService.ClassificationResult result = classified.get(i);
            List<String> existingCultures = normalizeValues(product.getCategory(), productService.getStringList(product.getCulturesJson()));
            List<String> cultures = normalizeValues(product.getCategory(), result.cultures());
            if (cultures.isEmpty()) {
                cultures = existingCultures;
            }
            boolean changed = !Objects.equals(existingCultures, cultures);
            String productName = firstNonBlank(product.getName(), "ID " + product.getId());

            if (!changed) {
                session.unchanged++;
                lines.add("• " + productName + " → " + formatCultures(cultures) + " [без изменений]");
                continue;
            }

            try {
                productService.updateProductCultures(product, cultures, false);
                session.updated++;
                session.bySection.merge(displaySection(product.getCategory()), 1, Integer::sum);
                lines.add("• " + productName + " → " + formatCultures(cultures) + " [обновлено]");
            } catch (Exception e) {
                session.failed++;
                session.failedProducts.add(productName);
                log.warn("Research cultures update failed for product {} (id={}): {}", product.getName(), product.getId(), e.getMessage());
                lines.add("• " + productName + " → ошибка обновления культур");
            }
        }

        session.processed = end;
        boolean hasMore = end < session.targets.size() && !session.stopped;
        return new BatchReport(buildBatchReportText(session, start + 1, end, lines, hasMore), hasMore);
    }

    private String buildBatchReportText(ResearchSession session, int start, int end, List<String> lines, boolean hasMore) {
        StringBuilder report = new StringBuilder();
        report.append("🧾 <b>Research культур</b>\n");
        report.append("Партия: <b>").append(start).append("–").append(end).append("</b> из <b>").append(session.targets.size()).append("</b>\n");
        report.append("Обновлено культур: <b>").append(session.updated).append("</b>, без изменений: <b>").append(session.unchanged)
                .append("</b>, ошибок: <b>").append(session.failed).append("</b>\n\n");
        lines.forEach(line -> report.append(line).append("\n"));
        if (hasMore) {
            report.append("\nНажмите <b>«Продолжить до конца»</b>, чтобы обработать все оставшиеся партии без промежуточных отчетов.");
        }
        return report.toString().trim();
    }

    private String buildFinalSummary(ResearchSession session) {
        StringBuilder summary = new StringBuilder();
        summary.append("🧠 <b>Research культур завершен</b>\n\n");
        summary.append("• Проверено товаров без семян: <b>").append(session.targets.size()).append("</b>\n");
        summary.append("• Обновлено культур: <b>").append(session.updated).append("</b>\n");
        summary.append("• Без изменений: <b>").append(session.unchanged).append("</b>\n");
        summary.append("• Ошибок обновления: <b>").append(session.failed).append("</b>\n");
        if (!session.bySection.isEmpty()) {
            summary.append("• Обновлено по разделам: <b>")
                    .append(session.bySection.entrySet().stream()
                            .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("—"))
                    .append("</b>\n");
        }
        if (!session.failedProducts.isEmpty()) {
            summary.append("• Проблемные позиции: <b>")
                    .append(session.failedProducts.stream().limit(8).reduce((left, right) -> left + ", " + right).orElse("—"))
                    .append(session.failedProducts.size() > 8 ? " и еще " + (session.failedProducts.size() - 8) : "")
                    .append("</b>\n");
        }
        return summary.toString().trim();
    }

    private void waitForBitrixSyncIfNeeded() {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService == null || !bitrixSyncService.isSyncInProgress()) {
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
    }

    private ExcelImportService.ImportRow toImportRow(CatalogProduct product) {
        Map<String, String> columns = new LinkedHashMap<>();
        putIfNotBlank(columns, "Позиция", product.getName());
        putIfNotBlank(columns, "Категория", product.getCategory());
        return new ExcelImportService.ImportRow(
                "product-" + product.getId(),
                "catalog",
                firstNonBlank(product.getCategory(), ""),
                product.getId() == null ? 0 : product.getId().intValue(),
                columns,
                firstNonBlank(product.getName(), ""),
                ""
        );
    }

    private boolean shouldResearchProduct(CatalogProduct product) {
        return !CatalogStructure.SEEDS.equals(CatalogStructure.normalizeSectionName(product.getCategory()));
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
        if (itemType.isBlank() || CatalogStructure.OTHER.equalsIgnoreCase(itemType)) {
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

    private String normalizeCategory(String value) {
        return firstNonBlank(CatalogStructure.normalizeSectionName(value), blankToNull(value), "");
    }

    private String displaySection(String value) {
        return value == null || value.isBlank() ? CatalogStructure.OTHER : value;
    }

    private List<String> normalizeValues(String sectionName, List<String> values) {
        return CultureCatalog.normalizeForResearch(values);
    }

    private String formatCultures(List<String> cultures) {
        return cultures == null || cultures.isEmpty() ? "культуры не определены" : String.join(", ", cultures);
    }

    private List<String> preferAiList(List<String> aiValues, List<String> existingValues) {
        if (aiValues != null) {
            List<String> normalized = aiValues.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return existingValues == null ? List.of() : existingValues;
    }

    private Map<String, Object> mergeResearchFilterMap(Map<String, Object> existing, AiClassificationService.ClassificationResult result) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        if (result.filterMap() != null) {
            result.filterMap().forEach((key, value) -> {
                if (key == null || key.isBlank() || isProtectedResearchField(key)) {
                    return;
                }
                if (value == null) {
                    return;
                }
                if (value instanceof List<?> list && list.isEmpty()) {
                    return;
                }
                if (!(value instanceof List<?>) && String.valueOf(value).isBlank()) {
                    return;
                }
                merged.put(key, value);
            });
        }
        if (result.activeIngredient() != null && !result.activeIngredient().isBlank()) {
            merged.put("activeIngredient", result.activeIngredient().trim());
        }
        return merged;
    }

    private Map<String, Object> mergeResearchRawData(
            Map<String, Object> existing,
            AiClassificationService.ClassificationResult result,
            String unitName,
            String packageDescription
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        if (result.activeIngredient() != null && !result.activeIngredient().isBlank()) {
            merged.put("Действующее вещество", result.activeIngredient().trim());
        }
        if (unitName != null && !unitName.isBlank()) {
            merged.put("Ед.изм", unitName.trim());
        }
        if (result.packageType() != null && !result.packageType().isBlank()) {
            merged.put("Тип упаковки", result.packageType().trim());
        }
        if (packageDescription != null && !packageDescription.isBlank()) {
            merged.put("Фасовка", packageDescription.trim());
        }
        return merged;
    }

    private boolean isProtectedResearchField(String key) {
        String normalized = TextUtils.normalizeToken(key);
        return normalized.equals("price")
                || normalized.contains("цена")
                || normalized.contains("discount")
                || normalized.contains("скидк")
                || normalized.contains("oldprice")
                || normalized.contains("стараяцена");
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

    public record BatchReport(String text, boolean hasMore) {
    }

    private record Resolution(String category, String subcategory, String itemType) {
    }

    private static final class ResearchSession {
        private final Long initiatedBy;
        private final List<CatalogProduct> targets;
        private final List<String> knownCultures;
        private final AtomicBoolean processing = new AtomicBoolean(false);
        private final Map<String, Integer> bySection = new LinkedHashMap<>();
        private final List<String> failedProducts = new ArrayList<>();
        private int processed;
        private int updated;
        private int unchanged;
        private int failed;
        private boolean stopped;

        private ResearchSession(Long initiatedBy, List<CatalogProduct> targets, List<String> knownCultures) {
            this.initiatedBy = initiatedBy;
            this.targets = targets;
            this.knownCultures = knownCultures;
        }
    }
}
