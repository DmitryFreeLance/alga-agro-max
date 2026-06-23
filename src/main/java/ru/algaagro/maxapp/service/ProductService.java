package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class ProductService {

    private final CatalogProductRepository catalogProductRepository;
    private final JsonHelper jsonHelper;

    public ProductService(CatalogProductRepository catalogProductRepository, JsonHelper jsonHelper) {
        this.catalogProductRepository = catalogProductRepository;
        this.jsonHelper = jsonHelper;
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

    public List<CatalogProduct> findFiltered(String culture, String category, String search, String tag, String sort) {
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
                .filter(product -> normalizedSearch.isBlank() || TextUtils.normalizeToken(buildSearchText(product)).contains(normalizedSearch))
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    public Map<String, Object> buildFilterSummary() {
        Set<String> cultures = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        Set<String> tags = new LinkedHashSet<>();
        for (CatalogProduct product : getActiveProducts()) {
            cultures.addAll(getStringList(product.getCulturesJson()));
            tags.addAll(getStringList(product.getTagsJson()));
            if (product.getCategory() != null && !product.getCategory().isBlank()) {
                categories.add(product.getCategory());
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cultures", cultures);
        response.put("categories", categories);
        response.put("tags", tags);
        response.put("total", getActiveProducts().size());
        return response;
    }

    @Transactional
    public CatalogProduct upsertProduct(ImportedProduct importedProduct) {
        CatalogProduct product = importedProduct.externalId() == null
                ? null
                : catalogProductRepository.findByExternalId(importedProduct.externalId()).orElse(null);
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
        product.setPrice(importedProduct.price());
        product.setStockQuantity(importedProduct.stockQuantity());
        product.setCulturesJson(jsonHelper.writeValue(importedProduct.cultures()));
        product.setPurposesJson(jsonHelper.writeValue(importedProduct.purposes()));
        product.setTagsJson(jsonHelper.writeValue(importedProduct.tags()));
        product.setCulturesIndex(TextUtils.toIndex(importedProduct.cultures()));
        product.setPurposesIndex(TextUtils.toIndex(importedProduct.purposes()));
        product.setTagsIndex(TextUtils.toIndex(importedProduct.tags()));
        product.setFilterMapJson(jsonHelper.writeValue(importedProduct.filterMap()));
        product.setRawDataJson(jsonHelper.writeValue(importedProduct.rawData()));
        product.setActive(true);
        return catalogProductRepository.save(product);
    }

    public List<String> getStringList(String json) {
        return jsonHelper.readValue(json, new TypeReference<>() { }, List.of());
    }

    public Map<String, Object> toMiniAppDto(CatalogProduct product) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", product.getId());
        dto.put("name", product.getName());
        dto.put("description", product.getDescription());
        dto.put("brand", product.getBrand());
        dto.put("category", product.getCategory());
        dto.put("subcategory", product.getSubcategory());
        dto.put("itemType", product.getItemType());
        dto.put("unitName", product.getUnitName());
        dto.put("price", product.getPrice());
        dto.put("stockQuantity", product.getStockQuantity());
        dto.put("cultures", getStringList(product.getCulturesJson()));
        dto.put("purposes", getStringList(product.getPurposesJson()));
        dto.put("tags", getStringList(product.getTagsJson()));
        dto.put("filterMap", jsonHelper.readMap(product.getFilterMapJson()));
        dto.put("imageStyle", buildImageStyle(product));
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
        return catalogProductRepository.save(product);
    }

    @Transactional
    public CatalogProduct updateProduct(Long id, AdminProductPayload payload) {
        CatalogProduct product = catalogProductRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        applyPayload(product, payload);
        return catalogProductRepository.save(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        CatalogProduct product = catalogProductRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Товар не найден"));
        catalogProductRepository.delete(product);
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
        product.setPrice(payload.price());
        product.setStockQuantity(payload.stockQuantity());
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

    private Map<String, String> buildImageStyle(CatalogProduct product) {
        int hue = Math.abs(Objects.hash(product.getName())) % 360;
        Map<String, String> style = new LinkedHashMap<>();
        style.put("primary", "hsl(" + hue + " 42% 30%)");
        style.put("secondary", "hsl(" + ((hue + 40) % 360) + " 48% 58%)");
        style.put("accent", "hsl(" + ((hue + 85) % 360) + " 52% 76%)");
        return style;
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
            List<String> cultures,
            List<String> purposes,
            List<String> tags,
            Map<String, Object> filterMap,
            Map<String, Object> rawData,
            Boolean active
    ) {
    }
}
