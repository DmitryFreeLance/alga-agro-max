package ru.algaagro.maxapp.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.ImportJob;
import ru.algaagro.maxapp.model.ImportStatus;
import ru.algaagro.maxapp.repository.ImportJobRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);

    private final ExecutorService importExecutorService;
    private final ImportJobRepository importJobRepository;
    private final ProductService productService;
    private final AiClassificationService aiClassificationService;
    private final JsonHelper jsonHelper;
    private final AppProperties appProperties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final DataFormatter dataFormatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));

    public ExcelImportService(
            ExecutorService importExecutorService,
            ImportJobRepository importJobRepository,
            ProductService productService,
            AiClassificationService aiClassificationService,
            JsonHelper jsonHelper,
            AppProperties appProperties
    ) {
        this.importExecutorService = importExecutorService;
        this.importJobRepository = importJobRepository;
        this.productService = productService;
        this.aiClassificationService = aiClassificationService;
        this.jsonHelper = jsonHelper;
        this.appProperties = appProperties;
    }

    @Transactional
    public ImportJob createJob(Long initiatedBy, List<Map<String, Object>> files) {
        ImportJob job = new ImportJob();
        job.setInitiatedByMaxUserId(initiatedBy);
        job.setStatus(ImportStatus.PENDING);
        job.setSourceFilesJson(jsonHelper.writeValue(files));
        job.setPreviewJson("[]");
        return importJobRepository.save(job);
    }

    public Optional<ImportJob> findJob(Long jobId) {
        return importJobRepository.findById(jobId);
    }

    public CompletableFuture<Void> analyzeAsync(ImportJob job, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
        return CompletableFuture.runAsync(() -> analyzeJob(job), importExecutorService)
                .thenRun(onSuccess)
                .exceptionally(error -> {
                    onFailure.accept(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                    return null;
                });
    }

    public CompletableFuture<Void> applyAsync(Long jobId, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
        return CompletableFuture.runAsync(() -> applyJob(jobId), importExecutorService)
                .thenRun(onSuccess)
                .exceptionally(error -> {
                    onFailure.accept(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                    return null;
                });
    }

    @Transactional
    public void cancelJob(Long jobId) {
        ImportJob job = importJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Задача импорта не найдена"));
        job.setStatus(ImportStatus.CANCELLED);
        if (job.getSummary() == null || job.getSummary().isBlank()) {
            job.setSummary("Импорт отменен пользователем.");
        }
        importJobRepository.save(job);
    }

    @Transactional
    public void analyzeJob(ImportJob job) {
        try {
            job.setStatus(ImportStatus.PROCESSING);
            importJobRepository.save(job);

            AnalyzedImport analyzedImport = analyze(job);
            job.setStatus(ImportStatus.PREVIEW_READY);
            job.setPreviewJson(jsonHelper.writeValue(analyzedImport.stagedProducts()));
            job.setSummary(buildPreviewSummary(analyzedImport));
            importJobRepository.save(job);
        } catch (RuntimeException e) {
            job.setStatus(ImportStatus.FAILED);
            job.setSummary("Не удалось подготовить импорт: " + TextUtils.trimTo(e.getMessage(), 500));
            importJobRepository.save(job);
            throw e;
        }
    }

    @Transactional
    public void applyJob(Long jobId) {
        ImportJob job = importJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Задача импорта не найдена"));
        try {
            if (job.getStatus() != ImportStatus.PREVIEW_READY) {
                throw new IllegalStateException("Импорт уже применен, отменен или еще не готов к подтверждению.");
            }
            List<StagedImportProduct> stagedProducts = jsonHelper.readValue(
                    job.getPreviewJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { },
                    List.of()
            );
            if (stagedProducts.isEmpty()) {
                throw new IllegalStateException("Нет подготовленных данных для применения.");
            }
            AppliedImportResult result = applyStagedProducts(stagedProducts);
            job.setStatus(ImportStatus.COMPLETED);
            job.setSummary(buildAppliedSummary(job, stagedProducts, result));
            importJobRepository.save(job);
        } catch (RuntimeException e) {
            job.setStatus(ImportStatus.FAILED);
            job.setSummary("Не удалось применить импорт: " + TextUtils.trimTo(e.getMessage(), 500));
            importJobRepository.save(job);
            throw e;
        }
    }

    private AnalyzedImport analyze(ImportJob job) {
        List<Map<String, Object>> files = jsonHelper.readValue(job.getSourceFilesJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of());
        List<ImportRow> rows = new ArrayList<>();
        for (Map<String, Object> file : files) {
            rows.addAll(parseFile(file));
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("Не удалось извлечь строки из Excel-файлов.");
        }

        Set<String> knownCultures = new LinkedHashSet<>();
        productService.getActiveProducts().forEach(product -> knownCultures.addAll(productService.getStringList(product.getCulturesJson())));
        Set<String> knownCategories = new LinkedHashSet<>();
        productService.getActiveProducts().stream()
                .map(product -> TextUtils.trimTo(product.getCategory(), 200))
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .forEach(knownCategories::add);
        List<AiClassificationService.ClassificationResult> classified = aiClassificationService.classify(rows, new ArrayList<>(knownCultures));

        Set<String> detectedCategories = new LinkedHashSet<>();
        Set<String> detectedCultures = new LinkedHashSet<>();
        Set<String> detectedPurposes = new LinkedHashSet<>();
        List<StagedImportProduct> stagedProducts = new ArrayList<>();
        int extractedNames = 0;
        int extractedSections = 0;
        int extractedComposition = 0;
        int extractedRate = 0;
        int extractedPrice = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            AiClassificationService.ClassificationResult result = classified.get(i);
            String resolvedName = !result.normalizedName().isBlank() ? result.normalizedName() : row.nameGuess();
            String resolvedCategory = resolveCategory(result, row);
            String resolvedSubcategory = resolveSubcategory(result, row);
            String resolvedItemType = resolveItemType(result, resolvedCategory, resolvedSubcategory, row);
            String resolvedDescription = resolveDescription(result, row);
            ProductService.OrderRules orderRules = productService.inferOrderRules(
                    resolvedName,
                    extractUnit(row.columns()),
                    resolvedDescription,
                    row.columns(),
                    result.filterMap()
            );
            ProductService.ImportedProduct product = new ProductService.ImportedProduct(
                    buildExternalId(row),
                    row.sourceFile(),
                    extractSku(row.columns()),
                    resolvedName,
                    resolvedDescription,
                    result.brand(),
                    resolvedCategory,
                    resolvedSubcategory,
                    resolvedItemType,
                    extractUnit(row.columns()),
                    extractPrice(row.columns()),
                    extractStock(row.columns()),
                    orderRules.packageType(),
                    orderRules.packageDescription(),
                    orderRules.minOrderQuantity(),
                    orderRules.orderStep(),
                    result.cultures(),
                    result.purposes(),
                    result.tags(),
                    result.filterMap(),
                    row.columns()
            );
            stagedProducts.add(new StagedImportProduct(
                    product.externalId(),
                    product.sourceFile(),
                    product.sku(),
                    product.name(),
                    product.description(),
                    product.brand(),
                    product.category(),
                    product.subcategory(),
                    product.itemType(),
                    product.unitName(),
                    product.price(),
                    product.stockQuantity(),
                    product.packageType(),
                    product.packageDescription(),
                    product.minOrderQuantity(),
                    product.orderStep(),
                    product.cultures(),
                    product.purposes(),
                    product.tags(),
                    product.filterMap(),
                    product.rawData(),
                    row.section(),
                    firstPresent(row.columns(), "состав", "composition"),
                    firstPresent(row.columns(), "норма расхода", "расход", "дозиров"),
                    firstPresent(row.columns(), "цена", "price")
            ));
            if (resolvedCategory != null && !resolvedCategory.isBlank()) {
                detectedCategories.add(resolvedCategory);
            }
            detectedCultures.addAll(product.cultures());
            detectedPurposes.addAll(product.purposes());
            if (!resolvedName.isBlank()) {
                extractedNames++;
            }
            if (row.section() != null && !row.section().isBlank()) {
                extractedSections++;
            }
            if (!firstPresent(row.columns(), "состав", "composition").isBlank()) {
                extractedComposition++;
            }
            if (!firstPresent(row.columns(), "норма расхода", "расход", "дозиров").isBlank()) {
                extractedRate++;
            }
            if (extractPrice(row.columns()) != null) {
                extractedPrice++;
            }
        }
        Set<String> newCategories = detectedCategories.stream()
                .filter(category -> knownCategories.stream().noneMatch(existing -> existing.equalsIgnoreCase(category)))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> newCultures = detectedCultures.stream()
                .filter(culture -> knownCultures.stream().noneMatch(existing -> existing.equalsIgnoreCase(culture)))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        return new AnalyzedImport(
                files,
                rows,
                stagedProducts,
                detectedCategories,
                detectedCultures,
                detectedPurposes,
                newCategories,
                newCultures,
                extractedNames,
                extractedSections,
                extractedComposition,
                extractedRate,
                extractedPrice
        );
    }

    private String buildPreviewSummary(AnalyzedImport analyzedImport) {
        StringBuilder summary = new StringBuilder();
        summary.append("🧠 <b>Анализ номенклатуры завершен</b>\n\n");
        summary.append("<b>Что проанализировано</b>\n");
        summary.append("• Файлов: <b>").append(analyzedImport.files().size()).append("</b>\n");
        summary.append("• Строк в прайсах: <b>").append(analyzedImport.rows().size()).append("</b>\n");
        summary.append("• Товаров к загрузке после очистки служебных строк: <b>").append(analyzedImport.stagedProducts().size()).append("</b>\n");

        summary.append("\n<b>Какие данные извлечены</b>\n");
        summary.append("• Название товара: <b>").append(analyzedImport.extractedNames()).append("/").append(analyzedImport.rows().size()).append("</b>\n");
        summary.append("• Раздел / группа: <b>").append(analyzedImport.extractedSections()).append("/").append(analyzedImport.rows().size()).append("</b>\n");
        summary.append("• Состав: <b>").append(analyzedImport.extractedComposition()).append("/").append(analyzedImport.rows().size()).append("</b>\n");
        summary.append("• Норма расхода: <b>").append(analyzedImport.extractedRate()).append("/").append(analyzedImport.rows().size()).append("</b>\n");
        summary.append("• Цена: <b>").append(analyzedImport.extractedPrice()).append("/").append(analyzedImport.rows().size()).append("</b>\n");

        Map<String, Long> categoryDistribution = countByCategory(analyzedImport.stagedProducts());
        if (!categoryDistribution.isEmpty()) {
            summary.append("\n<b>В какие категории пойдут товары</b>\n");
            summary.append("• ").append(formatCountMap(categoryDistribution, 8)).append("\n");
        }
        if (!analyzedImport.detectedCultures().isEmpty()) {
            summary.append("• Культуры: ").append(formatPreviewList(analyzedImport.detectedCultures(), 8)).append("\n");
        }
        if (!analyzedImport.detectedPurposes().isEmpty()) {
            summary.append("• Назначения: ").append(formatPreviewList(analyzedImport.detectedPurposes(), 6)).append("\n");
        }
        if (!analyzedImport.newCategories().isEmpty()) {
            summary.append("• Новые категории: ").append(formatPreviewList(analyzedImport.newCategories(), 5)).append("\n");
        }
        if (!analyzedImport.newCultures().isEmpty()) {
            summary.append("• Новые культуры: ").append(formatPreviewList(analyzedImport.newCultures(), 6)).append("\n");
        }

        List<String> fileNames = analyzedImport.files().stream()
                .map(file -> Objects.toString(file.get("name"), "").trim())
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (!fileNames.isEmpty()) {
            summary.append("\n<b>Какие файлы учтены</b>\n");
            summary.append("• ").append(formatPreviewList(new LinkedHashSet<>(fileNames), 4)).append("\n");
        }

        summary.append("\n<b>Примеры карточек после загрузки</b>\n");
        analyzedImport.stagedProducts().stream()
                .limit(6)
                .forEach(item -> summary.append("• ")
                        .append(TextUtils.trimTo(item.name(), 42))
                        .append(" → <b>").append(item.category()).append("</b>")
                        .append(item.subcategory() == null || item.subcategory().isBlank() ? "" : " / " + item.subcategory())
                        .append(item.price() == null ? "" : " — " + TextUtils.formatPrice(item.price()) + formatUnitSuffix(item.unitName()))
                        .append(item.composition() == null || item.composition().isBlank() ? "" : " • Состав: " + TextUtils.trimTo(item.composition(), 55))
                        .append(item.dosage() == null || item.dosage().isBlank() ? "" : " • Расход: " + TextUtils.trimTo(item.dosage(), 32))
                        .append("\n"));
        summary.append("\nДанные <b>еще не добавлены</b> в каталог.\n");
        summary.append("Нажмите <b>«Подтвердить импорт»</b>, чтобы записать товары в базу, или <b>«Отменить импорт»</b>, чтобы ничего не добавлять.");
        return summary.toString().trim();
    }

    private String buildAppliedSummary(ImportJob job, List<StagedImportProduct> stagedProducts, AppliedImportResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append("✅ <b>Импорт подтвержден и применен</b>\n\n");
        summary.append("• Файлов обработано: <b>").append(jsonHelper.readValue(job.getSourceFilesJson(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }, List.of()).size()).append("</b>\n");
        summary.append("• Добавлено товаров: <b>").append(result.created()).append("</b>\n");
        summary.append("• Обновлено товаров: <b>").append(result.updated()).append("</b>\n");
        summary.append("• Без изменений: <b>").append(result.unchanged()).append("</b>\n");
        summary.append("• Скрыто старых позиций: <b>").append(result.deactivated()).append("</b>\n");
        summary.append("• Всего применено: <b>").append(stagedProducts.size()).append("</b>\n");
        summary.append("• Категории: ").append(formatCountMap(countByCategory(stagedProducts), 6));
        return summary.toString();
    }

    private String formatPreviewList(Set<String> values, int limit) {
        List<String> ordered = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (ordered.isEmpty()) {
            return "—";
        }
        int safeLimit = Math.max(limit, 1);
        String visible = ordered.stream()
                .limit(safeLimit)
                .map(value -> TextUtils.trimTo(value, 32))
                .reduce((left, right) -> left + ", " + right)
                .orElse("—");
        int hidden = ordered.size() - Math.min(ordered.size(), safeLimit);
        if (hidden > 0) {
            visible += " и еще " + hidden;
        }
        return visible;
    }

    private Map<String, Long> countByCategory(List<StagedImportProduct> stagedProducts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (StagedImportProduct item : stagedProducts) {
            String key = item.category() == null || item.category().isBlank() ? "Прочее" : item.category();
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        }
        return counts;
    }

    private String formatCountMap(Map<String, Long> counts, int limit) {
        List<String> items = counts.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();
        LinkedHashSet<String> preview = new LinkedHashSet<>(items.stream().limit(limit).toList());
        return formatPreviewList(preview, limit);
    }

    private String formatUnitSuffix(String unitName) {
        if (unitName == null || unitName.isBlank()) {
            return "";
        }
        return "/" + unitName;
    }

    private List<ImportRow> parseFile(Map<String, Object> fileMeta) {
        String url = Objects.toString(fileMeta.get("url"), "");
        String fileName = Objects.toString(fileMeta.get("name"), "import.xlsx");
        if (url.isBlank()) {
            return List.of();
        }
        byte[] bytes = download(url);
        List<ImportRow> rows;
        if (isPdfFileName(fileName)) {
            rows = parsePdfFile(bytes, fileName);
        } else if (isWordFileName(fileName)) {
            rows = parseWordFile(bytes, fileName);
        } else {
            rows = parseWorkbookFile(bytes, fileName);
        }
        List<ImportRow> filteredRows = filterNoiseRows(rows);
        if (shouldUseAiFileFallback(fileName, filteredRows)) {
            log.info("Local parsing looks weak for {}. Trying direct AI file extraction. localRows={}, localScore={}",
                    fileName,
                    filteredRows.size(),
                    scoreParsedRows(filteredRows));
            List<ImportRow> aiRows = filterNoiseRows(aiClassificationService.extractRowsFromOriginalFile(fileName, bytes));
            List<ImportRow> bestRows = chooseBestParsedRows(aiRows, filteredRows);
            if (bestRows == aiRows && !aiRows.isEmpty()) {
                log.info("Direct AI file extraction selected for {}. aiRows={}, aiScore={}",
                        fileName,
                        aiRows.size(),
                        scoreParsedRows(aiRows));
            }
            return bestRows;
        }
        return filteredRows;
    }

    private boolean isPdfFileName(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private boolean isWordFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".docx") || normalized.endsWith(".doc");
    }

    private boolean shouldUseAiFileFallback(String fileName, List<ImportRow> rows) {
        if (!appProperties.getAi().isDirectFileFallbackEnabled()) {
            return false;
        }
        if (rows == null || rows.isEmpty()) {
            return true;
        }
        boolean documentLike = isPdfFileName(fileName) || isWordFileName(fileName);
        boolean workbookLike = fileName != null && fileName.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xlsm|xls)$");
        if (!documentLike && !workbookLike) {
            return false;
        }
        long pricedRows = rows.stream()
                .filter(row -> findFirst(row.columns(), "цена", "price", "стоимость").isPresent())
                .count();
        long rawOnlyRows = rows.stream()
                .filter(row -> row.columns().size() <= 2 && row.columns().containsKey("Сырой текст"))
                .count();
        long longNameRows = rows.stream()
                .filter(row -> {
                    String name = row.nameGuess() == null ? "" : row.nameGuess().trim();
                    return name.length() > 110 || name.split("\\s+").length > 12;
                })
                .count();
        int score = scoreParsedRows(rows);
        if (rows.size() < appProperties.getAi().getDirectFileFallbackMinRows()) {
            return true;
        }
        if (documentLike && pricedRows == 0) {
            return true;
        }
        if (rawOnlyRows * 2 >= rows.size()) {
            return true;
        }
        if (longNameRows * 2 >= rows.size()) {
            return true;
        }
        return score < Math.max(12, rows.size() * 2);
    }

    private List<ImportRow> parseWorkbookFile(byte[] bytes, String fileName) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<ImportRow> rows = new ArrayList<>();
            for (Sheet sheet : workbook) {
                List<List<String>> matrix = readSheetMatrix(sheet);
                List<ImportRow> parsed = parseStructuredGrid(matrix, fileName, sheet.getSheetName());
                if (parsed.isEmpty()) {
                    parsed = parseGenericWorkbookGrid(matrix, fileName, sheet.getSheetName());
                }
                rows.addAll(parsed);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл " + fileName, e);
        }
    }

    private List<ImportRow> parseWordFile(byte[] bytes, String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".docx")) {
            return parseDocxFile(bytes, fileName);
        }
        if (normalized.endsWith(".doc")) {
            return parseDocFile(bytes, fileName);
        }
        return List.of();
    }

    private List<ImportRow> parseDocxFile(byte[] bytes, String fileName) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<ImportRow> tableRows = new ArrayList<>();
            int tableIndex = 0;
            for (XWPFTable table : document.getTables()) {
                List<List<String>> matrix = new ArrayList<>();
                for (XWPFTableRow row : table.getRows()) {
                    List<String> values = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        values.add(cell.getText() == null ? "" : cell.getText().trim());
                    }
                    if (values.stream().anyMatch(value -> value != null && !value.isBlank())) {
                        matrix.add(values);
                    }
                }
                if (!matrix.isEmpty()) {
                    List<ImportRow> parsed = parseStructuredGrid(matrix, fileName, "DOCX-" + (++tableIndex));
                    if (parsed.isEmpty()) {
                        parsed = parseGenericWorkbookGrid(matrix, fileName, "DOCX-" + tableIndex);
                    }
                    tableRows.addAll(parsed);
                }
            }
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                List<ImportRow> textRows = parseTextDocument(extractor.getText(), fileName, "DOCX");
                return filterNoiseRows(chooseBestParsedRows(tableRows, textRows));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать Word-файл " + fileName, e);
        }
    }

    private List<ImportRow> parseDocFile(byte[] bytes, String fileName) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            return filterNoiseRows(parseTextDocument(extractor.getText(), fileName, "DOC"));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать Word-файл " + fileName, e);
        }
    }

    private List<ImportRow> parseStructuredGrid(List<List<String>> matrix, String fileName, String sheetName) {
        if (matrix.isEmpty()) {
            return List.of();
        }
        int headerRowIndex = detectHeaderRowIndex(matrix);
        if (headerRowIndex < 0) {
            return List.of();
        }
        List<String> headers = readHeaders(matrix, headerRowIndex);
        List<MutableImportRow> parsedRows = new ArrayList<>();
        String currentSection = "";
        for (int rowIndex = headerRowIndex + 1; rowIndex < matrix.size(); rowIndex++) {
            List<String> rawValues = readRowValues(matrix, rowIndex, headers.size());
            if (rowIsEmpty(rawValues)) {
                continue;
            }
            if (isHeaderLikeRow(rawValues)) {
                headers = readHeaders(matrix, rowIndex);
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
                String header = headers.get(cellIndex);
                if (header == null || header.isBlank()) {
                    continue;
                }
                columns.put(header, cellIndex < rawValues.size() ? Objects.toString(rawValues.get(cellIndex), "").trim() : "");
            }
            if (columns.values().stream().allMatch(String::isBlank)) {
                continue;
            }
            String position = extractName(columns);
            if (shouldAppendToPrevious(columns, position, parsedRows)) {
                appendToPrevious(parsedRows.get(parsedRows.size() - 1), columns, position);
                continue;
            }
            if (isSectionRow(columns, position)) {
                currentSection = position;
                continue;
            }
            if (!currentSection.isBlank()) {
                columns.put("Раздел", currentSection);
            }
            parsedRows.add(new MutableImportRow(
                    fileName + "#" + sheetName + "#" + rowIndex,
                    fileName,
                    sheetName,
                    rowIndex,
                    columns,
                    extractName(columns),
                    currentSection
            ));
        }
        return parsedRows.stream()
                .map(MutableImportRow::toImmutable)
                .toList();
    }

    private List<ImportRow> parseTextDocument(String text, String fileName, String sheetName) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = extractDocumentLines(text);
        List<List<ImportRow>> candidates = List.of(
                parseChemicalVerticalDocument(lines, fileName, sheetName),
                parseCornSeedDocument(lines, fileName, sheetName),
                parseGeneralSeedDocument(lines, fileName, sheetName),
                parseInlineCornSeedDocument(lines, fileName, sheetName),
                parseInlineSeedDocument(lines, fileName, sheetName),
                parseWinterSeedDocument(lines, fileName, sheetName),
                parseQuoteDocument(lines, fileName, sheetName)
        );
        List<ImportRow> bestCandidate = candidates.stream()
                .max(java.util.Comparator.comparingInt(List::size))
                .orElse(List.of());
        if (bestCandidate.size() >= 3) {
            return bestCandidate;
        }
        List<MutableImportRow> parsedRows = new ArrayList<>();
        String currentSection = "";
        int rowNumber = 0;
        for (String rawLine : lines) {
            String line = normalizePdfLine(rawLine);
            if (line.isBlank() || isPdfNoiseLine(line)) {
                continue;
            }
            if (looksLikePdfSection(line)) {
                currentSection = line.replaceAll("[:;]+$", "").trim();
                continue;
            }
            if (shouldAppendPdfLineToPrevious(line, parsedRows)) {
                appendPdfLineToPrevious(parsedRows.get(parsedRows.size() - 1), line);
                continue;
            }
            Map<String, String> columns = buildPdfColumns(line, currentSection);
            String position = extractName(columns);
            if (position.isBlank()) {
                continue;
            }
            if (!currentSection.isBlank()) {
                columns.put("Раздел", currentSection);
            }
            rowNumber++;
            parsedRows.add(new MutableImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    position,
                    currentSection
            ));
        }
        return parsedRows.stream()
                .map(MutableImportRow::toImmutable)
                .toList();
    }

    private List<String> extractDocumentLines(String text) {
        return Arrays.stream(text.split("\\R"))
                .map(this::normalizePdfLine)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private List<ImportRow> parseGenericWorkbookGrid(List<List<String>> matrix, String fileName, String sheetName) {
        if (matrix.isEmpty()) {
            return List.of();
        }
        List<ImportRow> rows = new ArrayList<>();
        String currentSection = "";
        int rowNumber = 0;
        for (int rowIndex = 0; rowIndex < matrix.size(); rowIndex++) {
            List<String> source = matrix.get(rowIndex);
            List<String> values = source.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank())
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            if (values.size() == 1 && !looksLikeLoosePrice(values.get(0)) && values.get(0).length() < 80) {
                currentSection = values.get(0);
                continue;
            }
            if (values.size() < 3) {
                continue;
            }
            if (values.size() >= 6 && looksLikeInteger(values.get(0)) && !findCornMaturityGroup(values.get(1)).isBlank() && looksLikeLoosePrice(values.get(values.size() - 1))) {
                String maturityGroup = values.get(1);
                String name = values.get(2);
                String fao = values.get(3);
                String regions = values.get(4);
                String price = values.get(values.size() - 1);
                Map<String, String> columns = new LinkedHashMap<>();
                columns.put("Позиция", name);
                columns.put("Группа спелости", maturityGroup);
                columns.put("ФАО", fao);
                columns.put("Регионы допуска", regions);
                columns.put("Цена", price);
                columns.put("Сырой текст", String.join(" | ", values));
                rowNumber++;
                rows.add(new ImportRow(
                        fileName + "#" + sheetName + "#" + rowIndex,
                        fileName,
                        sheetName,
                        rowNumber,
                        columns,
                        name,
                        "Кукуруза"
                ));
                continue;
            }
            if (values.size() == 3 && looksLikeInteger(values.get(0)) && looksLikeLoosePrice(values.get(2))) {
                String details = values.get(1);
                List<String> detailParts = Arrays.stream(details.split("\\s*,\\s*"))
                        .map(String::trim)
                        .filter(part -> !part.isBlank())
                        .toList();
                String name = detailParts.isEmpty() ? details : String.join(", ", detailParts.subList(0, Math.max(1, detailParts.size() - 1)));
                String packaging = detailParts.size() > 1 ? detailParts.get(detailParts.size() - 1) : "";
                Map<String, String> columns = new LinkedHashMap<>();
                columns.put("Позиция", name);
                columns.put("Сырой текст", String.join(" | ", values));
                if (!currentSection.isBlank()) {
                    columns.put("Раздел", currentSection);
                }
                if (!packaging.isBlank()) {
                    columns.put("Упаковка", packaging);
                }
                columns.put("Цена", values.get(2));
                rowNumber++;
                rows.add(new ImportRow(
                        fileName + "#" + sheetName + "#" + rowIndex,
                        fileName,
                        sheetName,
                        rowNumber,
                        columns,
                        name,
                        currentSection
                ));
                continue;
            }
            int start = looksLikeInteger(values.get(0)) && values.size() >= 4 ? 1 : 0;
            String name = valueAt(values, start);
            String second = valueAt(values, start + 1);
            String packaging = valueAt(values, values.size() - 2);
            String price = valueAt(values, values.size() - 1);
            if (name.isBlank() || price.isBlank() || !looksLikeLoosePrice(price)) {
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            columns.put("Сырой текст", String.join(" | ", values));
            if (!currentSection.isBlank()) {
                columns.put("Раздел", currentSection);
            }
            if (!second.isBlank() && !second.equals(packaging) && !second.equals(price)) {
                columns.put("Состав", second);
            }
            if (!packaging.isBlank() && !packaging.equals(price) && !packaging.equals(name)) {
                columns.put("Упаковка", packaging);
            }
            columns.put("Цена", price);
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowIndex,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    currentSection
            ));
        }
        return rows;
    }

    private List<ImportRow> parseChemicalVerticalDocument(List<String> lines, String fileName, String sheetName) {
        int headerIndex = findLineIndex(lines, "препарат");
        int compositionIndex = findLineIndex(lines, "действующее вещество");
        int dosageIndex = findLineIndex(lines, "норма расхода");
        if (headerIndex < 0 || compositionIndex < 0 || dosageIndex < 0) {
            return List.of();
        }
        int startIndex = Math.max(Math.max(headerIndex, compositionIndex), dosageIndex) + 1;
        List<ImportRow> rows = new ArrayList<>();
        String currentSection = "";
        int rowNumber = 0;
        int index = startIndex;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (isLikelyCorporateNoiseLine(line)) {
                index++;
                continue;
            }
            if (isLikelyPesticideSection(line)) {
                currentSection = line;
                index++;
                continue;
            }
            if (looksLikeChemicalName(line)) {
                String name = line;
                index++;
                List<String> compositionParts = new ArrayList<>();
                while (index < lines.size() && !looksLikeDosageLine(lines.get(index)) && !isLikelyPesticideSection(lines.get(index))) {
                    if (!isLikelyCorporateNoiseLine(lines.get(index))) {
                        compositionParts.add(lines.get(index));
                    }
                    index++;
                }
                String dosage = index < lines.size() ? lines.get(index) : "";
                if (looksLikeDosageLine(dosage)) {
                    index++;
                } else {
                    dosage = "";
                }
                String registrant = "";
                while (index < lines.size() && !looksLikeLoosePrice(lines.get(index)) && !looksLikeChemicalName(lines.get(index)) && !isLikelyPesticideSection(lines.get(index))) {
                    String candidate = lines.get(index);
                    if (!isLikelyCorporateNoiseLine(candidate) && !candidate.equalsIgnoreCase("Да") && !candidate.matches("^\\d+[.]\\d+$")) {
                        registrant = registrant.isBlank() ? candidate : registrant + " " + candidate;
                    }
                    index++;
                }
                String price = index < lines.size() && looksLikeLoosePrice(lines.get(index)) ? lines.get(index++) : "";
                if (price.isBlank()) {
                    continue;
                }
                Map<String, String> columns = new LinkedHashMap<>();
                columns.put("Позиция", name);
                columns.put("Сырой текст", String.join(" | ", compositionParts));
                if (!currentSection.isBlank()) {
                    columns.put("Раздел", currentSection);
                }
                if (!compositionParts.isEmpty()) {
                    columns.put("Состав", String.join(" ", compositionParts).trim());
                }
                if (!dosage.isBlank()) {
                    columns.put("Норма расхода", dosage);
                }
                if (!registrant.isBlank()) {
                    columns.put("Производитель", registrant);
                }
                columns.put("Цена", price);
                rowNumber++;
                rows.add(new ImportRow(
                        fileName + "#" + sheetName + "#" + rowNumber,
                        fileName,
                        sheetName,
                        rowNumber,
                        columns,
                        name,
                        currentSection
                ));
                continue;
            }
            index++;
        }
        return rows;
    }

    private List<ImportRow> parseCornSeedDocument(List<String> lines, String fileName, String sheetName) {
        int headerIndex = findLineIndex(lines, "группа спелости");
        int faoIndex = findLineIndex(lines, "фао");
        int priceIndex = findLineIndex(lines, "цена п.е");
        if (headerIndex < 0 || faoIndex < 0 || priceIndex < 0) {
            return List.of();
        }
        int startIndex = Math.max(Math.max(headerIndex, faoIndex), priceIndex) + 1;
        List<ImportRow> rows = new ArrayList<>();
        int rowNumber = 0;
        for (int index = startIndex; index + 5 < lines.size(); ) {
            if (!looksLikeInteger(lines.get(index))) {
                index++;
                continue;
            }
            String maturity = lines.get(index + 1);
            String name = lines.get(index + 2);
            String fao = lines.get(index + 3);
            String regions = lines.get(index + 4);
            String price = lines.get(index + 5);
            if (!looksLikeLoosePrice(price)) {
                index++;
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            columns.put("Секция", maturity);
            columns.put("ФАО", fao);
            columns.put("Регионы допуска", regions);
            columns.put("Цена", price);
            columns.put("Сырой текст", String.join(" | ", List.of(maturity, name, fao, regions, price)));
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    "Кукуруза"
            ));
            index += 6;
        }
        return rows;
    }

    private List<ImportRow> parseGeneralSeedDocument(List<String> lines, String fileName, String sheetName) {
        int headerIndex = findLineIndex(lines, "культура");
        int sortIndex = findLineIndex(lines, "сорт");
        int packagingIndex = findLineIndex(lines, "упаковка");
        int priceIndex = findLineIndex(lines, "цена");
        if (headerIndex < 0 || sortIndex < 0 || packagingIndex < 0 || priceIndex < 0) {
            return List.of();
        }
        int startIndex = Math.max(Math.max(headerIndex, sortIndex), Math.max(packagingIndex, priceIndex)) + 1;
        List<ImportRow> rows = new ArrayList<>();
        int rowNumber = 0;
        for (int index = startIndex; index + 5 < lines.size(); ) {
            String culture = lines.get(index);
            if (isLikelyCorporateNoiseLine(culture) || culture.length() > 120) {
                index++;
                continue;
            }
            String name = lines.get(index + 1);
            String category = lines.get(index + 2);
            String region = lines.get(index + 3);
            String packaging = lines.get(index + 4);
            String price = lines.get(index + 5);
            if (!looksLikePackagingLine(packaging) || !(looksLikeLoosePrice(price) || TextUtils.normalizeToken(price).contains("нет в наличии"))) {
                index++;
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            columns.put("Культура", culture);
            columns.put("Категория", category);
            columns.put("Регион допуска", region);
            columns.put("Упаковка", packaging);
            columns.put("Цена", price);
            columns.put("Сырой текст", String.join(" | ", List.of(culture, name, category, region, packaging, price)));
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    culture
            ));
            index += 6;
        }
        return rows;
    }

    private List<ImportRow> parseWinterSeedDocument(List<String> lines, String fileName, String sheetName) {
        int sortIndex = findLineIndex(lines, "сорт");
        int regionIndex = findLineIndex(lines, "регион допуска");
        int originatorIndex = findLineIndex(lines, "оригинатор");
        if (sortIndex < 0 || regionIndex < 0 || originatorIndex < 0) {
            return List.of();
        }
        int startIndex = Math.max(Math.max(sortIndex, regionIndex), originatorIndex) + 1;
        List<ImportRow> rows = new ArrayList<>();
        String currentCulture = "";
        int rowNumber = 0;
        int index = startIndex;
        while (index < lines.size()) {
            String line = lines.get(index);
            String normalized = TextUtils.normalizeToken(line);
            if (normalized.contains("озимая пшеница") || normalized.contains("озимый рапс") || normalized.contains("озимая рожь")) {
                currentCulture = line;
                index++;
                continue;
            }
            if (isLikelyCorporateNoiseLine(line) || line.startsWith("•") || line.length() > 90) {
                index++;
                continue;
            }
            String name = line;
            index++;
            List<String> regionParts = new ArrayList<>();
            while (index < lines.size() && looksLikeRegionLine(lines.get(index))) {
                regionParts.add(lines.get(index));
                index++;
            }
            List<String> originatorParts = new ArrayList<>();
            while (index < lines.size()
                    && !looksLikeRegionLine(lines.get(index))
                    && !TextUtils.normalizeToken(lines.get(index)).contains("оказываем услуги")
                    && !TextUtils.normalizeToken(lines.get(index)).contains("приглашаем вас")
                    && !TextUtils.normalizeToken(lines.get(index)).contains("озимая пшеница")
                    && !TextUtils.normalizeToken(lines.get(index)).contains("озимый рапс")
                    && originatorParts.size() < 2) {
                originatorParts.add(lines.get(index));
                index++;
            }
            if (currentCulture.isBlank() || name.length() < 3 || regionParts.isEmpty()) {
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            columns.put("Культура", currentCulture);
            columns.put("Регион допуска", String.join(" ", regionParts));
            if (!originatorParts.isEmpty()) {
                columns.put("Оригинатор", String.join(" ", originatorParts));
            }
            columns.put("Сырой текст", String.join(" | ", columns.values()));
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    currentCulture
            ));
        }
        return rows;
    }

    private List<ImportRow> parseInlineCornSeedDocument(List<String> lines, String fileName, String sheetName) {
        if (findLineIndex(lines, "группа спелости") < 0 || findLineIndex(lines, "цена п.е") < 0) {
            return List.of();
        }
        List<ImportRow> rows = new ArrayList<>();
        int rowNumber = 0;
        for (String line : lines) {
            String normalizedLine = line.replace('\u00A0', ' ').trim();
            Matcher leadingIndex = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(normalizedLine);
            if (!leadingIndex.matches()) {
                continue;
            }
            String bodyWithIndexRemoved = leadingIndex.group(2).trim();
            String price = extractTrailingAvailabilityOrPrice(bodyWithIndexRemoved);
            if (!looksLikeLoosePrice(price)) {
                continue;
            }
            String bodyWithoutPrice = stripTrailingToken(bodyWithIndexRemoved, price);
            String maturityGroup = findCornMaturityGroup(bodyWithoutPrice);
            if (maturityGroup.isBlank()) {
                continue;
            }
            String afterMaturity = bodyWithoutPrice.substring(maturityGroup.length()).trim();
            Matcher faoMatcher = Pattern.compile("^(.*)\\s+(\\d{2,3})\\s+([\\d,*/\\s.-]+)$").matcher(afterMaturity);
            if (!faoMatcher.matches()) {
                continue;
            }
            String name = faoMatcher.group(1).trim();
            String fao = faoMatcher.group(2).trim();
            String regions = faoMatcher.group(3).trim();
            if (name.isBlank()) {
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            columns.put("Группа спелости", maturityGroup);
            columns.put("ФАО", fao);
            columns.put("Регионы допуска", regions);
            columns.put("Цена", price);
            columns.put("Сырой текст", line);
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    "Кукуруза"
            ));
        }
        return rows;
    }

    private String findCornMaturityGroup(String text) {
        for (String value : List.of("Скороспелый", "Раннеспелый", "Среднеранний", "Среднеспелый", "Среднепоздний", "Позднеспелый")) {
            if (text.startsWith(value + " ") || text.equals(value)) {
                return value;
            }
        }
        return "";
    }

    private List<ImportRow> parseInlineSeedDocument(List<String> lines, String fileName, String sheetName) {
        List<ImportRow> rows = new ArrayList<>();
        String currentSection = "";
        int rowNumber = 0;
        for (String line : lines) {
            String normalized = TextUtils.normalizeToken(line);
            if (isLikelyCorporateNoiseLine(line) || normalized.contains("минимальный объем заказа")) {
                continue;
            }
            if (normalized.contains("сорта и гибриды")
                    || normalized.contains("семена зерновых")
                    || normalized.contains("семена масличных")
                    || normalized.contains("предложение на семена")) {
                currentSection = line;
                continue;
            }
            if (!looksLikePackagingLine(line) || !(looksLikeLoosePrice(line) || normalized.contains("нет в наличии"))) {
                continue;
            }
            String price = extractTrailingAvailabilityOrPrice(line);
            String body = stripTrailingToken(line, price);
            String packaging = extractTrailingPackaging(body);
            String name = stripTrailingToken(body, packaging).trim();
            if (name.isBlank()) {
                continue;
            }
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            if (!packaging.isBlank()) {
                columns.put("Упаковка", packaging);
            }
            if (!price.isBlank()) {
                columns.put("Цена", price);
            }
            columns.put("Сырой текст", line);
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    currentSection
            ));
        }
        return rows;
    }

    private List<ImportRow> parseQuoteDocument(List<String> lines, String fileName, String sheetName) {
        int nameIndex = findLineIndex(lines, "наименование");
        int compositionIndex = findLineIndex(lines, "состав");
        int priceIndex = findLineIndex(lines, "цена");
        if (nameIndex < 0 || compositionIndex < 0 || priceIndex < 0) {
            return List.of();
        }
        int startIndex = Math.max(Math.max(nameIndex, compositionIndex), priceIndex) + 1;
        List<ImportRow> rows = new ArrayList<>();
        int rowNumber = 0;
        int index = startIndex;
        while (index < lines.size()) {
            String name = lines.get(index);
            if (isLikelyCorporateNoiseLine(name) || looksLikeLoosePrice(name) || name.length() < 4) {
                index++;
                continue;
            }
            index++;
            List<String> descriptionParts = new ArrayList<>();
            while (index < lines.size() && !looksLikeLoosePrice(lines.get(index))) {
                if (!isLikelyCorporateNoiseLine(lines.get(index))) {
                    descriptionParts.add(lines.get(index));
                }
                index++;
            }
            String price = index < lines.size() ? lines.get(index) : "";
            if (!looksLikeLoosePrice(price)) {
                continue;
            }
            index++;
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("Позиция", name);
            if (!descriptionParts.isEmpty()) {
                columns.put("Состав", String.join(" ", descriptionParts));
            }
            columns.put("Цена", price);
            columns.put("Сырой текст", String.join(" | ", lines.subList(Math.max(startIndex, index - descriptionParts.size() - 2), Math.min(lines.size(), index))));
            rowNumber++;
            rows.add(new ImportRow(
                    fileName + "#" + sheetName + "#" + rowNumber,
                    fileName,
                    sheetName,
                    rowNumber,
                    columns,
                    name,
                    ""
            ));
        }
        return rows;
    }

    private List<ImportRow> chooseBestParsedRows(List<ImportRow> primary, List<ImportRow> fallback) {
        if (primary == null || primary.isEmpty()) {
            return fallback == null ? List.of() : fallback;
        }
        if (fallback == null || fallback.isEmpty()) {
            return primary;
        }
        return scoreParsedRows(fallback) > scoreParsedRows(primary) ? fallback : primary;
    }

    private int scoreParsedRows(List<ImportRow> rows) {
        int score = 0;
        for (ImportRow row : rows) {
            String name = row.nameGuess() == null ? "" : row.nameGuess().trim();
            String raw = row.columns().getOrDefault("Сырой текст", "");
            if (!name.isBlank() && !isLikelyCorporateNoiseLine(name) && !name.toLowerCase(Locale.ROOT).contains("уважаемые коллеги")) {
                score += 3;
            }
            if (findFirst(row.columns(), "цена", "price").isPresent()) {
                score += 2;
            }
            if (findFirst(row.columns(), "состав", "composition").isPresent()) {
                score += 1;
            }
            if (!raw.isBlank() && raw.length() < 260) {
                score += 1;
            }
        }
        return score;
    }

    private List<ImportRow> filterNoiseRows(List<ImportRow> rows) {
        return rows.stream()
                .filter(row -> {
                    String name = row.nameGuess() == null ? "" : row.nameGuess().trim();
                    if (name.isBlank()) {
                        return false;
                    }
                    String normalized = TextUtils.normalizeToken(name);
                    if (isLikelyCorporateNoiseLine(name)) {
                        return false;
                    }
                    return !normalized.contains("уважаемые коллеги")
                            && !normalized.contains("срок действия прайс")
                            && !normalized.contains("предлагаем семена")
                            && !normalized.contains("на посев")
                            && !normalized.contains("предоплата")
                            && !normalized.contains("прайс лист");
                })
                .toList();
    }

    private int findLineIndex(List<String> lines, String needle) {
        String normalizedNeedle = TextUtils.normalizeToken(needle);
        for (int index = 0; index < lines.size(); index++) {
            if (TextUtils.normalizeToken(lines.get(index)).contains(normalizedNeedle)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isLikelyCorporateNoiseLine(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return normalized.contains("инн")
                || normalized.contains("кпп")
                || normalized.contains("огрн")
                || normalized.startsWith("ооо ")
                || normalized.contains("тел")
                || normalized.contains("email")
                || normalized.contains("e mail")
                || normalized.contains("www")
                || normalized.contains("http")
                || normalized.contains("юридическ")
                || normalized.contains("почтовый адрес")
                || normalized.contains("уважаемые коллеги")
                || normalized.contains("предлагает")
                || normalized.contains("приглашаем вас")
                || normalized.contains("контакты")
                || normalized.contains("памятка")
                || normalized.contains("общество с ограниченной ответственностью")
                || normalized.contains("срок действия прайс")
                || normalized.contains("от объема заказа")
                || normalized.contains("офис")
                || normalized.contains("ул ")
                || normalized.matches("^\\d{6}.*");
    }

    private boolean isLikelyPesticideSection(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return normalized.equals("инсектициды")
                || normalized.equals("фунгициды")
                || normalized.equals("гербициды")
                || normalized.equals("десиканты")
                || normalized.equals("протравители")
                || normalized.equals("родентициды")
                || normalized.equals("репеленты")
                || normalized.equals("адъюванты")
                || normalized.equals("агрохимикаты")
                || normalized.contains("регуляторы роста");
    }

    private boolean looksLikeChemicalName(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return !normalized.isBlank()
                && !isLikelyCorporateNoiseLine(line)
                && line.length() < 90
                && (line.contains(",") || normalized.matches(".*\\b(кэ|кс|вдг|вр|вск|мд|ск|сп|ж|мэ|сэ)\\b.*"));
    }

    private boolean looksLikeDosageLine(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return normalized.matches(".*\\d+[.,]?\\d*(?:\\s*[\\-–]\\s*\\d+[.,]?\\d*)?.*")
                && (normalized.contains("л т")
                || normalized.contains("л га")
                || normalized.contains("кг га")
                || normalized.contains("кг т")
                || normalized.matches("^\\d+[.,]?\\d*(?:\\s*[\\-–]\\s*\\d+[.,]?\\d*)?$"));
    }

    private boolean looksLikeLoosePrice(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = line.replace('\u00A0', ' ').trim();
        if (TextUtils.normalizeToken(normalized).contains("по запросу")) {
            return true;
        }
        return normalized.matches(".*\\d[\\d\\s]*(?:[.,]\\d{1,2})?.*");
    }

    private boolean looksLikeInteger(String line) {
        return line != null && line.trim().matches("^\\d+$");
    }

    private boolean looksLikePackagingLine(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return normalized.contains("кг")
                || normalized.contains("л")
                || normalized.contains("п.е")
                || normalized.contains("мешок")
                || normalized.contains("биг бэг")
                || normalized.contains("канистр")
                || normalized.contains("пакет")
                || normalized.contains("тн");
    }

    private String extractTrailingAvailabilityOrPrice(String line) {
        String normalized = TextUtils.normalizeToken(line);
        if (normalized.contains("нет в наличии")) {
            return "Нет в наличии";
        }
        Matcher matcher = Pattern.compile("(\\d[\\d\\s]*(?:[.,]\\d{1,2})?)\\s*$").matcher(line.replace('\u00A0', ' '));
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String extractTrailingPackaging(String line) {
        Matcher matcher = Pattern.compile("((?:биг-бэг|биг бэг|бумажный мешок|канистра|мешок|пакет)[^\\d]*(?:\\d+[\\d\\s.,]*\\s*(?:кг|л|п\\.е\\.))?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("(\\d+[\\d\\s.,]*\\s*(?:кг|л|п\\.е\\.))\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String stripTrailingToken(String source, String token) {
        if (source == null || source.isBlank() || token == null || token.isBlank()) {
            return source == null ? "" : source.trim();
        }
        int index = source.lastIndexOf(token);
        if (index < 0) {
            return source.trim();
        }
        return source.substring(0, index).trim();
    }

    private boolean looksLikeRegionLine(String line) {
        String normalized = TextUtils.normalizeToken(line);
        return normalized.matches("^[\\d,*/\\s.-]+$")
                || normalized.contains("москва")
                || normalized.contains("краснодар")
                || normalized.contains("щелково")
                || normalized.contains("республика")
                || normalized.startsWith("г ");
    }

    private String valueAt(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index) == null ? "" : values.get(index).trim();
    }

    private List<ImportRow> parsePdfFile(byte[] bytes, String fileName) {
        List<ImportRow> scriptRows = parsePdfFileViaScript(bytes, fileName);
        if (!scriptRows.isEmpty()) {
            return scriptRows;
        }
        return parsePdfFileLegacy(bytes, fileName);
    }

    private List<ImportRow> parsePdfFileViaScript(byte[] bytes, String fileName) {
        Optional<Path> scriptPath = resolvePdfExtractorScript();
        if (scriptPath.isEmpty()) {
            return List.of();
        }
        Path tempPdf = null;
        try (PDDocument document = Loader.loadPDF(bytes)) {
            // Quick validation that PDF is readable before handing it to the external extractor.
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать PDF-файл " + fileName, e);
        }
        try {
            tempPdf = Files.createTempFile("alga-pdf-import-", ".pdf");
            Files.write(tempPdf, bytes);
            Process process = new ProcessBuilder(resolvePdfPythonExecutable(), scriptPath.get().toString(), tempPdf.toString())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(240, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PDF extractor timed out");
            }
            if (process.exitValue() != 0) {
                log.warn("Python PDF extractor failed for {}: {}", fileName, output.trim());
                return List.of();
            }
            List<Map<String, Object>> rows = jsonHelper.readValue(output, new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of());
            if (rows.isEmpty()) {
                return List.of();
            }
            List<ImportRow> importRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, String> columns = toStringMap(row.get("columns"));
                String rowId = Objects.toString(row.get("rowId"), fileName + "#PDF#" + (importRows.size() + 1));
                String sourceFile = Objects.toString(row.get("sourceFile"), fileName);
                String sheetName = Objects.toString(row.get("sheetName"), "PDF");
                int rowNumber = parseInteger(row.get("rowNumber"), importRows.size() + 1);
                String nameGuess = Objects.toString(row.get("nameGuess"), extractName(columns));
                String section = Objects.toString(row.get("section"), "");
                importRows.add(new ImportRow(rowId, sourceFile, sheetName, rowNumber, columns, nameGuess, section));
            }
            return importRows;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Python PDF extractor unavailable for {}: {}", fileName, e.getMessage());
            return List.of();
        } finally {
            if (tempPdf != null) {
                try {
                    Files.deleteIfExists(tempPdf);
                } catch (IOException ignored) {
                    // ignore cleanup failure
                }
            }
        }
    }

    private String resolvePdfPythonExecutable() {
        String envPath = System.getenv("APP_PDF_PYTHON_BIN");
        return envPath == null || envPath.isBlank() ? "python3" : envPath;
    }

    private Optional<Path> resolvePdfExtractorScript() {
        String envPath = System.getenv("APP_PDF_EXTRACTOR_SCRIPT");
        if (envPath != null && !envPath.isBlank()) {
            Path candidate = Path.of(envPath);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        Path localCandidate = Path.of("scripts", "pdf_extract.py");
        if (Files.exists(localCandidate)) {
            return Optional.of(localCandidate);
        }
        Path dockerCandidate = Path.of("/app", "scripts", "pdf_extract.py");
        if (Files.exists(dockerCandidate)) {
            return Optional.of(dockerCandidate);
        }
        return Optional.empty();
    }

    private Map<String, String> toStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(Objects.toString(entry.getKey(), ""), Objects.toString(entry.getValue(), ""));
        }
        return result;
    }

    private int parseInteger(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<ImportRow> parsePdfFileLegacy(byte[] bytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return parseTextDocument(text, fileName, "PDF");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать PDF-файл " + fileName, e);
        }
    }

    private String normalizePdfLine(String rawLine) {
        return rawLine == null
                ? ""
                : rawLine
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\r]+", " ")
                .replaceAll("\\s{2,}", "  ")
                .trim();
    }

    private boolean isPdfNoiseLine(String line) {
        String normalized = TextUtils.normalizeToken(line);
        if (normalized.isBlank() || normalized.length() < 3) {
            return true;
        }
        if (!normalized.matches(".*[a-zа-я].*")) {
            return true;
        }
        return normalized.contains("прайс")
                || normalized.contains("price")
                || normalized.contains("страница")
                || normalized.matches("^page\\s+\\d+.*")
                || normalized.contains("www.")
                || normalized.contains("http://")
                || normalized.contains("https://")
                || normalized.contains("@")
                || normalized.contains("тел.")
                || normalized.contains("телефон")
                || normalized.contains("эл. почт")
                || normalized.contains("действует с")
                || normalized.matches("^\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}.*");
    }

    private boolean looksLikePdfSection(String line) {
        String normalized = TextUtils.normalizeToken(line);
        if (normalized.length() < 4 || normalized.length() > 90) {
            return false;
        }
        if (!extractPdfPrice(line).isBlank() || !extractPdfDosage(line).isBlank()) {
            return false;
        }
        if (normalized.endsWith(":")) {
            return true;
        }
        if (normalized.contains("семена")
                || normalized.contains("пестицид")
                || normalized.contains("гербицид")
                || normalized.contains("фунгицид")
                || normalized.contains("инсектицид")
                || normalized.contains("удобр")
                || normalized.contains("агропит")
                || normalized.contains("корректор")
                || normalized.contains("биостим")
                || normalized.contains("адъюв")) {
            return true;
        }
        long letterCount = line.chars().filter(Character::isLetter).count();
        long upperCount = line.chars().filter(ch -> Character.isLetter(ch) && Character.isUpperCase(ch)).count();
        int wordCount = normalized.split("\\s+").length;
        return letterCount > 0 && wordCount <= 6 && ((double) upperCount / (double) letterCount) > 0.72d;
    }

    private boolean shouldAppendPdfLineToPrevious(String line, List<MutableImportRow> parsedRows) {
        if (parsedRows.isEmpty()) {
            return false;
        }
        String normalized = TextUtils.normalizeToken(line);
        return normalized.startsWith("состав")
                || normalized.startsWith("норма расхода")
                || normalized.startsWith("расход")
                || normalized.startsWith("действующее вещество")
                || normalized.startsWith("культуры")
                || normalized.startsWith("назначение")
                || normalized.startsWith("применение")
                || normalized.startsWith("для ");
    }

    private void appendPdfLineToPrevious(MutableImportRow previous, String line) {
        String normalized = TextUtils.normalizeToken(line);
        if (normalized.startsWith("состав")) {
            String existing = previous.columns().getOrDefault("Состав", "");
            previous.columns().put("Состав", existing.isBlank() ? line : (existing + " " + line).trim());
        } else if (normalized.startsWith("норма расхода") || normalized.startsWith("расход")) {
            String existing = previous.columns().getOrDefault("Норма расхода", "");
            previous.columns().put("Норма расхода", existing.isBlank() ? line : (existing + " " + line).trim());
        } else {
            String existing = previous.columns().getOrDefault("Сырой текст", "");
            previous.columns().put("Сырой текст", existing.isBlank() ? line : (existing + " " + line).trim());
            String currentComposition = previous.columns().getOrDefault("Состав", "");
            if (currentComposition.isBlank() && line.length() > 12) {
                previous.columns().put("Состав", line);
            }
        }
    }

    private Map<String, String> buildPdfColumns(String line, String currentSection) {
        Map<String, String> columns = new LinkedHashMap<>();
        String name = extractPdfName(line);
        String price = extractPdfPrice(line);
        String dosage = extractPdfDosage(line);
        String composition = extractPdfComposition(line, name, dosage, price);
        columns.put("Позиция", name);
        columns.put("Сырой текст", line);
        if (!currentSection.isBlank()) {
            columns.put("Раздел", currentSection);
        }
        if (!composition.isBlank()) {
            columns.put("Состав", composition);
        }
        if (!dosage.isBlank()) {
            columns.put("Норма расхода", dosage);
        }
        if (!price.isBlank()) {
            columns.put("Цена", price);
        }
        if (line.contains("/л") || TextUtils.normalizeToken(line).contains("р/л")) {
            columns.put("Ед.", "л");
        } else if (line.contains("/кг") || TextUtils.normalizeToken(line).contains("р/кг")) {
            columns.put("Ед.", "кг");
        }
        return columns;
    }

    private String extractPdfName(String line) {
        List<String> segments = Arrays.stream(line.split("\\s{2,}|\\t+"))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (!segments.isEmpty()) {
            String firstSegment = segments.get(0).replaceFirst("^[\\d\\s().-]+", "").trim();
            if (!firstSegment.isBlank()) {
                return TextUtils.trimTo(firstSegment, 180);
            }
        }
        String price = extractPdfPrice(line);
        String dosage = extractPdfDosage(line);
        String name = line;
        if (!price.isBlank()) {
            name = name.replace(price, " ");
        }
        if (!dosage.isBlank()) {
            name = name.replace(dosage, " ");
        }
        name = name.replaceAll("\\s{2,}", " ").replaceAll("\\s*[;|]+\\s*", " ").trim();
        return TextUtils.trimTo(name, 180);
    }

    private String extractPdfPrice(String line) {
        Optional<String> explicit = findRegex(line,
                "(?iu)(\\d[\\d\\s]{2,}(?:[.,]\\d{1,2})?\\s*(?:₽|руб(?:\\.|лей)?|р(?:/|\\s*)(?:л|кг|т)|руб(?:/|\\s*)(?:л|кг|т)))");
        if (explicit.isPresent()) {
            return explicit.get().trim();
        }
        List<String> segments = Arrays.stream(line.split("\\s{2,}|\\t+"))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            if (segment.matches(".*\\d{3,}.*")) {
                return segment;
            }
        }
        return "";
    }

    private String extractPdfDosage(String line) {
        return findRegex(line,
                "(?iu)(норма расхода[^.;]*|расход[^.;]*|\\d+[\\d,.\\-–]*\\s*(?:л|мл|кг|г)\\s*/\\s*(?:га|т|100\\s*кг))")
                .map(String::trim)
                .orElse("");
    }

    private String extractPdfComposition(String line, String name, String dosage, String price) {
        Optional<String> explicit = findRegex(line, "(?iu)(состав[^.;]*|действующее вещество[^.;]*)");
        if (explicit.isPresent()) {
            return TextUtils.trimTo(explicit.get().trim(), 220);
        }
        String tail = line;
        if (name != null && !name.isBlank() && tail.startsWith(name)) {
            tail = tail.substring(name.length()).trim();
        }
        if (dosage != null && !dosage.isBlank()) {
            tail = tail.replace(dosage, " ");
        }
        if (price != null && !price.isBlank()) {
            tail = tail.replace(price, " ");
        }
        tail = tail.replaceAll("\\s{2,}", " ").replaceAll("^[\\-–:;,./\\s]+", "").trim();
        if (tail.length() < 12) {
            return "";
        }
        return TextUtils.trimTo(tail, 220);
    }

    private Optional<String> findRegex(String value, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(value);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private byte[] download(String url) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET();
            if (isMaxHostedUrl(url) && appProperties.getMax().getBotToken() != null && !appProperties.getMax().getBotToken().isBlank()) {
                requestBuilder.header("Authorization", appProperties.getMax().getBotToken());
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ошибка скачивания файла: " + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Не удалось скачать файл", e);
        }
    }

    private boolean isMaxHostedUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.endsWith(".max.ru")
                    || normalized.endsWith(".oneme.ru")
                    || normalized.contains("max.ru")
                    || normalized.contains("oneme.ru");
        } catch (Exception e) {
            return false;
        }
    }

    private int detectHeaderRowIndex(Sheet sheet) {
        int limit = Math.min(sheet.getLastRowNum(), 20);
        for (int rowIndex = 0; rowIndex <= limit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            if (isHeaderLikeRow(readRowValues(row, Math.max(row.getLastCellNum(), 0)))) {
                return rowIndex;
            }
        }
        return -1;
    }

    private int detectHeaderRowIndex(List<List<String>> matrix) {
        int limit = Math.min(matrix.size() - 1, 20);
        for (int rowIndex = 0; rowIndex <= limit; rowIndex++) {
            if (isHeaderLikeRow(matrix.get(rowIndex))) {
                return rowIndex;
            }
        }
        return -1;
    }

    private List<String> readHeaders(Sheet sheet, int headerRowIndex) {
        Row headerRow = sheet.getRow(headerRowIndex);
        List<String> headers = new ArrayList<>();
        if (headerRow == null) {
            return headers;
        }
        for (int cellIndex = 0; cellIndex < headerRow.getLastCellNum(); cellIndex++) {
            headers.add(dataFormatter.formatCellValue(headerRow.getCell(cellIndex)).trim());
        }
        return headers;
    }

    private List<String> readHeaders(List<List<String>> matrix, int headerRowIndex) {
        if (headerRowIndex < 0 || headerRowIndex >= matrix.size()) {
            return List.of();
        }
        return matrix.get(headerRowIndex).stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
    }

    private List<String> readRowValues(Row row, int sizeHint) {
        int width = Math.max(sizeHint, row.getLastCellNum());
        List<String> values = new ArrayList<>();
        for (int cellIndex = 0; cellIndex < width; cellIndex++) {
            values.add(dataFormatter.formatCellValue(row.getCell(cellIndex)).trim());
        }
        return values;
    }

    private List<String> readRowValues(List<List<String>> matrix, int rowIndex, int sizeHint) {
        if (rowIndex < 0 || rowIndex >= matrix.size()) {
            return List.of();
        }
        List<String> source = matrix.get(rowIndex);
        int width = Math.max(sizeHint, source.size());
        List<String> values = new ArrayList<>();
        for (int cellIndex = 0; cellIndex < width; cellIndex++) {
            values.add(cellIndex < source.size() && source.get(cellIndex) != null ? source.get(cellIndex).trim() : "");
        }
        return values;
    }

    private boolean isHeaderLikeRow(List<String> values) {
        int matches = 0;
        for (String value : values) {
            String normalized = TextUtils.normalizeToken(value);
            if (normalized.contains("позиция")
                    || normalized.contains("состав")
                    || normalized.contains("норма расхода")
                    || normalized.contains("цена")) {
                matches++;
            }
        }
        return matches >= 2;
    }

    private boolean rowIsEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!dataFormatter.formatCellValue(row.getCell(i)).trim().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean rowIsEmpty(List<String> rowValues) {
        return rowValues == null || rowValues.stream().allMatch(value -> value == null || value.isBlank());
    }

    private List<List<String>> readSheetMatrix(Sheet sheet) {
        List<List<String>> matrix = new ArrayList<>();
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                matrix.add(List.of());
                continue;
            }
            matrix.add(readRowValues(row, Math.max(row.getLastCellNum(), 0)));
        }
        return matrix;
    }

    private String extractName(Map<String, String> columns) {
        return findFirst(columns, "позиция", "наименование", "товар", "номенклат", "product", "name")
                .orElseGet(() -> columns.values().stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("Без названия"));
    }

    private String extractSku(Map<String, String> columns) {
        return findFirst(columns, "артикул", "sku", "код", "id").orElse("");
    }

    private String extractUnit(Map<String, String> columns) {
        return findFirst(columns, "ед", "unit", "фасовка")
                .orElseGet(() -> inferUnit(columns).orElse("шт"));
    }

    private BigDecimal extractPrice(Map<String, String> columns) {
        return findFirst(columns, "цена", "price")
                .map(this::parseDecimal)
                .orElse(null);
    }

    private BigDecimal extractStock(Map<String, String> columns) {
        return findFirst(columns, "остат", "налич", "stock", "колич")
                .map(this::parseDecimal)
                .orElse(null);
    }

    private Optional<String> findFirst(Map<String, String> columns, String... needles) {
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String normalized = TextUtils.normalizeToken(entry.getKey());
            for (String needle : needles) {
                if (normalized.contains(TextUtils.normalizeToken(needle))) {
                    String value = entry.getValue();
                    if (value != null && !value.isBlank()) {
                        return Optional.of(value.trim());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String firstPresent(Map<String, String> columns, String... needles) {
        return findFirst(columns, needles).orElse("");
    }

    private Optional<String> inferUnit(Map<String, String> columns) {
        for (String header : columns.keySet()) {
            String normalized = TextUtils.normalizeToken(header);
            if (normalized.contains("руб л") || normalized.contains("/л")) {
                return Optional.of("л");
            }
            if (normalized.contains("руб кг") || normalized.contains("/кг")) {
                return Optional.of("кг");
            }
        }
        String rate = findFirst(columns, "норма расхода", "расход", "дозиров")
                .orElse("");
        String normalizedRate = TextUtils.normalizeToken(rate);
        if (normalizedRate.contains("л га") || normalizedRate.contains("л т") || normalizedRate.contains("мл")) {
            return Optional.of("л");
        }
        if (normalizedRate.contains("кг га") || normalizedRate.contains("кг т") || normalizedRate.contains("г ")) {
            return Optional.of("кг");
        }
        return Optional.empty();
    }

    private boolean isSectionRow(Map<String, String> columns, String position) {
        String composition = findFirst(columns, "состав", "composition").orElse("");
        String rate = findFirst(columns, "норма расхода", "расход", "дозиров").orElse("");
        String price = findFirst(columns, "цена", "price").orElse("");
        if (position.isBlank() || !composition.isBlank() || !rate.isBlank() || !price.isBlank()) {
            return false;
        }
        String normalized = TextUtils.normalizeToken(position);
        return !normalized.startsWith("изагри")
                && (normalized.contains("для")
                || normalized.contains("комплекс")
                || normalized.contains("дефицит")
                || normalized.contains("подкорм")
                || normalized.contains("препарат")
                || normalized.contains("адъюв")
                || normalized.contains("элемент")
                || normalized.contains("семян"));
    }

    private boolean shouldAppendToPrevious(Map<String, String> columns, String position, List<MutableImportRow> parsedRows) {
        if (parsedRows.isEmpty()) {
            return false;
        }
        String composition = findFirst(columns, "состав", "composition").orElse("");
        String rate = findFirst(columns, "норма расхода", "расход", "дозиров").orElse("");
        String price = findFirst(columns, "цена", "price").orElse("");
        if (!composition.isBlank() || !rate.isBlank() || !price.isBlank()) {
            return false;
        }
        String normalized = TextUtils.normalizeToken(position);
        return !position.isBlank()
                && !normalized.startsWith("изагри")
                && (position.length() > 25 || position.contains("%") || position.contains(","));
    }

    private void appendToPrevious(MutableImportRow previous, Map<String, String> columns, String text) {
        String compositionHeader = previous.findHeader("состав", "composition");
        if (compositionHeader != null) {
            String existing = previous.columns().getOrDefault(compositionHeader, "");
            previous.columns().put(compositionHeader, existing.isBlank() ? text : (existing + " " + text).trim());
            return;
        }
        String positionHeader = previous.findHeader("позиция", "наименование", "товар", "name");
        if (positionHeader != null) {
            String existing = previous.columns().getOrDefault(positionHeader, "");
            previous.columns().put(positionHeader, (existing + " " + text).trim());
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\u00A0', ' ').replace(",", ".");
        Matcher matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(normalized);
        String candidate = null;
        while (matcher.find()) {
            candidate = matcher.group();
        }
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return new BigDecimal(candidate);
    }

    private String resolveCategory(AiClassificationService.ClassificationResult result, ImportRow row) {
        if (result.category() != null && !result.category().isBlank() && !"Прочее".equalsIgnoreCase(result.category())) {
            return result.category();
        }
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess() + " " + row.columns());
        if (context.contains("адъюв") || context.contains("технологич") || context.contains("стик") || context.contains("клинер") || context.contains("контроль")) {
            return "Адъюванты";
        }
        if (context.contains("бор") || context.contains("цинк") || context.contains("магний") || context.contains("кальц")
                || context.contains("npk") || context.contains("подкорм") || context.contains("биостим") || context.contains("аминокислот")
                || context.contains("корректор") || context.contains("дефицит")) {
            return "Агропитание";
        }
        if (context.contains("гербиц") || context.contains("фунгиц") || context.contains("инсекти")
                || context.contains("протрав") || context.contains("десикант") || context.contains("роденти")
                || context.contains("репелент") || context.contains("регулятор рост") || context.contains("красител")) {
            return "Пестициды";
        }
        return result.category() == null || result.category().isBlank() ? "Прочее" : result.category();
    }

    private String resolveSubcategory(AiClassificationService.ClassificationResult result, ImportRow row) {
        if (result.subcategory() != null && !result.subcategory().isBlank()) {
            return result.subcategory();
        }
        String context = TextUtils.normalizeToken(row.section() + " " + row.nameGuess());
        if (context.contains("гербиц")) return "Гербициды";
        if (context.contains("фунгиц")) return "Фунгициды";
        if (context.contains("инсекти")) return "Инсектициды";
        if (context.contains("десикант")) return "Десиканты";
        if (context.contains("протрав")) return "Протравители";
        if (context.contains("роденти")) return "Родентициды";
        if (context.contains("репелент")) return "Репеленты";
        if (context.contains("регулятор рост")) return "Регуляторы роста растений";
        if (context.contains("обработк") && context.contains("сем")) return "Биостимуляторы";
        if (context.contains("антистресс")) return "Антистрессанты";
        if (context.contains("npk")) return "NPK-комплексы";
        if (context.contains("дефицит")) return "Корректоры дефицита";
        if (context.contains("адъюв")) return "Адъюванты";
        return "";
    }

    private String resolveItemType(AiClassificationService.ClassificationResult result, String category, String subcategory, ImportRow row) {
        if (result.itemType() != null && !result.itemType().isBlank() && !"Прочее".equalsIgnoreCase(result.itemType())) {
            return result.itemType();
        }
        if (!subcategory.isBlank()) {
            return subcategory;
        }
        if (!category.isBlank() && !"Прочее".equalsIgnoreCase(category)) {
            return category;
        }
        return row.section() == null || row.section().isBlank() ? "Товар" : row.section();
    }

    private String resolveDescription(AiClassificationService.ClassificationResult result, ImportRow row) {
        if (result.description() != null && !result.description().isBlank()) {
            return result.description();
        }
        List<String> parts = new ArrayList<>();
        if (row.section() != null && !row.section().isBlank()) {
            parts.add(row.section());
        }
        String composition = findFirst(row.columns(), "состав", "composition").orElse("");
        if (!composition.isBlank()) {
            parts.add("Состав: " + TextUtils.trimTo(composition, 180));
        }
        String rate = findFirst(row.columns(), "норма расхода", "расход", "дозиров").orElse("");
        if (!rate.isBlank()) {
            parts.add("Расход: " + TextUtils.trimTo(rate, 80));
        }
        return String.join(". ", parts);
    }

    private AppliedImportResult applyStagedProducts(List<StagedImportProduct> stagedProducts) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int deactivated = 0;
        Map<String, Set<String>> importedExternalIdsBySource = new LinkedHashMap<>();
        for (StagedImportProduct item : stagedProducts) {
            ProductService.ImportedProduct product = new ProductService.ImportedProduct(
                    item.externalId(),
                    item.sourceFile(),
                    item.sku(),
                    item.name(),
                    item.description(),
                    item.brand(),
                    item.category(),
                    item.subcategory(),
                    item.itemType(),
                    item.unitName(),
                    item.price(),
                    item.stockQuantity(),
                    item.packageType(),
                    item.packageDescription(),
                    item.minOrderQuantity(),
                    item.orderStep(),
                    item.cultures(),
                    item.purposes(),
                    item.tags(),
                    item.filterMap(),
                    item.rawData()
            );
            importedExternalIdsBySource
                    .computeIfAbsent(item.sourceFile(), key -> new LinkedHashSet<>())
                    .add(item.externalId());
            ProductService.UpsertResult saveResult = productService.upsertProduct(product, false);
            if (saveResult.created()) {
                created++;
            } else if (saveResult.changed()) {
                updated++;
            } else {
                unchanged++;
            }
        }
        for (Map.Entry<String, Set<String>> entry : importedExternalIdsBySource.entrySet()) {
            deactivated += productService.deactivateMissingSourceProducts(entry.getKey(), entry.getValue(), false);
        }
        if (created > 0 || updated > 0 || deactivated > 0) {
            productService.syncAllProductsToBitrix();
        }
        return new AppliedImportResult(created, updated, unchanged, deactivated);
    }

    private String buildExternalId(ImportRow row) {
        return TextUtils.normalizeToken(row.sourceFile()) + "::" + TextUtils.normalizeToken(row.sheetName()) + "::" + row.rowNumber();
    }

    public record ImportRow(
            String rowId,
            String sourceFile,
            String sheetName,
            int rowNumber,
            Map<String, String> columns,
            String nameGuess,
            String section
    ) {
    }

    private record AnalyzedImport(
            List<Map<String, Object>> files,
            List<ImportRow> rows,
            List<StagedImportProduct> stagedProducts,
            Set<String> detectedCategories,
            Set<String> detectedCultures,
            Set<String> detectedPurposes,
            Set<String> newCategories,
            Set<String> newCultures,
            int extractedNames,
            int extractedSections,
            int extractedComposition,
            int extractedRate,
            int extractedPrice
    ) {
    }

    public record StagedImportProduct(
            String externalId,
            String sourceFile,
            String sku,
            String name,
            String description,
            String brand,
            String category,
            String subcategory,
            String itemType,
            String unitName,
            BigDecimal price,
            BigDecimal stockQuantity,
            String packageType,
            String packageDescription,
            BigDecimal minOrderQuantity,
            BigDecimal orderStep,
            List<String> cultures,
            List<String> purposes,
            List<String> tags,
            Map<String, Object> filterMap,
            Map<String, String> rawData,
            String section,
            String composition,
            String dosage,
            String sourcePrice
    ) {
    }

    private record AppliedImportResult(
            int created,
            int updated,
            int unchanged,
            int deactivated
    ) {
    }

    private static final class MutableImportRow {
        private final String rowId;
        private final String sourceFile;
        private final String sheetName;
        private final int rowNumber;
        private final Map<String, String> columns;
        private final String section;

        private MutableImportRow(String rowId, String sourceFile, String sheetName, int rowNumber, Map<String, String> columns, String nameGuess, String section) {
            this.rowId = rowId;
            this.sourceFile = sourceFile;
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.columns = new LinkedHashMap<>(columns);
            this.columns.putIfAbsent("__nameGuess", nameGuess);
            this.section = section == null ? "" : section;
        }

        private Map<String, String> columns() {
            return columns;
        }

        private String findHeader(String... needles) {
            for (String key : columns.keySet()) {
                String normalized = TextUtils.normalizeToken(key);
                for (String needle : needles) {
                    if (normalized.contains(TextUtils.normalizeToken(needle))) {
                        return key;
                    }
                }
            }
            return null;
        }

        private ImportRow toImmutable() {
            String nameGuess = columns.getOrDefault("__nameGuess", "Без названия");
            columns.remove("__nameGuess");
            return new ImportRow(rowId, sourceFile, sheetName, rowNumber, columns, nameGuess, section);
        }
    }
}
