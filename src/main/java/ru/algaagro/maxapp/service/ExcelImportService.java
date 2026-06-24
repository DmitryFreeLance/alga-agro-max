package ru.algaagro.maxapp.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
        return importJobRepository.save(job);
    }

    public CompletableFuture<Void> processAsync(ImportJob job, Runnable onSuccess, java.util.function.Consumer<String> onFailure) {
        return CompletableFuture.runAsync(() -> processJob(job), importExecutorService)
                .thenRun(onSuccess)
                .exceptionally(error -> {
                    onFailure.accept(error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                    return null;
                });
    }

    @Transactional
    public void processJob(ImportJob job) {
        job.setStatus(ImportStatus.PROCESSING);
        importJobRepository.save(job);

        List<Map<String, Object>> files = jsonHelper.readValue(job.getSourceFilesJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of());
        List<ImportRow> rows = new ArrayList<>();
        for (Map<String, Object> file : files) {
            rows.addAll(parseFile(file));
        }
        if (rows.isEmpty()) {
            job.setStatus(ImportStatus.FAILED);
            job.setSummary("Не удалось извлечь строки из Excel-файлов.");
            importJobRepository.save(job);
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

        int created = 0;
        int updated = 0;
        Set<String> detectedCategories = new LinkedHashSet<>();
        Set<String> detectedCultures = new LinkedHashSet<>();
        Set<String> detectedPurposes = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            AiClassificationService.ClassificationResult result = classified.get(i);
            ProductService.ImportedProduct product = new ProductService.ImportedProduct(
                    buildExternalId(row),
                    row.sourceFile(),
                    extractSku(row.columns()),
                    result.normalizedName().isBlank() ? row.nameGuess() : result.normalizedName(),
                    result.description(),
                    result.brand(),
                    result.category(),
                    result.subcategory(),
                    result.itemType(),
                    extractUnit(row.columns()),
                    extractPrice(row.columns()),
                    extractStock(row.columns()),
                    result.cultures(),
                    result.purposes(),
                    result.tags(),
                    result.filterMap(),
                    row.columns()
            );
            ProductService.UpsertResult saveResult = productService.upsertProduct(product);
            if (saveResult.created()) {
                created++;
            } else {
                updated++;
            }
            if (result.category() != null && !result.category().isBlank()) {
                detectedCategories.add(result.category());
            }
            detectedCultures.addAll(result.cultures());
            detectedPurposes.addAll(result.purposes());
        }
        job.setStatus(ImportStatus.COMPLETED);
        Set<String> newCategories = detectedCategories.stream()
                .filter(category -> knownCategories.stream().noneMatch(existing -> existing.equalsIgnoreCase(category)))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        Set<String> newCultures = detectedCultures.stream()
                .filter(culture -> knownCultures.stream().noneMatch(existing -> existing.equalsIgnoreCase(culture)))
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        job.setSummary(buildImportSummary(files, rows.size(), created, updated, detectedCategories, detectedCultures, detectedPurposes, newCategories, newCultures));
        importJobRepository.save(job);
    }

    private String buildImportSummary(
            List<Map<String, Object>> files,
            int rowsProcessed,
            int created,
            int updated,
            Set<String> categories,
            Set<String> cultures,
            Set<String> purposes,
            Set<String> newCategories,
            Set<String> newCultures
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append("✅ <b>Импорт завершен</b>\n\n");
        summary.append("• Файлов обработано: <b>").append(files.size()).append("</b>\n");
        summary.append("• Строк из прайсов: <b>").append(rowsProcessed).append("</b>\n");
        summary.append("• Добавлено товаров: <b>").append(created).append("</b>\n");
        summary.append("• Обновлено товаров: <b>").append(updated).append("</b>\n");
        summary.append("• Всего затронуто товаров: <b>").append(created + updated).append("</b>\n");

        if (!categories.isEmpty()) {
            summary.append("• Категории: ").append(formatPreviewList(categories, 6)).append("\n");
        }
        if (!cultures.isEmpty()) {
            summary.append("• Культуры: ").append(formatPreviewList(cultures, 8)).append("\n");
        }
        if (!purposes.isEmpty()) {
            summary.append("• Назначения: ").append(formatPreviewList(purposes, 6)).append("\n");
        }
        if (!newCategories.isEmpty()) {
            summary.append("• Новые категории: ").append(formatPreviewList(newCategories, 5)).append("\n");
        }
        if (!newCultures.isEmpty()) {
            summary.append("• Новые культуры: ").append(formatPreviewList(newCultures, 6)).append("\n");
        }

        List<String> fileNames = files.stream()
                .map(file -> Objects.toString(file.get("name"), "").trim())
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (!fileNames.isEmpty()) {
            summary.append("• Файлы: ").append(formatPreviewList(new LinkedHashSet<>(fileNames), 3)).append("\n");
        }
        return summary.toString().trim();
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

    private List<ImportRow> parseFile(Map<String, Object> fileMeta) {
        String url = Objects.toString(fileMeta.get("url"), "");
        String fileName = Objects.toString(fileMeta.get("name"), "import.xlsx");
        if (url.isBlank()) {
            return List.of();
        }
        byte[] bytes = download(url);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<ImportRow> rows = new ArrayList<>();
            for (Sheet sheet : workbook) {
                List<String> headers = readHeaders(sheet);
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null || rowIsEmpty(row)) {
                        continue;
                    }
                    Map<String, String> columns = new LinkedHashMap<>();
                    for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
                        String header = headers.get(cellIndex);
                        if (header.isBlank()) {
                            continue;
                        }
                        Cell cell = row.getCell(cellIndex);
                        columns.put(header, dataFormatter.formatCellValue(cell).trim());
                    }
                    rows.add(new ImportRow(
                            fileName + "#" + sheet.getSheetName() + "#" + rowIndex,
                            fileName,
                            sheet.getSheetName(),
                            rowIndex,
                            columns,
                            extractName(columns)
                    ));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл " + fileName, e);
        }
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

    private List<String> readHeaders(Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        List<String> headers = new ArrayList<>();
        if (headerRow == null) {
            return headers;
        }
        for (int cellIndex = 0; cellIndex < headerRow.getLastCellNum(); cellIndex++) {
            headers.add(dataFormatter.formatCellValue(headerRow.getCell(cellIndex)).trim());
        }
        return headers;
    }

    private boolean rowIsEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!dataFormatter.formatCellValue(row.getCell(i)).trim().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String extractName(Map<String, String> columns) {
        return findFirst(columns, "наименование", "товар", "номенклат", "product", "name")
                .orElseGet(() -> columns.values().stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("Без названия"));
    }

    private String extractSku(Map<String, String> columns) {
        return findFirst(columns, "артикул", "sku", "код", "id").orElse("");
    }

    private String extractUnit(Map<String, String> columns) {
        return findFirst(columns, "ед", "unit", "фасовка").orElse("шт");
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

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(" ", "").replace(",", ".").replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank()) {
            return null;
        }
        return new BigDecimal(normalized);
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
            String nameGuess
    ) {
    }
}
