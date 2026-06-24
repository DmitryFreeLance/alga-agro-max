package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ProductService {

    private static final BigDecimal DEFAULT_ORDER_QUANTITY = BigDecimal.ONE;
    private static final Pattern BOX_MULTIPLIER_PATTERN = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*[xх*]\\s*(\\d+(?:[.,]\\d+)?)\\s*(л|литр|литра|литров|кг|килограмм|килограмма|килограммов)");
    private static final Pattern BOX_TOTAL_PATTERN = Pattern.compile("(?i)короб[а-я]*[^\\d]{0,20}(\\d+(?:[.,]\\d+)?)\\s*(л|литр|литра|литров|кг|килограмм|килограмма|килограммов)");
    private static final Pattern TOTAL_VOLUME_PATTERN = Pattern.compile("(?i)итог[ао]?[^\\d]{0,12}(\\d+(?:[.,]\\d+)?)\\s*(л|литр|литра|литров|кг|килограмм|килограмма|килограммов)");
    private static final Pattern CANISTER_PATTERN = Pattern.compile("(?i)канистр[а-я]*[^\\d]{0,12}(\\d+(?:[.,]\\d+)?)\\s*(л|литр|литра|литров|кг|килограмм|килограмма|килограммов)");
    private static final Pattern CANISTER_COUNT_PATTERN = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*канистр[а-я]*[^\\d]{0,12}(?:по\\s*)?(\\d+(?:[.,]\\d+)?)\\s*(л|литр|литра|литров|кг|килограмм|килограмма|килограммов)");
    private static final Pattern PE_PATTERN = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:п\\.\\s*е\\.|п/е|пе|посевн(?:ая|ые)?\\s+единиц[аы]?)");
    private static final Pattern TON_PATTERN = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(?:т\\b|тонн[аы]?)");

    private final CatalogProductRepository catalogProductRepository;
    private final JsonHelper jsonHelper;
    private final ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider;
    private final ObjectProvider<ManufacturerService> manufacturerServiceProvider;

    public ProductService(
            CatalogProductRepository catalogProductRepository,
            JsonHelper jsonHelper,
            ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider,
            ObjectProvider<ManufacturerService> manufacturerServiceProvider
    ) {
        this.catalogProductRepository = catalogProductRepository;
        this.jsonHelper = jsonHelper;
        this.bitrixSyncServiceProvider = bitrixSyncServiceProvider;
        this.manufacturerServiceProvider = manufacturerServiceProvider;
    }

    public List<CatalogProduct> getActiveProducts() {
        return catalogProductRepository.findAllByActiveTrueOrderByNameAsc();
    }

    public Optional<CatalogProduct> findById(Long id) {
        return catalogProductRepository.findById(id);
    }

    public List<Map<String, Object>> getAdminProducts() {
        return catalogProductRepository.findAll().stream()
                .sorted(Comparator.comparing(CatalogProduct::getUpdatedAt, Comparator.reverseOrder()))
                .map(this::toAdminDto)
                .toList();
    }

    public List<CatalogProduct> findFiltered(String culture, String category, String search, String tag, String season, String sort) {
        Comparator<CatalogProduct> comparator = switch (sort == null ? "" : sort) {
            case "price_desc" -> Comparator.comparing(CatalogProduct::getPrice, Comparator.nullsLast(Comparator.reverseOrder()));
            case "price_asc" -> Comparator.comparing(CatalogProduct::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));
            case "stock_desc" -> Comparator.comparing(CatalogProduct::getStockQuantity, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(CatalogProduct::getName, String.CASE_INSENSITIVE_ORDER);
        };
        String normalizedSearch = TextUtils.normalizeToken(search);
        return getActiveProducts().stream()
                .filter(product -> TextUtils.containsToken(product.getCulturesIndex(), culture))
                .filter(product -> category == null || category.isBlank() || category.equalsIgnoreCase(product.getCategory()))
                .filter(product -> TextUtils.containsToken(product.getTagsIndex(), tag))
                .filter(product -> matchesSeason(product, season))
                .filter(product -> normalizedSearch.isBlank() || TextUtils.normalizeToken(buildSearchText(product)).contains(normalizedSearch))
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    public Map<String, Object> buildFilterSummary(String culture) {
        Set<String> cultures = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        Set<String> seasons = new LinkedHashSet<>();
        List<CatalogProduct> activeProducts = getActiveProducts();
        for (CatalogProduct product : activeProducts) {
            cultures.addAll(getStringList(product.getCulturesJson()));
        }
        for (CatalogProduct product : activeProducts.stream()
                .filter(item -> TextUtils.containsToken(item.getCulturesIndex(), culture))
                .toList()) {
            tags.addAll(getStringList(product.getTagsJson()));
            if (product.getCategory() != null && !product.getCategory().isBlank()) {
                categories.add(product.getCategory());
            }
            seasons.addAll(extractSeasonValues(product));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cultures", cultures);
        response.put("categories", categories);
        response.put("tags", tags);
        response.put("seasons", seasons);
        response.put("total", getActiveProducts().size());
        return response;
    }

    @Transactional
    public UpsertResult upsertProduct(ImportedProduct importedProduct) {
        return upsertProduct(importedProduct, true);
    }

    @Transactional
    public UpsertResult upsertProduct(ImportedProduct importedProduct, boolean syncToBitrix) {
        CatalogProduct product = importedProduct.externalId() == null
                ? null
                : catalogProductRepository.findByExternalId(importedProduct.externalId()).orElse(null);
        if (product == null && importedProduct.name() != null && !importedProduct.name().isBlank()) {
            String normalizedImportName = TextUtils.normalizeToken(importedProduct.name());
            product = catalogProductRepository.findAll().stream()
                    .filter(existing -> existing.getName() != null && !existing.getName().isBlank())
                    .filter(existing -> TextUtils.normalizeToken(existing.getName()).equals(normalizedImportName))
                    .findFirst()
                    .orElse(null);
        }
        boolean created = product == null;
        if (product == null) {
            product = new CatalogProduct();
        }
        product.setExternalId(importedProduct.externalId());
        product.setSourceFile(importedProduct.sourceFile());
        product.setSku(importedProduct.sku());
        product.setName(importedProduct.name());
        product.setDescription(importedProduct.description());
        product.setBrand(importedProduct.brand());
        product.setCategory(importedProduct.category());
        product.setSubcategory(importedProduct.subcategory());
        product.setItemType(importedProduct.itemType());
        product.setUnitName(importedProduct.unitName());
        product.setPackageType(firstNonBlank(importedProduct.packageType(), product.getPackageType()));
        product.setPackageDescription(firstNonBlank(importedProduct.packageDescription(), product.getPackageDescription()));
        product.setPrice(importedProduct.price());
        product.setStockQuantity(importedProduct.stockQuantity());
        product.setMinOrderQuantity(firstPositive(importedProduct.minOrderQuantity(), product.getMinOrderQuantity()));
        product.setOrderStep(firstPositive(importedProduct.orderStep(), product.getOrderStep()));
        product.setCulturesJson(jsonHelper.writeValue(importedProduct.cultures()));
        product.setPurposesJson(jsonHelper.writeValue(importedProduct.purposes()));
        product.setTagsJson(jsonHelper.writeValue(importedProduct.tags()));
        product.setCulturesIndex(TextUtils.toIndex(importedProduct.cultures()));
        product.setPurposesIndex(TextUtils.toIndex(importedProduct.purposes()));
        product.setTagsIndex(TextUtils.toIndex(importedProduct.tags()));
        product.setFilterMapJson(jsonHelper.writeValue(importedProduct.filterMap()));
        product.setRawDataJson(jsonHelper.writeValue(importedProduct.rawData()));
        product.setActive(true);
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        if (syncToBitrix) {
            syncProductWithBitrix(saved.getId());
        }
        return new UpsertResult(saved, created);
    }

    public List<String> getStringList(String json) {
        return jsonHelper.readValue(json, new TypeReference<>() { }, List.of());
    }

    public OrderRules resolveOrderRules(CatalogProduct product) {
        Map<String, Object> filterMap = jsonHelper.readMap(product.getFilterMapJson());
        Map<String, Object> rawData = jsonHelper.readMap(product.getRawDataJson());
        OrderRules inferred = inferOrderRules(product.getName(), product.getUnitName(), product.getDescription(), rawData, filterMap);
        String unitName = firstNonBlank(product.getUnitName(), inferred.unitName(), "шт");
        BigDecimal minOrderQuantity = firstPositive(product.getMinOrderQuantity(), inferred.minOrderQuantity(), DEFAULT_ORDER_QUANTITY);
        BigDecimal orderStep = firstPositive(product.getOrderStep(), inferred.orderStep(), minOrderQuantity, DEFAULT_ORDER_QUANTITY);
        String packageType = firstNonBlank(product.getPackageType(), inferred.packageType());
        String packageDescription = firstNonBlank(product.getPackageDescription(), inferred.packageDescription());
        return new OrderRules(unitName, minOrderQuantity, orderStep, packageType, packageDescription);
    }

    public OrderRules inferOrderRules(
            String name,
            String unitName,
            String description,
            Map<String, ?> rawData,
            Map<String, ?> filterMap
    ) {
        BigDecimal explicitMin = firstPositive(
                readDecimalValue(filterMap, "minOrderQuantity", "min_order_quantity", "minimumOrderQuantity"),
                readDecimalValue(rawData, "Минимальный заказ", "Мин. заказ", "minOrderQuantity")
        );
        BigDecimal explicitStep = firstPositive(
                readDecimalValue(filterMap, "orderStep", "order_step"),
                readDecimalValue(rawData, "Кратность", "Шаг заказа", "orderStep")
        );
        String explicitPackageType = firstNonBlank(
                readStringValue(filterMap, "packageType", "package_type"),
                readStringValue(rawData, "Тип упаковки", "Упаковка", "packageType")
        );
        String explicitPackageDescription = firstNonBlank(
                readStringValue(filterMap, "packageDescription", "package_description"),
                readStringValue(rawData, "Фасовка", "Тара", "packageDescription")
        );
        String resolvedUnitName = firstNonBlank(
                unitName,
                readStringValue(rawData, "Ед.", "Единица", "unit", "unitName"),
                "шт"
        );
        String textSource = buildOrderRulesText(name, description, rawData, filterMap);
        BigDecimal inferredMin = explicitMin;
        BigDecimal inferredStep = explicitStep;
        String inferredPackageType = explicitPackageType;
        String inferredPackageDescription = explicitPackageDescription;

        if (inferredMin == null || inferredStep == null || inferredPackageType == null || inferredPackageDescription == null) {
            OrderRules detected = detectOrderRulesFromText(textSource, resolvedUnitName);
            inferredMin = firstPositive(inferredMin, detected.minOrderQuantity());
            inferredStep = firstPositive(inferredStep, detected.orderStep());
            inferredPackageType = firstNonBlank(inferredPackageType, detected.packageType());
            inferredPackageDescription = firstNonBlank(inferredPackageDescription, detected.packageDescription());
        }

        String normalizedUnit = TextUtils.normalizeToken(resolvedUnitName);
        if (normalizedUnit.contains("п.е") || normalizedUnit.contains("пе")) {
            inferredMin = firstPositive(inferredMin, DEFAULT_ORDER_QUANTITY);
            inferredStep = firstPositive(inferredStep, DEFAULT_ORDER_QUANTITY);
            inferredPackageType = firstNonBlank(inferredPackageType, "п.е.");
            inferredPackageDescription = firstNonBlank(inferredPackageDescription, "1 посевная единица");
        } else if (normalizedUnit.equals("т") || normalizedUnit.contains("тон")) {
            inferredMin = firstPositive(inferredMin, DEFAULT_ORDER_QUANTITY);
            inferredStep = firstPositive(inferredStep, DEFAULT_ORDER_QUANTITY);
            inferredPackageType = firstNonBlank(inferredPackageType, "тонна");
        } else {
            inferredMin = firstPositive(inferredMin, DEFAULT_ORDER_QUANTITY);
            inferredStep = firstPositive(inferredStep, inferredMin, DEFAULT_ORDER_QUANTITY);
        }

        return new OrderRules(resolvedUnitName, inferredMin, inferredStep, inferredPackageType, inferredPackageDescription);
    }

    public void validateOrderQuantity(CatalogProduct product, BigDecimal quantity) {
        OrderRules rules = resolveOrderRules(product);
        BigDecimal safeQuantity = sanitizePositive(quantity);
        if (safeQuantity == null) {
            throw new IllegalArgumentException("Для товара «" + product.getName() + "» нужно указать количество больше нуля.");
        }
        if (safeQuantity.compareTo(rules.minOrderQuantity()) < 0) {
            throw new IllegalArgumentException("Для товара «" + product.getName() + "» минимальный объем заказа — "
                    + formatQuantity(rules.minOrderQuantity()) + " " + rules.unitName() + ".");
        }
        BigDecimal remainder = safeQuantity.subtract(rules.minOrderQuantity()).remainder(rules.orderStep());
        if (remainder.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException("Для товара «" + product.getName() + "» заказ должен быть кратен "
                    + formatQuantity(rules.orderStep()) + " " + rules.unitName()
                    + " после минимального объема " + formatQuantity(rules.minOrderQuantity()) + " " + rules.unitName() + ".");
        }
    }

    public String buildOrderHint(OrderRules rules) {
        boolean meaningfulQuantityRule = rules.minOrderQuantity() != null && rules.minOrderQuantity().compareTo(DEFAULT_ORDER_QUANTITY) > 0
                || rules.orderStep() != null && rules.orderStep().compareTo(DEFAULT_ORDER_QUANTITY) > 0;
        boolean hasPackageInfo = rules.packageDescription() != null && !rules.packageDescription().isBlank()
                || rules.packageType() != null && !rules.packageType().isBlank();
        if (!meaningfulQuantityRule && !hasPackageInfo) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (rules.packageDescription() != null && !rules.packageDescription().isBlank()) {
            parts.add(rules.packageDescription());
        } else if (rules.packageType() != null && !rules.packageType().isBlank()) {
            parts.add("Фасовка: " + rules.packageType());
        }
        if (meaningfulQuantityRule) {
            parts.add("Мин. заказ: " + formatQuantity(rules.minOrderQuantity()) + " " + rules.unitName());
            parts.add("Кратно: " + formatQuantity(rules.orderStep()) + " " + rules.unitName());
        }
        return String.join(" • ", parts);
    }

    public Map<String, Object> toMiniAppDto(CatalogProduct product) {
        OrderRules orderRules = resolveOrderRules(product);
        Map<String, Object> filterMap = jsonHelper.readMap(product.getFilterMapJson());
        Map<String, Object> rawData = jsonHelper.readMap(product.getRawDataJson());
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", product.getId());
        dto.put("name", product.getName());
        dto.put("description", product.getDescription());
        dto.put("brand", product.getBrand());
        dto.put("category", product.getCategory());
        dto.put("subcategory", product.getSubcategory());
        dto.put("itemType", product.getItemType());
        dto.put("unitName", orderRules.unitName());
        dto.put("price", product.getPrice());
        dto.put("stockQuantity", product.getStockQuantity());
        dto.put("cultures", getStringList(product.getCulturesJson()));
        dto.put("purposes", getStringList(product.getPurposesJson()));
        dto.put("tags", getStringList(product.getTagsJson()));
        dto.put("filterMap", filterMap);
        dto.put("rawData", rawData);
        dto.put("packageType", orderRules.packageType());
        dto.put("packageDescription", orderRules.packageDescription());
        dto.put("minOrderQuantity", orderRules.minOrderQuantity());
        dto.put("orderStep", orderRules.orderStep());
        dto.put("orderHint", buildOrderHint(orderRules));
        dto.put("imageStyle", buildImageStyle(product));
        dto.put("oldPrice", firstPositive(
                parseFlexibleDecimal(Objects.toString(filterMap.getOrDefault("oldPrice", ""), "")),
                parseFlexibleDecimal(Objects.toString(rawData.getOrDefault("Старая цена", ""), ""))
        ));
        dto.put("activeIngredient", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("activeIngredient", ""), ""),
                Objects.toString(rawData.getOrDefault("Действующее вещество", ""), ""),
                Objects.toString(rawData.getOrDefault("действующее вещество", ""), "")
        ));
        return dto;
    }

    public Map<String, Object> toAdminDto(CatalogProduct product) {
        Map<String, Object> dto = new LinkedHashMap<>(toMiniAppDto(product));
        dto.put("externalId", product.getExternalId());
        dto.put("sourceFile", product.getSourceFile());
        dto.put("sku", product.getSku());
        dto.put("active", product.isActive());
        dto.put("updatedAt", product.getUpdatedAt());
        return dto;
    }

    @Transactional
    public CatalogProduct createManualProduct(AdminProductPayload payload) {
        CatalogProduct product = new CatalogProduct();
        applyPayload(product, payload);
        if (product.getName() == null || product.getName().isBlank()) {
            throw new IllegalArgumentException("Название товара обязательно");
        }
        if (product.getExternalId() == null || product.getExternalId().isBlank()) {
            product.setExternalId("manual-" + System.currentTimeMillis());
        }
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        syncProductWithBitrix(saved.getId());
        return saved;
    }

    @Transactional
    public CatalogProduct updateProduct(Long id, AdminProductPayload payload) {
        CatalogProduct product = catalogProductRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        applyPayload(product, payload);
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        syncProductWithBitrix(saved.getId());
        return saved;
    }

    @Transactional
    public void updateManufacturerName(Long productId, String manufacturerName) {
        CatalogProduct product = catalogProductRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        product.setBrand(blankToNull(manufacturerName));
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        syncProductWithBitrix(saved.getId());
    }

    @Transactional
    public void deleteProduct(Long id) {
        CatalogProduct product = catalogProductRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        deleteProductInBitrix(product);
        catalogProductRepository.delete(product);
    }

    @Transactional
    public int deactivateMissingSourceProducts(String sourceFile, Set<String> keepExternalIds) {
        return deactivateMissingSourceProducts(sourceFile, keepExternalIds, true);
    }

    @Transactional
    public int deactivateMissingSourceProducts(String sourceFile, Set<String> keepExternalIds, boolean syncToBitrix) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return 0;
        }
        int deactivated = 0;
        for (CatalogProduct product : catalogProductRepository.findAllBySourceFile(sourceFile)) {
            String externalId = product.getExternalId();
            if (externalId != null && keepExternalIds.contains(externalId)) {
                continue;
            }
            if (product.isActive()) {
                product.setActive(false);
                CatalogProduct saved = catalogProductRepository.save(product);
                if (syncToBitrix) {
                    syncProductWithBitrix(saved.getId());
                }
                deactivated++;
            }
        }
        return deactivated;
    }

    public void syncAllProductsToBitrix() {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService != null) {
            bitrixSyncService.syncAllLocalProducts();
        }
    }

    private void applyPayload(CatalogProduct product, AdminProductPayload payload) {
        product.setExternalId(payload.externalId());
        product.setSourceFile(payload.sourceFile());
        product.setSku(payload.sku());
        product.setName(payload.name());
        product.setDescription(payload.description());
        product.setBrand(payload.brand());
        product.setCategory(payload.category());
        product.setSubcategory(payload.subcategory());
        product.setItemType(payload.itemType());
        product.setUnitName(payload.unitName());
        product.setPackageType(blankToNull(payload.packageType()));
        product.setPackageDescription(blankToNull(payload.packageDescription()));
        product.setPrice(payload.price());
        product.setStockQuantity(payload.stockQuantity());
        product.setMinOrderQuantity(sanitizePositive(payload.minOrderQuantity()));
        product.setOrderStep(sanitizePositive(payload.orderStep()));
        product.setActive(payload.active() == null || payload.active());
        List<String> cultures = payload.cultures() == null ? List.of() : payload.cultures();
        List<String> purposes = payload.purposes() == null ? List.of() : payload.purposes();
        List<String> tags = payload.tags() == null ? List.of() : payload.tags();
        product.setCulturesJson(jsonHelper.writeValue(cultures));
        product.setPurposesJson(jsonHelper.writeValue(purposes));
        product.setTagsJson(jsonHelper.writeValue(tags));
        product.setCulturesIndex(TextUtils.toIndex(cultures));
        product.setPurposesIndex(TextUtils.toIndex(purposes));
        product.setTagsIndex(TextUtils.toIndex(tags));
        product.setFilterMapJson(jsonHelper.writeValue(payload.filterMap() == null ? Map.of() : payload.filterMap()));
        product.setRawDataJson(jsonHelper.writeValue(payload.rawData() == null ? Map.of() : payload.rawData()));
    }

    private String buildSearchText(CatalogProduct product) {
        return String.join(" ",
                Objects.toString(product.getName(), ""),
                Objects.toString(product.getDescription(), ""),
                Objects.toString(product.getCategory(), ""),
                Objects.toString(product.getSubcategory(), ""),
                Objects.toString(product.getBrand(), ""),
                String.join(" ", getStringList(product.getCulturesJson())),
                String.join(" ", getStringList(product.getTagsJson())));
    }

    private boolean matchesSeason(CatalogProduct product, String season) {
        String normalizedSeason = normalizeSeasonValue(season);
        if (normalizedSeason.isBlank()) {
            return true;
        }
        return extractSeasonValues(product).stream()
                .map(this::normalizeSeasonValue)
                .anyMatch(normalizedSeason::equals);
    }

    private List<String> extractSeasonValues(CatalogProduct product) {
        LinkedHashSet<String> seasons = new LinkedHashSet<>();
        Object seasonValue = jsonHelper.readMap(product.getFilterMapJson()).get("season");
        seasons.addAll(toStringList(seasonValue));
        for (String tag : getStringList(product.getTagsJson())) {
            String normalized = normalizeSeasonValue(tag);
            if (!normalized.isBlank()) {
                seasons.add(displaySeasonValue(normalized));
            }
        }
        return new ArrayList<>(seasons);
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> Objects.toString(item, "").trim())
                    .filter(item -> !item.isBlank())
                    .map(this::displaySeasonValue)
                    .toList();
        }
        String raw = Objects.toString(value, "").trim();
        if (raw.isBlank()) {
            return List.of();
        }
        return List.of(displaySeasonValue(raw));
    }

    private String normalizeSeasonValue(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.contains("озим")) {
            return "озимые";
        }
        if (normalized.contains("яров")) {
            return "яровые";
        }
        return "";
    }

    private String displaySeasonValue(String value) {
        String normalized = normalizeSeasonValue(value);
        if ("озимые".equals(normalized)) {
            return "Озимые";
        }
        if ("яровые".equals(normalized)) {
            return "Яровые";
        }
        return value == null ? "" : value.trim();
    }

    private Map<String, String> buildImageStyle(CatalogProduct product) {
        int hue = Math.abs(Objects.hash(product.getName())) % 360;
        Map<String, String> style = new LinkedHashMap<>();
        style.put("primary", "hsl(" + hue + " 42% 30%)");
        style.put("secondary", "hsl(" + ((hue + 40) % 360) + " 48% 58%)");
        style.put("accent", "hsl(" + ((hue + 85) % 360) + " 52% 76%)");
        return style;
    }

    private OrderRules detectOrderRulesFromText(String textSource, String unitName) {
        BigDecimal minOrderQuantity = null;
        BigDecimal orderStep = null;
        String packageType = null;
        String packageDescription = null;

        Matcher boxMultiplier = BOX_MULTIPLIER_PATTERN.matcher(textSource);
        if (boxMultiplier.find()) {
            BigDecimal units = parseFlexibleDecimal(boxMultiplier.group(1));
            BigDecimal volume = parseFlexibleDecimal(boxMultiplier.group(2));
            BigDecimal total = positiveMultiply(units, volume);
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "коробка";
                packageDescription = "Коробка " + formatQuantity(units) + " × " + formatQuantity(volume) + " " + normalizeUnitLabel(boxMultiplier.group(3));
            }
        }

        Matcher boxTotal = BOX_TOTAL_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && boxTotal.find()) {
            BigDecimal total = parseFlexibleDecimal(boxTotal.group(1));
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "коробка";
                packageDescription = "Коробка " + formatQuantity(total) + " " + normalizeUnitLabel(boxTotal.group(2));
            }
        }

        Matcher explicitTotal = TOTAL_VOLUME_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && explicitTotal.find() && TextUtils.normalizeToken(textSource).contains("канистр")) {
            BigDecimal total = parseFlexibleDecimal(explicitTotal.group(1));
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = firstNonBlank(packageType, "коробка");
                packageDescription = firstNonBlank(packageDescription, "Коробка " + formatQuantity(total) + " " + normalizeUnitLabel(explicitTotal.group(2)));
            }
        }

        Matcher canisterCount = CANISTER_COUNT_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && canisterCount.find()) {
            BigDecimal count = parseFlexibleDecimal(canisterCount.group(1));
            BigDecimal volume = parseFlexibleDecimal(canisterCount.group(2));
            BigDecimal total = positiveMultiply(count, volume);
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "коробка";
                packageDescription = "Коробка " + formatQuantity(count) + " канистр по " + formatQuantity(volume) + " " + normalizeUnitLabel(canisterCount.group(3));
            }
        }

        Matcher canister = CANISTER_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && canister.find()) {
            BigDecimal total = parseFlexibleDecimal(canister.group(1));
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "канистра";
                packageDescription = "Канистра " + formatQuantity(total) + " " + normalizeUnitLabel(canister.group(2));
            }
        }

        Matcher peMatcher = PE_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && peMatcher.find()) {
            BigDecimal total = parseFlexibleDecimal(peMatcher.group(1));
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "п.е.";
                packageDescription = formatQuantity(total) + " посевная единица";
            }
        }

        Matcher tonMatcher = TON_PATTERN.matcher(textSource);
        if (minOrderQuantity == null && tonMatcher.find()) {
            BigDecimal total = parseFlexibleDecimal(tonMatcher.group(1));
            if (total != null) {
                minOrderQuantity = total;
                orderStep = total;
                packageType = "тонна";
                packageDescription = formatQuantity(total) + " т";
            }
        }

        String normalizedUnit = TextUtils.normalizeToken(unitName);
        if (minOrderQuantity == null && (normalizedUnit.contains("п.е") || normalizedUnit.contains("пе"))) {
            minOrderQuantity = DEFAULT_ORDER_QUANTITY;
            orderStep = DEFAULT_ORDER_QUANTITY;
            packageType = firstNonBlank(packageType, "п.е.");
            packageDescription = firstNonBlank(packageDescription, "1 посевная единица");
        }
        if (minOrderQuantity == null && (normalizedUnit.equals("т") || normalizedUnit.contains("тон"))) {
            minOrderQuantity = DEFAULT_ORDER_QUANTITY;
            orderStep = DEFAULT_ORDER_QUANTITY;
            packageType = firstNonBlank(packageType, "тонна");
        }
        if (minOrderQuantity == null) {
            minOrderQuantity = DEFAULT_ORDER_QUANTITY;
        }
        if (orderStep == null) {
            orderStep = minOrderQuantity;
        }
        return new OrderRules(firstNonBlank(unitName, "шт"), minOrderQuantity, orderStep, packageType, packageDescription);
    }

    private String buildOrderRulesText(String name, String description, Map<String, ?> rawData, Map<String, ?> filterMap) {
        StringBuilder builder = new StringBuilder();
        appendOrderSource(builder, name);
        appendOrderSource(builder, description);
        appendOrderSource(builder, flattenValue(rawData));
        appendOrderSource(builder, flattenValue(filterMap));
        return builder.toString();
    }

    private void appendOrderSource(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private String flattenValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream()
                    .map(this::flattenValue)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.joining(" "));
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::flattenValue)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.joining(" "));
        }
        return Objects.toString(value, "");
    }

    private BigDecimal readDecimalValue(Map<String, ?> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (TextUtils.normalizeToken(Objects.toString(entry.getKey(), "")).equals(TextUtils.normalizeToken(key))) {
                    BigDecimal parsed = parseFlexibleDecimal(Objects.toString(entry.getValue(), ""));
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private String readStringValue(Map<String, ?> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (TextUtils.normalizeToken(Objects.toString(entry.getKey(), "")).equals(TextUtils.normalizeToken(key))) {
                    String candidate = blankToNull(Objects.toString(entry.getValue(), ""));
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal parseFlexibleDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(',', '.').replaceAll("[^\\d.]+", " ").trim();
        if (normalized.isBlank()) {
            return null;
        }
        String firstToken = normalized.split("\\s+")[0];
        try {
            BigDecimal parsed = new BigDecimal(firstToken);
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed.stripTrailingZeros() : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal positiveMultiply(BigDecimal left, BigDecimal right) {
        BigDecimal safeLeft = sanitizePositive(left);
        BigDecimal safeRight = sanitizePositive(right);
        if (safeLeft == null || safeRight == null) {
            return null;
        }
        return safeLeft.multiply(safeRight).stripTrailingZeros();
    }

    private BigDecimal sanitizePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value.stripTrailingZeros();
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            BigDecimal safe = sanitizePositive(value);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String formatQuantity(BigDecimal value) {
        if (value == null) {
            return "1";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() <= 0
                ? normalized.toPlainString()
                : normalized.setScale(Math.min(3, Math.max(normalized.scale(), 0)), RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String normalizeUnitLabel(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.startsWith("лит")) {
            return "л";
        }
        if (normalized.startsWith("кил")) {
            return "кг";
        }
        return blankToNull(value) == null ? "шт" : value.trim();
    }

    private void ensureManufacturerExists(String brand) {
        ManufacturerService manufacturerService = manufacturerServiceProvider.getIfAvailable();
        if (manufacturerService != null) {
            manufacturerService.ensureExists(brand);
        }
    }

    private void syncProductWithBitrix(Long productId) {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService != null && productId != null) {
            bitrixSyncService.syncLocalProduct(productId);
        }
    }

    private void deleteProductInBitrix(CatalogProduct product) {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService != null) {
            bitrixSyncService.deleteRemoteProduct(product);
        }
    }

    public record ImportedProduct(
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
            Map<String, String> rawData
    ) {
        public ImportedProduct {
            cultures = cultures == null ? new ArrayList<>() : cultures;
            purposes = purposes == null ? new ArrayList<>() : purposes;
            tags = tags == null ? new ArrayList<>() : tags;
            filterMap = filterMap == null ? new LinkedHashMap<>() : filterMap;
            rawData = rawData == null ? new LinkedHashMap<>() : rawData;
        }
    }

    public record UpsertResult(
            CatalogProduct product,
            boolean created
    ) {
    }

    public record AdminProductPayload(
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
            Map<String, Object> rawData,
            Boolean active
    ) {
    }

    public record OrderRules(
            String unitName,
            BigDecimal minOrderQuantity,
            BigDecimal orderStep,
            String packageType,
            String packageDescription
    ) {
    }
}
