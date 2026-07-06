package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.CatalogStructure;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final BigDecimal DEFAULT_ORDER_QUANTITY = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_CATALOG_PRICE = new BigDecimal("10");
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
        ImportedProduct normalizedImportedProduct = normalizeImportedProduct(importedProduct);
        CatalogProduct product = findExistingProductForImport(normalizedImportedProduct).product();
        boolean created = product == null;
        if (product == null) {
            product = new CatalogProduct();
        } else if (!created && !hasImportedChanges(product, normalizedImportedProduct)) {
            return new UpsertResult(product, false, false);
        }
        product.setExternalId(firstNonBlank(normalizedImportedProduct.externalId(), product.getExternalId(), created ? "manual-" + System.currentTimeMillis() : null));
        product.setSourceFile(firstNonBlank(normalizedImportedProduct.sourceFile(), product.getSourceFile()));
        product.setSku(firstNonBlank(normalizedImportedProduct.sku(), product.getSku()));
        product.setName(firstNonBlank(normalizedImportedProduct.name(), product.getName()));
        product.setDescription(firstNonBlank(normalizedImportedProduct.description(), product.getDescription()));
        product.setBrand(firstNonBlank(normalizedImportedProduct.brand(), product.getBrand()));
        product.setCategory(firstNonBlank(normalizedImportedProduct.category(), product.getCategory()));
        product.setSubcategory(firstNonBlank(normalizedImportedProduct.subcategory(), product.getSubcategory()));
        product.setItemType(firstNonBlank(normalizedImportedProduct.itemType(), product.getItemType()));
        product.setUnitName(firstNonBlank(normalizedImportedProduct.unitName(), product.getUnitName()));
        product.setPackageType(firstNonBlank(normalizedImportedProduct.packageType(), product.getPackageType()));
        product.setPackageDescription(firstNonBlank(normalizedImportedProduct.packageDescription(), product.getPackageDescription()));
        product.setPrice(firstPositive(normalizedImportedProduct.price(), product.getPrice()));
        product.setStockQuantity(firstPositive(normalizedImportedProduct.stockQuantity(), product.getStockQuantity()));
        product.setMinOrderQuantity(firstPositive(normalizedImportedProduct.minOrderQuantity(), product.getMinOrderQuantity()));
        product.setOrderStep(firstPositive(normalizedImportedProduct.orderStep(), product.getOrderStep()));
        List<String> mergedCultures = normalizedImportedProduct.cultures() == null || normalizedImportedProduct.cultures().isEmpty()
                ? getStringList(product.getCulturesJson())
                : normalizedImportedProduct.cultures();
        List<String> mergedPurposes = normalizedImportedProduct.purposes() == null || normalizedImportedProduct.purposes().isEmpty()
                ? getStringList(product.getPurposesJson())
                : normalizedImportedProduct.purposes();
        List<String> mergedTags = normalizedImportedProduct.tags() == null || normalizedImportedProduct.tags().isEmpty()
                ? getStringList(product.getTagsJson())
                : normalizedImportedProduct.tags();
        product.setCulturesJson(jsonHelper.writeValue(mergedCultures));
        product.setPurposesJson(jsonHelper.writeValue(mergedPurposes));
        product.setTagsJson(jsonHelper.writeValue(mergedTags));
        product.setCulturesIndex(TextUtils.toIndex(mergedCultures));
        product.setPurposesIndex(TextUtils.toIndex(mergedPurposes));
        product.setTagsIndex(TextUtils.toIndex(mergedTags));
        product.setFilterMapJson(jsonHelper.writeValue(mergeMaps(jsonHelper.readMap(product.getFilterMapJson()), normalizedImportedProduct.filterMap())));
        product.setRawDataJson(jsonHelper.writeValue(mergeStringMaps(jsonHelper.readValue(product.getRawDataJson(), new TypeReference<>() { }, Map.of()), normalizedImportedProduct.rawData())));
        product.setActive(true);
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        if (syncToBitrix) {
            syncProductWithBitrix(saved.getId());
        }
        return new UpsertResult(saved, created, true);
    }

    public ImportMatch findExistingProductForImport(ImportedProduct importedProduct) {
        return findExistingProductMatch(normalizeImportedProduct(importedProduct));
    }

    public ImportedProduct prepareImportedProduct(ImportedProduct importedProduct) {
        return normalizeImportedProduct(importedProduct);
    }

    private ImportMatch findExistingProductMatch(ImportedProduct importedProduct) {
        CatalogProduct product = importedProduct.externalId() == null || importedProduct.externalId().isBlank()
                ? null
                : catalogProductRepository.findByExternalId(importedProduct.externalId()).orElse(null);
        if (product != null) {
            return new ImportMatch(product, "externalId");
        }
        String normalizedImportName = TextUtils.normalizeToken(importedProduct.name());
        if (normalizedImportName.isBlank()) {
            return new ImportMatch(null, "");
        }
        CatalogProduct matchedByName = catalogProductRepository.findAll().stream()
                .filter(existing -> existing.getName() != null && !existing.getName().isBlank())
                .filter(existing -> TextUtils.normalizeToken(existing.getName()).equals(normalizedImportName))
                .findFirst()
                .orElse(null);
        return new ImportMatch(matchedByName, matchedByName == null ? "" : "name");
    }

    public List<String> getStringList(String json) {
        return jsonHelper.readValue(json, new TypeReference<>() { }, List.of());
    }

    public List<ImportFieldChange> previewImportedChanges(CatalogProduct existing, ImportedProduct importedProduct) {
        if (existing == null || importedProduct == null) {
            return List.of();
        }
        ImportedProduct normalizedImportedProduct = normalizeImportedProduct(importedProduct);
        List<String> mergedCultures = normalizedImportedProduct.cultures() == null || normalizedImportedProduct.cultures().isEmpty()
                ? getStringList(existing.getCulturesJson())
                : normalizedImportedProduct.cultures();
        List<String> mergedPurposes = normalizedImportedProduct.purposes() == null || normalizedImportedProduct.purposes().isEmpty()
                ? getStringList(existing.getPurposesJson())
                : normalizedImportedProduct.purposes();
        List<String> mergedTags = normalizedImportedProduct.tags() == null || normalizedImportedProduct.tags().isEmpty()
                ? getStringList(existing.getTagsJson())
                : normalizedImportedProduct.tags();
        Map<String, Object> existingFilterMap = jsonHelper.readMap(existing.getFilterMapJson());
        Map<String, Object> mergedFilterMap = mergeMaps(existingFilterMap, normalizedImportedProduct.filterMap());
        List<ImportFieldChange> changes = new ArrayList<>();
        addStringChange(changes, "Название", existing.getName(), firstNonBlank(normalizedImportedProduct.name(), existing.getName()));
        addStringChange(changes, "Описание", existing.getDescription(), firstNonBlank(normalizedImportedProduct.description(), existing.getDescription()));
        addStringChange(changes, "Производитель", existing.getBrand(), firstNonBlank(normalizedImportedProduct.brand(), existing.getBrand()));
        addStringChange(changes, "Раздел", existing.getCategory(), firstNonBlank(normalizedImportedProduct.category(), existing.getCategory()));
        addStringChange(changes, "Категория", existing.getSubcategory(), firstNonBlank(normalizedImportedProduct.subcategory(), existing.getSubcategory()));
        addStringChange(changes, "Тип товара", existing.getItemType(), firstNonBlank(normalizedImportedProduct.itemType(), existing.getItemType()));
        addStringChange(changes, "Единица", existing.getUnitName(), firstNonBlank(normalizedImportedProduct.unitName(), existing.getUnitName()));
        addStringChange(changes, "Тип упаковки", existing.getPackageType(), firstNonBlank(normalizedImportedProduct.packageType(), existing.getPackageType()));
        addStringChange(changes, "Фасовка", existing.getPackageDescription(), firstNonBlank(normalizedImportedProduct.packageDescription(), existing.getPackageDescription()));
        addDecimalChange(changes, "Цена", existing.getPrice(), firstPositive(normalizedImportedProduct.price(), existing.getPrice()));
        addDecimalChange(changes, "Остаток", existing.getStockQuantity(), firstPositive(normalizedImportedProduct.stockQuantity(), existing.getStockQuantity()));
        addDecimalChange(changes, "Мин. заказ", existing.getMinOrderQuantity(), firstPositive(normalizedImportedProduct.minOrderQuantity(), existing.getMinOrderQuantity()));
        addDecimalChange(changes, "Шаг заказа", existing.getOrderStep(), firstPositive(normalizedImportedProduct.orderStep(), existing.getOrderStep()));
        addListChange(changes, "Культуры", getStringList(existing.getCulturesJson()), mergedCultures);
        addListChange(changes, "Назначения", getStringList(existing.getPurposesJson()), mergedPurposes);
        addListChange(changes, "Теги", getStringList(existing.getTagsJson()), mergedTags);
        addMapFieldChange(changes, "Действующее вещество", existingFilterMap, mergedFilterMap, "activeIngredient");
        addMapFieldChange(changes, "Технология возделывания", existingFilterMap, mergedFilterMap, "cultivationTechnology");
        addMapFieldChange(changes, "ФАО", existingFilterMap, mergedFilterMap, "seedFao");
        addMapFieldChange(changes, "Количество семян", existingFilterMap, mergedFilterMap, "seedsPerBag");
        addMapFieldChange(changes, "Группа спелости", existingFilterMap, mergedFilterMap, "seedMaturityGroup");
        addMapFieldChange(changes, "Репродукция", existingFilterMap, mergedFilterMap, "seedReproduction");
        addMapFieldChange(changes, "Срок вегетации", existingFilterMap, mergedFilterMap, "seedVegetationPeriod");
        addBooleanMapFieldChange(changes, "Для теплицы", existingFilterMap, mergedFilterMap, "forGreenhouse");
        return changes;
    }

    public OrderRules resolveOrderRules(CatalogProduct product) {
        Map<String, Object> filterMap = jsonHelper.readMap(product.getFilterMapJson());
        Map<String, Object> rawData = jsonHelper.readMap(product.getRawDataJson());
        OrderRules inferred = inferOrderRules(product.getName(), product.getUnitName(), product.getDescription(), rawData, filterMap);
        String unitName = firstNonBlank(product.getUnitName(), inferred.unitName(), "шт");
        BigDecimal inferredMin = sanitizeOrderRuleQuantity(inferred.minOrderQuantity(), unitName, inferred.packageDescription());
        BigDecimal inferredStep = sanitizeOrderRuleQuantity(inferred.orderStep(), unitName, inferred.packageDescription());
        String inferredPackageType = sanitizePackageType(inferred.packageType(), inferred.packageDescription());
        String inferredPackageDescription = sanitizePackageDescription(inferred.packageDescription());
        BigDecimal explicitMin = sanitizeOrderRuleQuantity(product.getMinOrderQuantity(), unitName, product.getPackageDescription());
        BigDecimal explicitStep = sanitizeOrderRuleQuantity(product.getOrderStep(), unitName, product.getPackageDescription());
        String explicitPackageType = sanitizePackageType(product.getPackageType(), product.getPackageDescription());
        String explicitPackageDescription = sanitizePackageDescription(product.getPackageDescription());
        BigDecimal minOrderQuantity = firstPositive(explicitMin, inferredMin, DEFAULT_ORDER_QUANTITY);
        BigDecimal orderStep = firstPositive(explicitStep, inferredStep, minOrderQuantity, DEFAULT_ORDER_QUANTITY);
        String packageType = firstNonBlank(explicitPackageType, inferredPackageType);
        String packageDescription = firstNonBlank(explicitPackageDescription, inferredPackageDescription);
        if (shouldDefaultBigBagForSeed(product.getCategory(), product.getSubcategory(), getStringList(product.getCulturesJson()), product.getName(), product.getDescription())) {
            packageType = firstNonBlank(packageType, "биг-бэг");
            packageDescription = firstNonBlank(packageDescription, "Биг-бэг");
        }
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

        if (shouldDefaultBigBagForSeed(
                CatalogStructure.normalizeSectionName(readStringValue(rawData, "Категория", "Раздел", "category")),
                readStringValue(rawData, "Подкатегория", "Культура", "subcategory"),
                Arrays.asList(
                        readStringValue(rawData, "Культура"),
                        readStringValue(rawData, "Культуры"),
                        readStringValue(rawData, "Подкатегория")
                ),
                name,
                description
        )) {
            inferredPackageType = firstNonBlank(inferredPackageType, "биг-бэг");
            inferredPackageDescription = firstNonBlank(inferredPackageDescription, "Биг-бэг");
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
        BigDecimal basePrice = firstPositive(product.getPrice(), DEFAULT_CATALOG_PRICE);
        BigDecimal discountPercent = firstPositive(
                parseFlexibleDecimal(Objects.toString(filterMap.getOrDefault("discountPercent", ""), "")),
                parseFlexibleDecimal(Objects.toString(rawData.getOrDefault("Скидка", ""), ""))
        );
        BigDecimal effectivePrice = applyDiscount(basePrice, discountPercent);
        BigDecimal legacyOldPrice = firstPositive(
                parseFlexibleDecimal(Objects.toString(filterMap.getOrDefault("oldPrice", ""), "")),
                parseFlexibleDecimal(Objects.toString(rawData.getOrDefault("Старая цена", ""), ""))
        );
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", product.getId());
        dto.put("name", product.getName());
        dto.put("description", product.getDescription());
        dto.put("brand", product.getBrand());
        dto.put("category", product.getCategory());
        dto.put("subcategory", product.getSubcategory());
        dto.put("itemType", product.getItemType());
        dto.put("unitName", orderRules.unitName());
        dto.put("price", effectivePrice);
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
        dto.put("orderDisplayUnit", resolveDisplayUnit(product));
        dto.put("imageStyle", buildImageStyle(product));
        dto.put("oldPrice", effectivePrice != null && basePrice != null && effectivePrice.compareTo(basePrice) < 0 ? basePrice : legacyOldPrice);
        dto.put("discountPercent", discountPercent);
        dto.put("activeIngredient", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("activeIngredient", ""), ""),
                Objects.toString(rawData.getOrDefault("Действующее вещество", ""), ""),
                Objects.toString(rawData.getOrDefault("действующее вещество", ""), "")
        ));
        dto.put("forGreenhouse", isProductForGreenhouse(filterMap, rawData));
        dto.put("seedFao", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("seedFao", ""), ""),
                Objects.toString(filterMap.getOrDefault("fao", ""), ""),
                Objects.toString(rawData.getOrDefault("ФАО", ""), ""),
                Objects.toString(rawData.getOrDefault("FAO", ""), "")
        ));
        dto.put("seedsPerBag", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("seedsPerBag", ""), ""),
                Objects.toString(filterMap.getOrDefault("bagSeedCount", ""), ""),
                Objects.toString(rawData.getOrDefault("Семян в мешке", ""), ""),
                Objects.toString(rawData.getOrDefault("Количество семян в мешке", ""), "")
        ));
        String cultivationTechnology = firstNonBlank(
                Objects.toString(filterMap.getOrDefault("cultivationTechnology", ""), ""),
                Objects.toString(filterMap.getOrDefault("seedTechnology", ""), ""),
                Objects.toString(rawData.getOrDefault("Технология возделывания", ""), ""),
                Objects.toString(rawData.getOrDefault("Технология обработки", ""), "")
        );
        if (isSunflowerSeedProduct(product) && TextUtils.normalizeToken(cultivationTechnology).contains("sulfo")) {
            cultivationTechnology = "";
        }
        dto.put("cultivationTechnology", cultivationTechnology);
        dto.put("seedMaturityGroup", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("seedMaturityGroup", ""), ""),
                Objects.toString(filterMap.getOrDefault("maturityGroup", ""), ""),
                Objects.toString(rawData.getOrDefault("Группа спелости", ""), "")
        ));
        dto.put("seedReproduction", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("seedReproduction", ""), ""),
                Objects.toString(filterMap.getOrDefault("reproduction", ""), ""),
                Objects.toString(rawData.getOrDefault("Репродукция", ""), "")
        ));
        dto.put("seedVegetationPeriod", firstNonBlank(
                Objects.toString(filterMap.getOrDefault("seedVegetationPeriod", ""), ""),
                Objects.toString(filterMap.getOrDefault("vegetationPeriod", ""), ""),
                Objects.toString(rawData.getOrDefault("Срок вегетации", ""), ""),
                Objects.toString(rawData.getOrDefault("Срок созревания", ""), ""),
                Objects.toString(rawData.getOrDefault("Дни вегетации", ""), "")
        ));
        return dto;
    }

    public String resolveDisplayUnit(CatalogProduct product) {
        if (product == null) {
            return "шт";
        }
        String unitName = firstNonBlank(product.getUnitName(), "шт");
        String normalizedUnit = TextUtils.normalizeToken(unitName);
        String packageDescription = blankToNull(product.getPackageDescription());
        String packageType = blankToNull(product.getPackageType());
        boolean volumeUnit = normalizedUnit.equals("л")
                || normalizedUnit.contains("лит")
                || normalizedUnit.equals("кг")
                || normalizedUnit.contains("кил");
        boolean boxLike = BOX_MULTIPLIER_PATTERN.matcher(packageDescription == null ? "" : packageDescription).find()
                || TextUtils.normalizeToken(packageType).contains("короб");
        if (volumeUnit && boxLike) {
            return "шт";
        }
        return unitName;
    }

    public Map<String, Object> toAdminDto(CatalogProduct product) {
        Map<String, Object> dto = new LinkedHashMap<>(toMiniAppDto(product));
        Map<String, Object> filterMap = jsonHelper.readMap(product.getFilterMapJson());
        dto.put("externalId", product.getExternalId());
        dto.put("sourceFile", product.getSourceFile());
        dto.put("sku", product.getSku());
        dto.put("price", firstPositive(product.getPrice(), DEFAULT_CATALOG_PRICE));
        dto.put("discountPercent", firstPositive(
                parseFlexibleDecimal(Objects.toString(filterMap.getOrDefault("discountPercent", ""), "")),
                BigDecimal.ZERO
        ));
        dto.put("active", product.isActive());
        dto.put("updatedAt", product.getUpdatedAt());
        return dto;
    }

    private BigDecimal applyDiscount(BigDecimal basePrice, BigDecimal discountPercent) {
        if (basePrice == null || discountPercent == null || discountPercent.compareTo(BigDecimal.ZERO) <= 0) {
            return basePrice;
        }
        BigDecimal normalizedDiscount = discountPercent.min(new BigDecimal("100"));
        BigDecimal multiplier = BigDecimal.ONE.subtract(normalizedDiscount.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
        return basePrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
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
        return updateProduct(id, payload, true);
    }

    @Transactional
    public CatalogProduct updateProduct(Long id, AdminProductPayload payload, boolean syncToBitrix) {
        CatalogProduct product = catalogProductRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        return updateProduct(product, payload, syncToBitrix);
    }

    @Transactional
    public CatalogProduct updateProduct(CatalogProduct product, AdminProductPayload payload, boolean syncToBitrix) {
        if (product == null || product.getId() == null) {
            throw new IllegalArgumentException("Товар не найден");
        }
        applyPayload(product, payload);
        CatalogProduct saved = catalogProductRepository.save(product);
        ensureManufacturerExists(saved.getBrand());
        if (syncToBitrix) {
            syncProductWithBitrix(saved.getId());
        }
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
        String normalizedCategory = normalizeAdminCategory(
                payload.category(),
                payload.subcategory(),
                payload.name(),
                payload.description(),
                payload.filterMap(),
                payload.rawData()
        );
        String normalizedSubcategory = normalizeAdminSubcategory(normalizedCategory, payload.subcategory(), payload.name(), payload.description());
        product.setExternalId(payload.externalId());
        product.setSourceFile(payload.sourceFile());
        product.setSku(payload.sku());
        product.setName(payload.name());
        product.setDescription(payload.description());
        product.setBrand(payload.brand());
        product.setCategory(normalizedCategory);
        product.setSubcategory(normalizedSubcategory);
        product.setItemType(blankToNull(firstNonBlank(normalizedSubcategory, normalizedCategory, payload.itemType())));
        product.setUnitName(blankToNull(firstNonBlank(
                payload.unitName(),
                CatalogStructure.SEEDS.equals(normalizedCategory) ? "п.е." : null
        )));
        product.setPrice(firstPositive(payload.price(), DEFAULT_CATALOG_PRICE));
        product.setStockQuantity(payload.stockQuantity());
        product.setMinOrderQuantity(sanitizePositive(payload.minOrderQuantity()));
        product.setOrderStep(sanitizePositive(payload.orderStep()));
        product.setActive(payload.active() == null || payload.active());
        List<String> cultures = payload.cultures() == null ? List.of() : payload.cultures();
        List<String> purposes = payload.purposes() == null ? List.of() : payload.purposes();
        List<String> tags = payload.tags() == null ? List.of() : payload.tags();
        String packageType = blankToNull(payload.packageType());
        String packageDescription = blankToNull(payload.packageDescription());
        if (shouldDefaultBigBagForSeed(normalizedCategory, normalizedSubcategory, cultures, payload.name(), payload.description())) {
            packageType = firstNonBlank(packageType, "биг-бэг");
            packageDescription = firstNonBlank(packageDescription, "Биг-бэг");
        }
        product.setPackageType(packageType);
        product.setPackageDescription(packageDescription);
        product.setCulturesJson(jsonHelper.writeValue(cultures));
        product.setPurposesJson(jsonHelper.writeValue(purposes));
        product.setTagsJson(jsonHelper.writeValue(tags));
        product.setCulturesIndex(TextUtils.toIndex(cultures));
        product.setPurposesIndex(TextUtils.toIndex(purposes));
        product.setTagsIndex(TextUtils.toIndex(tags));
        Map<String, Object> filterMap = payload.filterMap() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.filterMap());
        if (isTruthy(filterMap.get("forGreenhouse")) || isTruthy(filterMap.get("greenhouse")) || CatalogStructure.CLOSED_GROUND.equals(normalizedCategory)) {
            filterMap.put("forGreenhouse", true);
        } else {
            filterMap.remove("forGreenhouse");
        }
        if (normalizedSubcategory != null && !normalizedSubcategory.isBlank()) {
            filterMap.put("subcategory", List.of(normalizedSubcategory));
        } else {
            filterMap.remove("subcategory");
        }
        if (product.getBrand() != null && !product.getBrand().isBlank()) {
            filterMap.put("manufacturer", List.of(product.getBrand()));
        } else {
            filterMap.remove("manufacturer");
        }
        if (!cultures.isEmpty()) {
            filterMap.put("cultures", new ArrayList<>(cultures));
        } else {
            filterMap.remove("cultures");
        }
        copyStructuredValue(filterMap, "seedFao", "fao");
        copyStructuredValue(filterMap, "seedsPerBag", "bagSeedCount");
        copyStructuredValue(filterMap, "seedMaturityGroup", "maturityGroup");
        copyStructuredValue(filterMap, "seedReproduction", "reproduction");
        copyStructuredValue(filterMap, "seedVegetationPeriod", "vegetationPeriod");
        product.setFilterMapJson(jsonHelper.writeValue(filterMap));
        Map<String, Object> rawData = payload.rawData() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload.rawData());
        putOrRemove(rawData, "Действующее вещество", filterMap.get("activeIngredient"));
        putOrRemove(rawData, "ФАО", filterMap.get("seedFao"));
        putOrRemove(rawData, "Семян в мешке", filterMap.get("seedsPerBag"));
        putOrRemove(rawData, "Группа спелости", filterMap.get("seedMaturityGroup"));
        putOrRemove(rawData, "Репродукция", filterMap.get("seedReproduction"));
        putOrRemove(rawData, "Технология возделывания", filterMap.get("cultivationTechnology"));
        putOrRemove(rawData, "Срок вегетации", filterMap.get("seedVegetationPeriod"));
        putOrRemove(rawData, "Фасовка", product.getPackageDescription());
        product.setRawDataJson(jsonHelper.writeValue(rawData));
    }

    private void copyStructuredValue(Map<String, Object> map, String sourceKey, String aliasKey) {
        Object value = map.get(sourceKey);
        if (value == null) {
            map.remove(aliasKey);
            return;
        }
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) {
            map.remove(aliasKey);
            return;
        }
        map.put(sourceKey, text);
        map.put(aliasKey, text);
    }

    private void putOrRemove(Map<String, Object> map, String key, Object value) {
        String text = Objects.toString(value, "").trim();
        if (!text.isBlank()) {
            map.put(key, text);
            return;
        }
        map.remove(key);
    }

    private boolean isSunflowerSeedProduct(CatalogProduct product) {
        if (!CatalogStructure.SEEDS.equals(CatalogStructure.normalizeSectionName(product.getCategory()))) {
            return false;
        }
        return "Подсолнечник".equals(CatalogStructure.inferSubcategory(
                CatalogStructure.SEEDS,
                String.join(" ",
                        Objects.toString(product.getSubcategory(), ""),
                        Objects.toString(product.getItemType(), ""),
                        Objects.toString(product.getName(), ""),
                        Objects.toString(product.getDescription(), ""))
        ));
    }

    private String normalizeAdminCategory(
            String category,
            String subcategory,
            String name,
            String description,
            Map<String, Object> filterMap,
            Map<String, Object> rawData
    ) {
        if (isProductForGreenhouse(filterMap, rawData)) {
            return CatalogStructure.CLOSED_GROUND;
        }
        String normalized = CatalogStructure.normalizeSectionName(category);
        if (!normalized.isBlank() && !CatalogStructure.OTHER.equalsIgnoreCase(normalized)) {
            return normalized;
        }
        return CatalogStructure.inferSection(String.join(" ", Objects.toString(category, ""), Objects.toString(subcategory, ""), Objects.toString(name, ""), Objects.toString(description, "")), subcategory);
    }

    private String normalizeAdminSubcategory(String category, String subcategory, String name, String description) {
        String normalized = CatalogStructure.inferSubcategory(category, firstNonBlank(subcategory, name, description));
        if (!normalized.isBlank()) {
            return normalized;
        }
        normalized = CatalogStructure.inferSubcategory(category, String.join(" ", Objects.toString(subcategory, ""), Objects.toString(name, ""), Objects.toString(description, "")));
        return blankToNull(firstNonBlank(normalized, subcategory));
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
        appendOrderSource(builder, sanitizePackageDescription(readStringValue(rawData, "Фасовка", "Упаковка", "Тара", "Тип упаковки", "packageDescription", "packageType")));
        appendOrderSource(builder, sanitizePackageDescription(readStringValue(filterMap, "packageDescription", "packageType")));
        appendOrderSource(builder, name);
        return builder.toString();
    }

    private BigDecimal sanitizeOrderRuleQuantity(BigDecimal value, String unitName, String packageDescription) {
        BigDecimal safe = sanitizePositive(value);
        if (safe == null) {
            return null;
        }
        String normalizedUnit = TextUtils.normalizeToken(unitName);
        String normalizedPackage = TextUtils.normalizeToken(packageDescription);
        if ((normalizedUnit.equals("л") || normalizedUnit.startsWith("лит") || normalizedUnit.equals("кг") || normalizedUnit.startsWith("кил"))
                && safe.compareTo(new BigDecimal("500")) > 0) {
            return null;
        }
        if (normalizedPackage.contains("короб") && normalizedPackage.contains("канистр") && safe.compareTo(new BigDecimal("200")) > 0) {
            return null;
        }
        return safe;
    }

    private String sanitizePackageType(String packageType, String packageDescription) {
        String safeDescription = sanitizePackageDescription(packageDescription);
        if (safeDescription == null) {
            return null;
        }
        String normalized = TextUtils.normalizeToken(packageType);
        if (normalized.contains("неопредел")) {
            return null;
        }
        return blankToNull(packageType);
    }

    private String sanitizePackageDescription(String packageDescription) {
        String safe = blankToNull(packageDescription);
        if (safe == null) {
            return null;
        }
        String normalized = TextUtils.normalizeToken(safe);
        if (normalized.contains("неопредел")) {
            return null;
        }
        Matcher matcher = CANISTER_COUNT_PATTERN.matcher(safe);
        if (matcher.find()) {
            BigDecimal count = parseFlexibleDecimal(matcher.group(1));
            if (count != null && count.compareTo(new BigDecimal("50")) > 0) {
                return null;
            }
        }
        return safe;
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

    private boolean isProductForGreenhouse(Map<String, Object> filterMap, Map<String, Object> rawData) {
        return isTruthy(filterMap == null ? null : filterMap.get("forGreenhouse"))
                || isTruthy(filterMap == null ? null : filterMap.get("greenhouse"))
                || isTruthy(rawData == null ? null : rawData.get("Для теплицы"))
                || isTruthy(rawData == null ? null : rawData.get("Теплица"));
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalized = TextUtils.normalizeToken(Objects.toString(value, ""));
        return normalized.equals("true")
                || normalized.equals("1")
                || normalized.equals("yes")
                || normalized.equals("da")
                || normalized.equals("y")
                || normalized.equals("для теплицы")
                || normalized.equals("теплица");
    }

    private void ensureManufacturerExists(String brand) {
        ManufacturerService manufacturerService = manufacturerServiceProvider.getIfAvailable();
        if (manufacturerService != null) {
            manufacturerService.ensureExists(brand);
        }
    }

    private ImportedProduct normalizeImportedProduct(ImportedProduct imported) {
        if (imported == null) {
            return null;
        }
        String packageType = blankToNull(imported.packageType());
        String packageDescription = blankToNull(imported.packageDescription());
        if (shouldDefaultBigBagForSeed(imported.category(), imported.subcategory(), imported.cultures(), imported.name(), imported.description())) {
            packageType = firstNonBlank(packageType, "биг-бэг");
            packageDescription = firstNonBlank(packageDescription, "Биг-бэг");
        }
        Map<String, String> rawData = new LinkedHashMap<>(imported.rawData());
        if (packageDescription != null && !packageDescription.isBlank()) {
            rawData.put("Фасовка", packageDescription);
        }
        return new ImportedProduct(
                imported.externalId(),
                imported.sourceFile(),
                imported.sku(),
                imported.name(),
                imported.description(),
                imported.brand(),
                imported.category(),
                imported.subcategory(),
                imported.itemType(),
                imported.unitName(),
                imported.price(),
                imported.stockQuantity(),
                packageType,
                packageDescription,
                imported.minOrderQuantity(),
                imported.orderStep(),
                imported.cultures(),
                imported.purposes(),
                imported.tags(),
                imported.filterMap(),
                rawData
        );
    }

    private boolean shouldDefaultBigBagForSeed(String category, String subcategory, List<String> cultures, String name, String description) {
        if (!CatalogStructure.SEEDS.equals(CatalogStructure.normalizeSectionName(category))) {
            return false;
        }
        String context = TextUtils.normalizeToken(String.join(" ",
                Objects.toString(subcategory, ""),
                String.join(" ", cultures == null ? List.of() : cultures),
                Objects.toString(name, ""),
                Objects.toString(description, "")
        ));
        if (context.contains("подсолнеч")
                || context.contains("кукуруз")
                || context.contains("рапс")
                || context.contains("свекл")) {
            return false;
        }
        return true;
    }

    private boolean hasImportedChanges(CatalogProduct existing, ImportedProduct imported) {
        ImportedProduct normalizedImported = normalizeImportedProduct(imported);
        List<String> mergedCultures = normalizedImported.cultures() == null || normalizedImported.cultures().isEmpty()
                ? getStringList(existing.getCulturesJson())
                : normalizedImported.cultures();
        List<String> mergedPurposes = normalizedImported.purposes() == null || normalizedImported.purposes().isEmpty()
                ? getStringList(existing.getPurposesJson())
                : normalizedImported.purposes();
        List<String> mergedTags = normalizedImported.tags() == null || normalizedImported.tags().isEmpty()
                ? getStringList(existing.getTagsJson())
                : normalizedImported.tags();
        Map<String, Object> mergedFilterMap = mergeMaps(jsonHelper.readMap(existing.getFilterMapJson()), normalizedImported.filterMap());
        Map<String, String> mergedRawData = mergeStringMaps(
                jsonHelper.readValue(existing.getRawDataJson(), new TypeReference<>() { }, Map.of()),
                normalizedImported.rawData()
        );
        return !Objects.equals(blankToNull(existing.getExternalId()), blankToNull(firstNonBlank(normalizedImported.externalId(), existing.getExternalId())))
                || !Objects.equals(blankToNull(existing.getSourceFile()), blankToNull(firstNonBlank(normalizedImported.sourceFile(), existing.getSourceFile())))
                || !Objects.equals(blankToNull(existing.getSku()), blankToNull(firstNonBlank(normalizedImported.sku(), existing.getSku())))
                || !Objects.equals(blankToNull(existing.getName()), blankToNull(firstNonBlank(normalizedImported.name(), existing.getName())))
                || !Objects.equals(blankToNull(existing.getDescription()), blankToNull(firstNonBlank(normalizedImported.description(), existing.getDescription())))
                || !Objects.equals(blankToNull(existing.getBrand()), blankToNull(firstNonBlank(normalizedImported.brand(), existing.getBrand())))
                || !Objects.equals(blankToNull(existing.getCategory()), blankToNull(firstNonBlank(normalizedImported.category(), existing.getCategory())))
                || !Objects.equals(blankToNull(existing.getSubcategory()), blankToNull(firstNonBlank(normalizedImported.subcategory(), existing.getSubcategory())))
                || !Objects.equals(blankToNull(existing.getItemType()), blankToNull(firstNonBlank(normalizedImported.itemType(), existing.getItemType())))
                || !Objects.equals(blankToNull(existing.getUnitName()), blankToNull(firstNonBlank(normalizedImported.unitName(), existing.getUnitName())))
                || !Objects.equals(blankToNull(existing.getPackageType()), blankToNull(firstNonBlank(normalizedImported.packageType(), existing.getPackageType())))
                || !Objects.equals(blankToNull(existing.getPackageDescription()), blankToNull(firstNonBlank(normalizedImported.packageDescription(), existing.getPackageDescription())))
                || !sameDecimal(existing.getPrice(), firstPositive(normalizedImported.price(), existing.getPrice()))
                || !sameDecimal(existing.getStockQuantity(), firstPositive(normalizedImported.stockQuantity(), existing.getStockQuantity()))
                || !sameDecimal(existing.getMinOrderQuantity(), firstPositive(normalizedImported.minOrderQuantity(), existing.getMinOrderQuantity()))
                || !sameDecimal(existing.getOrderStep(), firstPositive(normalizedImported.orderStep(), existing.getOrderStep()))
                || !Objects.equals(existing.getCulturesJson(), jsonHelper.writeValue(mergedCultures))
                || !Objects.equals(existing.getPurposesJson(), jsonHelper.writeValue(mergedPurposes))
                || !Objects.equals(existing.getTagsJson(), jsonHelper.writeValue(mergedTags))
                || !Objects.equals(existing.getCulturesIndex(), TextUtils.toIndex(mergedCultures))
                || !Objects.equals(existing.getPurposesIndex(), TextUtils.toIndex(mergedPurposes))
                || !Objects.equals(existing.getTagsIndex(), TextUtils.toIndex(mergedTags))
                || !Objects.equals(existing.getFilterMapJson(), jsonHelper.writeValue(mergedFilterMap))
                || !Objects.equals(existing.getRawDataJson(), jsonHelper.writeValue(mergedRawData))
                || !existing.isActive();
    }

    private void addStringChange(List<ImportFieldChange> changes, String label, String before, String after) {
        String safeBefore = blankToNull(before);
        String safeAfter = blankToNull(after);
        if (!Objects.equals(safeBefore, safeAfter)) {
            changes.add(new ImportFieldChange(label, defaultPreviewValue(safeBefore), defaultPreviewValue(safeAfter)));
        }
    }

    private void addDecimalChange(List<ImportFieldChange> changes, String label, BigDecimal before, BigDecimal after) {
        if (!sameDecimal(before, after)) {
            changes.add(new ImportFieldChange(label, before == null ? "—" : formatQuantity(before), after == null ? "—" : formatQuantity(after)));
        }
    }

    private void addListChange(List<ImportFieldChange> changes, String label, List<String> before, List<String> after) {
        String safeBefore = joinPreviewList(before);
        String safeAfter = joinPreviewList(after);
        if (!Objects.equals(safeBefore, safeAfter)) {
            changes.add(new ImportFieldChange(label, safeBefore, safeAfter));
        }
    }

    private void addMapFieldChange(List<ImportFieldChange> changes, String label, Map<String, Object> before, Map<String, Object> after, String key) {
        addStringChange(changes, label, Objects.toString(before.get(key), ""), Objects.toString(after.get(key), ""));
    }

    private void addBooleanMapFieldChange(List<ImportFieldChange> changes, String label, Map<String, Object> before, Map<String, Object> after, String key) {
        String safeBefore = isTruthy(before.get(key)) ? "Да" : "Нет";
        String safeAfter = isTruthy(after.get(key)) ? "Да" : "Нет";
        if (!Objects.equals(safeBefore, safeAfter)) {
            changes.add(new ImportFieldChange(label, safeBefore, safeAfter));
        }
    }

    private String joinPreviewList(List<String> values) {
        List<String> safeValues = values == null ? List.of() : values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        return safeValues.isEmpty() ? "—" : String.join(", ", safeValues);
    }

    private String defaultPreviewValue(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Map<String, Object> mergeMaps(Map<String, Object> existing, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        if (incoming == null || incoming.isEmpty()) {
            return merged;
        }
        incoming.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (value instanceof String stringValue) {
                String normalized = blankToNull(stringValue);
                if (normalized != null) {
                    merged.put(key, normalized);
                }
                return;
            }
            if (value != null) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, String> mergeStringMaps(Map<String, String> existing, Map<String, String> incoming) {
        Map<String, String> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        if (incoming == null || incoming.isEmpty()) {
            return merged;
        }
        incoming.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String normalized = blankToNull(value);
            if (normalized != null) {
                merged.put(key, normalized);
            }
        });
        return merged;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    private void syncProductWithBitrix(Long productId) {
        if (productId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeBitrixSync(productId);
                }
            });
            return;
        }
        executeBitrixSync(productId);
    }

    private void executeBitrixSync(Long productId) {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService != null && productId != null) {
            try {
                bitrixSyncService.syncLocalProduct(productId);
            } catch (RuntimeException exception) {
                log.warn("Bitrix sync skipped for product {}: {}", productId, exception.getMessage());
            }
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
            boolean created,
            boolean changed
    ) {
    }

    public record ImportMatch(
            CatalogProduct product,
            String matchedBy
    ) {
    }

    public record ImportFieldChange(
            String fieldLabel,
            String beforeValue,
            String afterValue
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
