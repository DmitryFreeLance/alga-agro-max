package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.model.CatalogOrderItem;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogOrderRepository;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class BitrixSyncService {

    private static final Logger log = LoggerFactory.getLogger(BitrixSyncService.class);

    private static final String PROPERTY_EXTERNAL_ID = "APP_EXTERNAL_ID";
    private static final String PROPERTY_SOURCE_FILE = "APP_SOURCE_FILE";
    private static final String PROPERTY_SKU = "APP_SKU";
    private static final String PROPERTY_BRAND = "APP_BRAND";
    private static final String PROPERTY_CATEGORY = "APP_CATEGORY";
    private static final String PROPERTY_SUBCATEGORY = "APP_SUBCATEGORY";
    private static final String PROPERTY_ITEM_TYPE = "APP_ITEM_TYPE";
    private static final String PROPERTY_UNIT_NAME = "APP_UNIT_NAME";
    private static final String PROPERTY_CULTURES = "APP_CULTURES";
    private static final String PROPERTY_PURPOSES = "APP_PURPOSES";
    private static final String PROPERTY_TAGS = "APP_TAGS";
    private static final String PROPERTY_PACKAGE_TYPE = "APP_PACKAGE_TYPE";
    private static final String PROPERTY_PACKAGE_DESCRIPTION = "APP_PACKAGE_DESCRIPTION";
    private static final String PROPERTY_MIN_ORDER_QUANTITY = "APP_MIN_ORDER_QUANTITY";
    private static final String PROPERTY_ORDER_STEP = "APP_ORDER_STEP";
    private static final String PROPERTY_FILTER_MAP = "APP_FILTER_MAP";

    private static final List<PropertyDefinition> PROPERTY_DEFINITIONS = List.of(
            new PropertyDefinition(PROPERTY_EXTERNAL_ID, "ID из мини-приложения", "S", 100, 1),
            new PropertyDefinition(PROPERTY_SOURCE_FILE, "Исходный файл", "S", 110, 1),
            new PropertyDefinition(PROPERTY_SKU, "Артикул", "S", 120, 1),
            new PropertyDefinition(PROPERTY_BRAND, "Бренд", "S", 130, 1),
            new PropertyDefinition(PROPERTY_CATEGORY, "Категория mini app", "S", 140, 1),
            new PropertyDefinition(PROPERTY_SUBCATEGORY, "Подкатегория mini app", "S", 150, 1),
            new PropertyDefinition(PROPERTY_ITEM_TYPE, "Тип товара mini app", "S", 160, 1),
            new PropertyDefinition(PROPERTY_UNIT_NAME, "Единица mini app", "S", 170, 1),
            new PropertyDefinition(PROPERTY_CULTURES, "Культуры mini app", "S", 180, 3),
            new PropertyDefinition(PROPERTY_PURPOSES, "Назначения mini app", "S", 190, 3),
            new PropertyDefinition(PROPERTY_TAGS, "Теги mini app", "S", 200, 3),
            new PropertyDefinition(PROPERTY_PACKAGE_TYPE, "Тип упаковки", "S", 210, 1),
            new PropertyDefinition(PROPERTY_PACKAGE_DESCRIPTION, "Описание упаковки", "S", 220, 2),
            new PropertyDefinition(PROPERTY_MIN_ORDER_QUANTITY, "Мин. объем заказа", "N", 230, 1),
            new PropertyDefinition(PROPERTY_ORDER_STEP, "Кратность заказа", "N", 240, 1),
            new PropertyDefinition(PROPERTY_FILTER_MAP, "Служебные фильтры mini app", "S", 250, 6)
    );

    private final AppProperties appProperties;
    private final JsonHelper jsonHelper;
    private final CatalogProductRepository catalogProductRepository;
    private final CatalogOrderRepository catalogOrderRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    private volatile PortalContext portalContext;

    public BitrixSyncService(
            AppProperties appProperties,
            JsonHelper jsonHelper,
            CatalogProductRepository catalogProductRepository,
            CatalogOrderRepository catalogOrderRepository
    ) {
        this.appProperties = appProperties;
        this.jsonHelper = jsonHelper;
        this.catalogProductRepository = catalogProductRepository;
        this.catalogOrderRepository = catalogOrderRepository;
    }

    public boolean enabled() {
        return appProperties.getBitrix().isSyncEnabled()
                && appProperties.getBitrix().getWebhookBaseUrl() != null
                && !appProperties.getBitrix().getWebhookBaseUrl().isBlank();
    }

    public boolean isSyncInProgress() {
        return syncInProgress.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialSync() {
        if (!enabled()) {
            log.info("Bitrix24 sync disabled: webhook is not configured");
            return;
        }
        if (!syncInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            syncFromBitrix();
        } catch (RuntimeException e) {
            log.warn("Initial Bitrix24 sync failed: {}", e.getMessage());
        } finally {
            syncInProgress.set(false);
        }
    }

    @Scheduled(
            initialDelayString = "${app.bitrix.initial-sync-delay-ms:45000}",
            fixedDelayString = "${app.bitrix.poll-interval-ms:180000}"
    )
    public void scheduledPull() {
        if (!enabled() || !syncInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            syncFromBitrix();
        } catch (RuntimeException e) {
            log.warn("Bitrix24 scheduled sync failed: {}", e.getMessage());
        } finally {
            syncInProgress.set(false);
        }
    }

    @Transactional
    public void syncLocalProduct(Long productId) {
        if (!enabled() || productId == null) {
            return;
        }
        CatalogProduct product = catalogProductRepository.findById(productId).orElse(null);
        if (product == null) {
            return;
        }
        if (!product.isActive() && product.getBitrixProductId() == null) {
            return;
        }
        PortalContext context = ensurePortalContext();
        String hash = computeSyncHash(product);
        if (product.getBitrixProductId() != null && hash.equals(product.getBitrixSyncHash())) {
            return;
        }

        Long bitrixProductId = resolveRemoteProductId(context, product);
        Map<String, Object> fields = buildProductFields(context, product);
        if (bitrixProductId == null) {
            JsonNode result = call("catalog.product.add", Map.of("fields", fields));
            bitrixProductId = extractId(result, "product", "element");
        } else {
            try {
                call("catalog.product.update", Map.of("id", bitrixProductId, "fields", fields));
            } catch (IllegalStateException updateError) {
                if (looksLikeMissingRemote(updateError)) {
                    bitrixProductId = null;
                    JsonNode result = call("catalog.product.add", Map.of("fields", fields));
                    bitrixProductId = extractId(result, "product", "element");
                } else {
                    throw updateError;
                }
            }
        }

        Long priceId = upsertProductPrice(context, bitrixProductId, product.getPrice(), product.getBitrixPriceId());
        product.setBitrixProductId(bitrixProductId);
        product.setBitrixPriceId(priceId);
        product.setBitrixSyncHash(hash);
        product.setBitrixSyncedAt(Instant.now());
        catalogProductRepository.save(product);
    }

    @Transactional
    public void syncAllLocalProducts() {
        if (!enabled()) {
            return;
        }
        List<CatalogProduct> products = catalogProductRepository.findAll();
        int synced = 0;
        int failed = 0;
        for (CatalogProduct product : products) {
            if (!product.isActive() && product.getBitrixProductId() == null) {
                continue;
            }
            try {
                syncLocalProduct(product.getId());
                synced++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Bitrix24 outbound sync skipped product id={}, name={}: {}",
                        product.getId(),
                        TextUtils.trimTo(product.getName(), 80),
                        e.getMessage());
            }
        }
        log.info("Bitrix24 outbound sync completed: synced={}, failed={}", synced, failed);
    }

    @Transactional
    public void deleteRemoteProduct(CatalogProduct product) {
        if (!enabled() || product == null || product.getBitrixProductId() == null) {
            return;
        }
        try {
            call("catalog.product.delete", Map.of("id", product.getBitrixProductId()));
        } catch (IllegalStateException e) {
            if (!looksLikeMissingRemote(e)) {
                throw e;
            }
        }
    }

    @Transactional
    public void syncOrderToLead(CatalogOrder order) {
        if (!enabled() || order == null || order.getBitrixLeadId() != null) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("TITLE", appProperties.getBitrix().getLeadTitlePrefix() + " " + order.getPublicCode());
        fields.put("NAME", order.getCustomerName());
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            fields.put("PHONE", List.of(Map.of(
                    "VALUE", order.getCustomerPhone(),
                    "VALUE_TYPE", "WORK"
            )));
        }
        if (order.getCustomerCompany() != null && !order.getCustomerCompany().isBlank()) {
            fields.put("COMPANY_TITLE", order.getCustomerCompany());
        }
        if (order.getTotalPrice() != null) {
            fields.put("OPPORTUNITY", order.getTotalPrice());
        }
        fields.put("CURRENCY_ID", appProperties.getBitrix().getCurrencyId());
        fields.put("COMMENTS", buildLeadComment(order));
        fields.put("OPENED", "Y");
        fields.put("ORIGINATOR_ID", appProperties.getBitrix().getOriginatorId());
        fields.put("ORIGIN_ID", order.getPublicCode());
        if (appProperties.getBitrix().getLeadAssignedById() != null) {
            fields.put("ASSIGNED_BY_ID", appProperties.getBitrix().getLeadAssignedById());
        }
        if (appProperties.getBitrix().getLeadSourceId() != null && !appProperties.getBitrix().getLeadSourceId().isBlank()) {
            fields.put("SOURCE_ID", appProperties.getBitrix().getLeadSourceId());
        }
        JsonNode leadResult = call("crm.lead.add", Map.of("fields", fields, "params", Map.of("REGISTER_SONET_EVENT", "Y")));
        Long leadId = extractSimpleResultId(leadResult);
        if (leadId == null) {
            throw new IllegalStateException("Bitrix24 did not return lead id");
        }
        List<Map<String, Object>> rows = buildLeadRows(order);
        if (!rows.isEmpty()) {
            call("crm.lead.productrows.set", Map.of("id", leadId, "rows", rows));
        }
        order.setBitrixLeadId(leadId);
        catalogOrderRepository.save(order);
    }

    @Transactional
    public void syncFromBitrix() {
        if (!enabled()) {
            return;
        }
        PortalContext context = ensurePortalContext();
        Set<Long> remoteIds = new LinkedHashSet<>();
        Set<Long> seenPageFirstIds = new LinkedHashSet<>();
        int start = 0;
        int total = -1;
        int pageSize = 50;
        boolean fullScanCompleted = true;
        while (true) {
            JsonNode result = call("catalog.product.list", Map.of(
                    "select", buildProductSelect(context),
                    "filter", Map.of("iblockId", context.iblockId()),
                    "order", Map.of("id", "asc"),
                    "start", start
            ));
            JsonNode productsNode = result.path("products");
            if (!productsNode.isArray()) {
                fullScanCompleted = false;
                log.warn("Bitrix24 inbound sync returned non-array products page at start={}", start);
                break;
            }
            if (productsNode.isEmpty()) {
                if (total > 0 && remoteIds.size() < total) {
                    fullScanCompleted = false;
                    log.warn("Bitrix24 inbound sync stopped early: empty page at start={}, scanned={}, expected={}",
                            start, remoteIds.size(), total);
                }
                break;
            }
            long firstId = productsNode.get(0).path("id").asLong(0L);
            if (firstId > 0 && !seenPageFirstIds.add(firstId)) {
                fullScanCompleted = false;
                log.warn("Bitrix24 inbound sync detected repeated page starting from product id={}, start={}", firstId, start);
                break;
            }
            List<Long> productIds = new ArrayList<>();
            productsNode.forEach(node -> {
                long id = node.path("id").asLong(0L);
                if (id > 0) {
                    productIds.add(id);
                    remoteIds.add(id);
                }
            });
            Map<Long, RemotePrice> prices = fetchPrices(context, productIds);
            for (JsonNode productNode : productsNode) {
                applyRemoteProduct(context, productNode, prices.get(productNode.path("id").asLong()));
            }
            total = result.path("total").asInt(total);
            JsonNode nextNode = result.get("next");
            if (nextNode != null && nextNode.canConvertToInt()) {
                int next = nextNode.asInt();
                if (next <= start) {
                    fullScanCompleted = false;
                    log.warn("Bitrix24 inbound sync returned non-increasing next offset: start={}, next={}", start, next);
                    break;
                }
                start = next;
                continue;
            }
            if (total > 0 && remoteIds.size() >= total) {
                break;
            }
            if (productsNode.size() < pageSize) {
                break;
            }
            start += productsNode.size();
        }
        if (fullScanCompleted) {
            deactivateProductsMissingInBitrix(remoteIds);
            log.info("Bitrix24 inbound sync completed, {} remote products scanned", remoteIds.size());
        } else {
            log.warn("Bitrix24 inbound sync finished without full catalog scan, skipped local deactivation. scanned={}", remoteIds.size());
        }
    }

    private PortalContext ensurePortalContext() {
        PortalContext cached = portalContext;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (portalContext != null) {
                return portalContext;
            }
            Integer catalogId = appProperties.getBitrix().getCatalogId();
            Integer iblockId = null;
            JsonNode catalogsResult = call("catalog.catalog.list", Map.of(
                    "select", List.of("id", "name", "iblockId"),
                    "start", 0
            ));
            JsonNode catalogs = catalogsResult.path("catalogs");
            if (!catalogs.isArray() || catalogs.isEmpty()) {
                throw new IllegalStateException("Не удалось получить торговый каталог Bitrix24");
            }
            JsonNode selectedCatalog = catalogs.get(0);
            if (catalogId != null) {
                for (JsonNode node : catalogs) {
                    int remoteCatalogId = node.path("id").asInt();
                    int remoteIblockId = node.path("iblockId").asInt();
                    if (remoteCatalogId == catalogId || remoteIblockId == catalogId) {
                        selectedCatalog = node;
                        break;
                    }
                }
            }
            catalogId = selectedCatalog.path("id").asInt();
            iblockId = selectedCatalog.path("iblockId").asInt();
            if (iblockId <= 0) {
                throw new IllegalStateException("Не удалось определить iblockId торгового каталога Bitrix24");
            }

            Integer basePriceTypeId = appProperties.getBitrix().getBasePriceTypeId();
            if (basePriceTypeId == null) {
                JsonNode result = call("catalog.priceType.list", Map.of("start", 0));
                JsonNode priceTypes = result.path("priceTypes");
                if (!priceTypes.isArray() || priceTypes.isEmpty()) {
                    throw new IllegalStateException("Не удалось получить тип цены Bitrix24");
                }
                basePriceTypeId = priceTypes.get(0).path("id").asInt();
                for (JsonNode node : priceTypes) {
                    String xmlId = node.path("xmlId").asText("");
                    if ("BASE".equalsIgnoreCase(xmlId)) {
                        basePriceTypeId = node.path("id").asInt();
                        break;
                    }
                }
            }

            Map<String, Integer> propertyIds = ensureProperties(iblockId);
            Map<String, Integer> measureIdsByUnit = loadMeasureIdsByUnit();
            Map<Integer, String> unitNamesByMeasureId = invertMeasureMap(measureIdsByUnit);
            portalContext = new PortalContext(catalogId, iblockId, basePriceTypeId, propertyIds, measureIdsByUnit, unitNamesByMeasureId);
            return portalContext;
        }
    }

    private Map<String, Integer> ensureProperties(Integer catalogId) {
        JsonNode result = call("catalog.productProperty.list", Map.of(
                "select", List.of("id", "code", "name", "propertyType"),
                "filter", Map.of("iblockId", catalogId),
                "start", 0
        ));
        Map<String, Integer> ids = new LinkedHashMap<>();
        JsonNode properties = result.path("productProperties");
        if (properties.isArray()) {
            for (JsonNode node : properties) {
                String code = node.path("code").asText("");
                if (!code.isBlank()) {
                    ids.put(code, node.path("id").asInt());
                }
            }
        }
        for (PropertyDefinition definition : PROPERTY_DEFINITIONS) {
            if (ids.containsKey(definition.code())) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("iblockId", catalogId);
            fields.put("name", definition.name());
            fields.put("code", definition.code());
            fields.put("propertyType", definition.propertyType());
            fields.put("multiple", "N");
            fields.put("isRequired", "N");
            fields.put("active", "Y");
            fields.put("sort", definition.sort());
            fields.put("rowCount", definition.rowCount());
            fields.put("searchable", "N");
            fields.put("filtrable", "N");
            JsonNode addResult = call("catalog.productProperty.add", Map.of("fields", fields));
            ids.put(definition.code(), Math.toIntExact(extractId(addResult, "productProperty")));
            log.info("Created Bitrix24 catalog property {}", definition.code());
        }
        return ids;
    }

    private Map<String, Integer> loadMeasureIdsByUnit() {
        JsonNode result = call("catalog.measure.list", Map.of("start", 0));
        Map<String, Integer> map = new LinkedHashMap<>();
        JsonNode measures = result.path("measures");
        if (!measures.isArray()) {
            return map;
        }
        for (JsonNode node : measures) {
            int id = node.path("id").asInt();
            String symbol = node.path("symbolIntl").asText("");
            if (!symbol.isBlank()) {
                map.put(TextUtils.normalizeToken(symbol), id);
            }
            int code = node.path("code").asInt(0);
            if (code == 112) {
                map.put("л", id);
                map.put("литр", id);
            } else if (code == 166) {
                map.put("кг", id);
                map.put("килограмм", id);
            } else if (code == 796) {
                map.put("шт", id);
            }
        }
        return map;
    }

    private Map<Integer, String> invertMeasureMap(Map<String, Integer> measureIdsByUnit) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : measureIdsByUnit.entrySet()) {
            result.putIfAbsent(entry.getValue(), entry.getKey());
        }
        return result;
    }

    private Long resolveRemoteProductId(PortalContext context, CatalogProduct product) {
        if (product.getBitrixProductId() != null) {
            return product.getBitrixProductId();
        }
        String externalId = firstNonBlank(product.getExternalId(), product.getSku());
        if (externalId != null) {
            JsonNode result = call("catalog.product.list", Map.of(
                    "select", List.of("id", "iblockId", "xmlId", "name"),
                    "filter", Map.of(
                            "iblockId", context.iblockId(),
                            "xmlId", externalId
                    ),
                    "start", 0
            ));
            JsonNode products = result.path("products");
            if (products.isArray() && !products.isEmpty()) {
                Long id = products.get(0).path("id").asLong();
                if (id != null && id > 0) {
                    return id;
                }
            }
        }
        if (product.getName() != null && !product.getName().isBlank()) {
            JsonNode result = call("catalog.product.list", Map.of(
                    "select", List.of("id", "iblockId", "name"),
                    "filter", Map.of(
                            "iblockId", context.iblockId(),
                            "name", product.getName()
                    ),
                    "start", 0
            ));
            JsonNode products = result.path("products");
            if (products.isArray() && products.size() == 1) {
                Long id = products.get(0).path("id").asLong();
                if (id != null && id > 0) {
                    return id;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildProductFields(PortalContext context, CatalogProduct product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("iblockId", context.iblockId());
        fields.put("name", product.getName());
        fields.put("active", product.isActive() ? "Y" : "N");
        fields.put("xmlId", firstNonBlank(product.getExternalId(), "app-" + product.getId()));
        fields.put("detailText", firstNonBlank(product.getDescription(), ""));
        fields.put("detailTextType", "text");
        fields.put("previewText", buildPreviewText(product));
        fields.put("previewTextType", "text");
        fields.put("canBuyZero", "Y");
        fields.put("quantityTrace", "N");
        if (product.getStockQuantity() != null) {
            fields.put("quantity", product.getStockQuantity());
        }

        Integer measureId = resolveMeasureId(context, product.getUnitName());
        if (measureId != null) {
            fields.put("measure", measureId);
        }

        putPropertyValue(fields, context, PROPERTY_EXTERNAL_ID, firstNonBlank(product.getExternalId(), "app-" + product.getId()));
        putPropertyValue(fields, context, PROPERTY_SOURCE_FILE, product.getSourceFile());
        putPropertyValue(fields, context, PROPERTY_SKU, product.getSku());
        putPropertyValue(fields, context, PROPERTY_BRAND, product.getBrand());
        putPropertyValue(fields, context, PROPERTY_CATEGORY, product.getCategory());
        putPropertyValue(fields, context, PROPERTY_SUBCATEGORY, product.getSubcategory());
        putPropertyValue(fields, context, PROPERTY_ITEM_TYPE, product.getItemType());
        putPropertyValue(fields, context, PROPERTY_UNIT_NAME, product.getUnitName());
        putPropertyValue(fields, context, PROPERTY_CULTURES, joinList(readStringList(product.getCulturesJson())));
        putPropertyValue(fields, context, PROPERTY_PURPOSES, joinList(readStringList(product.getPurposesJson())));
        putPropertyValue(fields, context, PROPERTY_TAGS, joinList(readStringList(product.getTagsJson())));
        putPropertyValue(fields, context, PROPERTY_PACKAGE_TYPE, product.getPackageType());
        putPropertyValue(fields, context, PROPERTY_PACKAGE_DESCRIPTION, product.getPackageDescription());
        putPropertyValue(fields, context, PROPERTY_MIN_ORDER_QUANTITY, product.getMinOrderQuantity());
        putPropertyValue(fields, context, PROPERTY_ORDER_STEP, product.getOrderStep());
        putPropertyValue(fields, context, PROPERTY_FILTER_MAP, normalizeFilterMapJson(product.getFilterMapJson()));
        return fields;
    }

    private String buildPreviewText(CatalogProduct product) {
        List<String> parts = new ArrayList<>();
        if (product.getCategory() != null && !product.getCategory().isBlank()) {
            parts.add(product.getCategory());
        }
        if (product.getSubcategory() != null && !product.getSubcategory().isBlank()) {
            parts.add(product.getSubcategory());
        }
        List<String> cultures = readStringList(product.getCulturesJson());
        if (!cultures.isEmpty()) {
            parts.add("Культуры: " + String.join(", ", cultures.stream().limit(4).toList()));
        }
        return String.join(" • ", parts);
    }

    private Integer resolveMeasureId(PortalContext context, String unitName) {
        if (unitName == null || unitName.isBlank()) {
            return context.measureIdsByUnit().get("шт");
        }
        String normalized = TextUtils.normalizeToken(unitName);
        Integer exact = context.measureIdsByUnit().get(normalized);
        if (exact != null) {
            return exact;
        }
        if (normalized.contains("лит")) {
            return context.measureIdsByUnit().get("л");
        }
        if (normalized.contains("кил")) {
            return context.measureIdsByUnit().get("кг");
        }
        if (normalized.contains("шт") || normalized.contains("пе") || normalized.contains("п е")) {
            return context.measureIdsByUnit().get("шт");
        }
        return null;
    }

    private void putPropertyValue(Map<String, Object> fields, PortalContext context, String code, Object value) {
        Integer propertyId = context.propertyIds().get(code);
        if (propertyId == null) {
            return;
        }
        String fieldName = "property" + propertyId;
        if (value == null) {
            fields.put(fieldName, "");
            return;
        }
        if (value instanceof BigDecimal decimal) {
            fields.put(fieldName, decimal);
            return;
        }
        String stringValue = Objects.toString(value, "").trim();
        fields.put(fieldName, stringValue);
    }

    private List<String> buildProductSelect(PortalContext context) {
        List<String> select = new ArrayList<>(List.of(
                "id",
                "iblockId",
                "name",
                "active",
                "xmlId",
                "code",
                "detailText",
                "previewText",
                "quantity",
                "measure"
        ));
        context.propertyIds().values().forEach(id -> select.add("property" + id));
        return select;
    }

    private Map<Long, RemotePrice> fetchPrices(PortalContext context, Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        JsonNode result = call("catalog.price.list", Map.of(
                "select", List.of("id", "productId", "price", "currency", "catalogGroupId"),
                "filter", Map.of(
                        "catalogGroupId", context.basePriceTypeId(),
                        "@productId", productIds
                ),
                "start", 0
        ));
        Map<Long, RemotePrice> prices = new LinkedHashMap<>();
        JsonNode pricesNode = result.path("prices");
        if (!pricesNode.isArray()) {
            return prices;
        }
        for (JsonNode node : pricesNode) {
            long productId = node.path("productId").asLong(0L);
            if (productId <= 0) {
                continue;
            }
            prices.put(productId, new RemotePrice(
                    node.path("id").asLong(0L),
                    parseDecimal(node.path("price").asText(null)),
                    node.path("currency").asText(appProperties.getBitrix().getCurrencyId())
            ));
        }
        return prices;
    }

    private Long upsertProductPrice(PortalContext context, Long productId, BigDecimal price, Long knownPriceId) {
        if (productId == null || price == null) {
            return knownPriceId;
        }
        Long priceId = knownPriceId;
        if (priceId == null) {
            RemotePrice existing = fetchPrices(context, List.of(productId)).get(productId);
            priceId = existing == null || existing.id() <= 0 ? null : existing.id();
        }
        if (priceId == null) {
            JsonNode result = call("catalog.price.add", Map.of(
                    "fields", Map.of(
                            "productId", productId,
                            "catalogGroupId", context.basePriceTypeId(),
                            "price", price,
                            "currency", appProperties.getBitrix().getCurrencyId()
                    )
            ));
            return extractId(result, "price");
        }
        call("catalog.price.update", Map.of(
                "id", priceId,
                "fields", Map.of(
                        "productId", productId,
                        "catalogGroupId", context.basePriceTypeId(),
                        "price", price,
                        "currency", appProperties.getBitrix().getCurrencyId()
                )
        ));
        return priceId;
    }

    private void applyRemoteProduct(PortalContext context, JsonNode productNode, RemotePrice remotePrice) {
        Long remoteId = productNode.path("id").asLong(0L);
        if (remoteId == null || remoteId <= 0) {
            return;
        }

        String externalId = firstNonBlank(
                propertyValue(productNode, context, PROPERTY_EXTERNAL_ID),
                productNode.path("xmlId").asText(""),
                "bitrix-" + remoteId
        );
        CatalogProduct localByBitrix = findFirstByBitrixProductId(remoteId);
        CatalogProduct localByExternal = externalId == null || externalId.isBlank()
                ? null
                : findFirstByExternalId(externalId);

        CatalogProduct local = localByBitrix != null ? localByBitrix : localByExternal;
        if (localByBitrix != null && localByExternal != null && !Objects.equals(localByBitrix.getId(), localByExternal.getId())) {
            log.warn("Resolved Bitrix/local collision for bitrixProductId={}, externalId={}, using externalId record id={}",
                    remoteId,
                    externalId,
                    localByExternal.getId());
            localByBitrix.setBitrixProductId(null);
            localByBitrix.setBitrixPriceId(null);
            localByBitrix.setBitrixSyncHash(null);
            localByBitrix.setBitrixSyncedAt(Instant.now());
            catalogProductRepository.save(localByBitrix);
            local = localByExternal;
        }
        if (local == null) {
            String normalizedName = TextUtils.normalizeToken(productNode.path("name").asText(""));
            if (!normalizedName.isBlank()) {
                local = catalogProductRepository.findAll().stream()
                        .filter(item -> item.getName() != null)
                        .filter(item -> TextUtils.normalizeToken(item.getName()).equals(normalizedName))
                        .findFirst()
                        .orElse(null);
            }
        }
        if (local == null) {
            local = new CatalogProduct();
        }

        local.setBitrixProductId(remoteId);
        local.setBitrixPriceId(remotePrice == null ? null : remotePrice.id());
        local.setExternalId(externalId);
        local.setSourceFile(blankToNull(propertyValue(productNode, context, PROPERTY_SOURCE_FILE)));
        local.setSku(blankToNull(propertyValue(productNode, context, PROPERTY_SKU)));
        local.setName(firstNonBlank(productNode.path("name").asText(""), local.getName(), "Товар"));
        local.setDescription(firstNonBlank(
                productNode.path("detailText").asText(""),
                productNode.path("previewText").asText(""),
                local.getDescription(),
                ""
        ));
        local.setBrand(blankToNull(propertyValue(productNode, context, PROPERTY_BRAND)));
        local.setCategory(firstNonBlank(
                blankToNull(propertyValue(productNode, context, PROPERTY_CATEGORY)),
                local.getCategory(),
                inferCategoryFromName(local.getName()),
                "Прочее"
        ));
        local.setSubcategory(blankToNull(propertyValue(productNode, context, PROPERTY_SUBCATEGORY)));
        local.setItemType(blankToNull(propertyValue(productNode, context, PROPERTY_ITEM_TYPE)));
        local.setUnitName(firstNonBlank(
                blankToNull(propertyValue(productNode, context, PROPERTY_UNIT_NAME)),
                context.unitNamesByMeasureId().get(productNode.path("measure").asInt()),
                local.getUnitName(),
                "шт"
        ));
        local.setPackageType(blankToNull(propertyValue(productNode, context, PROPERTY_PACKAGE_TYPE)));
        local.setPackageDescription(blankToNull(propertyValue(productNode, context, PROPERTY_PACKAGE_DESCRIPTION)));
        local.setPrice(remotePrice == null ? null : remotePrice.price());
        local.setStockQuantity(parseDecimal(productNode.path("quantity").asText(null)));
        local.setMinOrderQuantity(parseDecimal(propertyValue(productNode, context, PROPERTY_MIN_ORDER_QUANTITY)));
        local.setOrderStep(parseDecimal(propertyValue(productNode, context, PROPERTY_ORDER_STEP)));
        local.setActive("Y".equalsIgnoreCase(productNode.path("active").asText("Y")));

        List<String> cultures = splitMultiValue(propertyValue(productNode, context, PROPERTY_CULTURES));
        List<String> purposes = splitMultiValue(propertyValue(productNode, context, PROPERTY_PURPOSES));
        List<String> tags = splitMultiValue(propertyValue(productNode, context, PROPERTY_TAGS));
        Map<String, Object> filterMap = parseFilterMap(propertyValue(productNode, context, PROPERTY_FILTER_MAP), tags);

        local.setCulturesJson(jsonHelper.writeValue(cultures));
        local.setPurposesJson(jsonHelper.writeValue(purposes));
        local.setTagsJson(jsonHelper.writeValue(tags));
        local.setCulturesIndex(TextUtils.toIndex(cultures));
        local.setPurposesIndex(TextUtils.toIndex(purposes));
        local.setTagsIndex(TextUtils.toIndex(tags));
        local.setFilterMapJson(jsonHelper.writeValue(filterMap));
        Map<String, Object> rawData = new LinkedHashMap<>();
        rawData.put("syncedFrom", "bitrix24");
        rawData.put("bitrixProductId", remoteId);
        rawData.put("bitrixPriceId", remotePrice == null ? null : remotePrice.id());
        local.setRawDataJson(jsonHelper.writeValue(rawData));
        local.setBitrixSyncHash(computeSyncHash(local));
        local.setBitrixSyncedAt(Instant.now());
        catalogProductRepository.save(local);
    }

    private void deactivateProductsMissingInBitrix(Set<Long> remoteIds) {
        for (CatalogProduct product : catalogProductRepository.findAllByBitrixProductIdIsNotNull()) {
            if (product.getBitrixProductId() != null && remoteIds.contains(product.getBitrixProductId())) {
                continue;
            }
            product.setActive(false);
            product.setBitrixProductId(null);
            product.setBitrixPriceId(null);
            product.setBitrixSyncHash(null);
            product.setBitrixSyncedAt(Instant.now());
            catalogProductRepository.save(product);
        }
    }

    private List<Map<String, Object>> buildLeadRows(CatalogOrder order) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CatalogOrderItem item : order.getItems()) {
            Map<String, Object> row = new LinkedHashMap<>();
            CatalogProduct product = item.getProductId() == null ? null : catalogProductRepository.findById(item.getProductId()).orElse(null);
            if (product != null && product.getBitrixProductId() != null) {
                row.put("PRODUCT_ID", product.getBitrixProductId());
            } else {
                row.put("PRODUCT_NAME", item.getProductName());
            }
            row.put("PRICE", item.getUnitPrice());
            row.put("QUANTITY", item.getQuantity());
            if (product != null && product.getUnitName() != null && !product.getUnitName().isBlank()) {
                row.put("MEASURE_NAME", product.getUnitName());
            }
            rows.add(row);
        }
        return rows;
    }

    private String buildLeadComment(CatalogOrder order) {
        StringBuilder builder = new StringBuilder();
        builder.append("Заказ ").append(order.getPublicCode()).append("\n");
        if (order.getCustomerCompany() != null && !order.getCustomerCompany().isBlank()) {
            builder.append("Компания: ").append(order.getCustomerCompany()).append("\n");
        }
        if (order.getCustomerFarmName() != null && !order.getCustomerFarmName().isBlank()) {
            builder.append("Хозяйство: ").append(order.getCustomerFarmName()).append("\n");
        }
        if (order.getCustomerInn() != null && !order.getCustomerInn().isBlank()) {
            builder.append("ИНН: ").append(order.getCustomerInn()).append("\n");
        }
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            builder.append("Телефон: ").append(order.getCustomerPhone()).append("\n");
        }
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            builder.append("Email: ").append(order.getCustomerEmail()).append("\n");
        }
        if (order.getDeliveryAddress() != null && !order.getDeliveryAddress().isBlank()) {
            builder.append("Адрес: ").append(order.getDeliveryAddress()).append("\n");
        }
        if (order.getComment() != null && !order.getComment().isBlank()) {
            builder.append("Комментарий: ").append(order.getComment()).append("\n");
        }
        List<Map<String, Object>> attachments = jsonHelper.readValue(order.getAttachmentsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of());
        if (!attachments.isEmpty()) {
            builder.append("Реквизиты:\n");
            attachments.forEach(attachment -> {
                String name = String.valueOf(attachment.getOrDefault("originalName", attachment.getOrDefault("storedName", "Файл")));
                String url = String.valueOf(attachment.getOrDefault("downloadUrl", ""));
                if (!url.isBlank()) {
                    builder.append("• ").append(name).append(": ").append(url).append("\n");
                }
            });
        }
        builder.append("\nСостав заказа:\n");
        order.getItems().forEach(item -> builder.append("• ")
                .append(item.getProductName())
                .append(" × ")
                .append(item.getQuantity().stripTrailingZeros().toPlainString())
                .append(" = ")
                .append(TextUtils.formatPrice(item.getUnitPrice().multiply(item.getQuantity())))
                .append("\n"));
        return builder.toString().trim();
    }

    private JsonNode call(String method, Object payload) {
        try {
            String url = normalizeWebhookBaseUrl(appProperties.getBitrix().getWebhookBaseUrl()) + "/" + method + ".json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonHelper.writeValue(payload == null ? Map.of() : payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Bitrix24 HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = jsonHelper.readTree(response.body());
            if (root.hasNonNull("error")) {
                String description = root.path("error_description").asText(root.path("error").asText("Bitrix24 error"));
                throw new IllegalStateException(description);
            }
            return root.has("result") ? root.get("result") : root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bitrix24 request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Bitrix24 request failed: " + e.getMessage(), e);
        }
    }

    private String normalizeWebhookBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            throw new IllegalStateException("Bitrix24 webhook URL is empty");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(".json")) {
            value = value.substring(0, value.lastIndexOf('/'));
        }
        int restIndex = value.indexOf("/rest/");
        if (restIndex < 0) {
            return value;
        }
        String prefix = value.substring(0, restIndex + 6);
        String suffix = value.substring(restIndex + 6);
        String[] segments = Arrays.stream(suffix.split("/"))
                .filter(segment -> !segment.isBlank())
                .toArray(String[]::new);
        if (segments.length <= 2) {
            return value;
        }
        return prefix + segments[0] + "/" + segments[1];
    }

    private String computeSyncHash(CatalogProduct product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalId", product.getExternalId());
        data.put("sku", product.getSku());
        data.put("name", product.getName());
        data.put("description", product.getDescription());
        data.put("brand", product.getBrand());
        data.put("category", product.getCategory());
        data.put("subcategory", product.getSubcategory());
        data.put("itemType", product.getItemType());
        data.put("unitName", product.getUnitName());
        data.put("packageType", product.getPackageType());
        data.put("packageDescription", product.getPackageDescription());
        data.put("price", product.getPrice());
        data.put("stockQuantity", product.getStockQuantity());
        data.put("minOrderQuantity", product.getMinOrderQuantity());
        data.put("orderStep", product.getOrderStep());
        data.put("active", product.isActive());
        data.put("cultures", readStringList(product.getCulturesJson()));
        data.put("purposes", readStringList(product.getPurposesJson()));
        data.put("tags", readStringList(product.getTagsJson()));
        data.put("filterMap", jsonHelper.readMap(product.getFilterMapJson()));
        String source = jsonHelper.writeValue(data);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    private String propertyValue(JsonNode productNode, PortalContext context, String code) {
        Integer propertyId = context.propertyIds().get(code);
        if (propertyId == null) {
            return "";
        }
        JsonNode node = productNode.get("property" + propertyId);
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(item.isObject() ? item.path("value").asText("") : item.asText("")));
            return joinList(values);
        }
        if (node.isObject()) {
            return node.path("value").asText("");
        }
        return node.asText("");
    }

    private String normalizeFilterMapJson(String filterMapJson) {
        Map<String, Object> filterMap = new LinkedHashMap<>(jsonHelper.readMap(filterMapJson));
        if (!filterMap.containsKey("season")) {
            List<String> season = new ArrayList<>();
            Object rawSeason = filterMap.get("season");
            if (rawSeason instanceof Collection<?> collection) {
                collection.forEach(item -> season.add(Objects.toString(item, "")));
            }
            if (!season.isEmpty()) {
                filterMap.put("season", season);
            }
        }
        return jsonHelper.writeValue(filterMap);
    }

    private Map<String, Object> parseFilterMap(String raw, List<String> tags) {
        Map<String, Object> filterMap = new LinkedHashMap<>(jsonHelper.readMap(raw));
        List<String> seasons = new ArrayList<>();
        for (String tag : tags) {
            String normalized = TextUtils.normalizeToken(tag);
            if (normalized.contains("озим")) {
                seasons.add("Озимые");
            }
            if (normalized.contains("яров")) {
                seasons.add("Яровые");
            }
        }
        if (!seasons.isEmpty()) {
            filterMap.put("season", seasons.stream().distinct().toList());
        }
        return filterMap;
    }

    private List<String> readStringList(String json) {
        return jsonHelper.readStringList(json).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> splitMultiValue(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,;\\n]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private String joinList(List<String> values) {
        return values == null ? "" : values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(',', '.').replaceAll("[^\\d.\\-]+", "");
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalized).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long extractId(JsonNode result, String... objectKeys) {
        for (String objectKey : objectKeys) {
            JsonNode objectNode = result.path(objectKey);
            if (objectNode.has("id")) {
                return objectNode.path("id").asLong();
            }
        }
        return extractSimpleResultId(result);
    }

    private Long extractSimpleResultId(JsonNode result) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return null;
        }
        if (result.isIntegralNumber()) {
            return result.asLong();
        }
        if (result.has("id")) {
            return result.path("id").asLong();
        }
        return null;
    }

    private boolean looksLikeMissingRemote(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("not found")
                || message.contains("не найден")
                || message.contains("could not find")
                || message.contains("invalid product");
    }

    private String inferCategoryFromName(String name) {
        String normalized = TextUtils.normalizeToken(name);
        if (normalized.contains("сем")) {
            return "Семена";
        }
        if (normalized.contains("гербиц") || normalized.contains("фунгиц") || normalized.contains("инсектиц") || normalized.contains("пестиц")) {
            return "Пестициды";
        }
        if (normalized.contains("удобр") || normalized.contains("агропит") || normalized.contains("npk") || normalized.contains("азот") || normalized.contains("бор")) {
            return "Агропитание";
        }
        return "Прочее";
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

    private CatalogProduct findFirstByExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        List<CatalogProduct> matches = catalogProductRepository.findAllByExternalIdOrderByUpdatedAtDesc(externalId);
        if (matches.size() > 1) {
            log.warn("Detected duplicate local products by externalId={}, using the latest record", externalId);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private CatalogProduct findFirstByBitrixProductId(Long bitrixProductId) {
        if (bitrixProductId == null || bitrixProductId <= 0) {
            return null;
        }
        List<CatalogProduct> matches = catalogProductRepository.findAllByBitrixProductIdOrderByUpdatedAtDesc(bitrixProductId);
        if (matches.size() > 1) {
            log.warn("Detected duplicate local products by bitrixProductId={}, using the latest record", bitrixProductId);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private record PropertyDefinition(
            String code,
            String name,
            String propertyType,
            int sort,
            int rowCount
    ) {
    }

    private record PortalContext(
            int catalogId,
            int iblockId,
            int basePriceTypeId,
            Map<String, Integer> propertyIds,
            Map<String, Integer> measureIdsByUnit,
            Map<Integer, String> unitNamesByMeasureId
    ) {
    }

    private record RemotePrice(
            long id,
            BigDecimal price,
            String currency
    ) {
    }
}
