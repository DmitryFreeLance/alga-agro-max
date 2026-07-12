package ru.algaagro.maxapp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "catalog_products")
public class CatalogProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String externalId;

    private String sourceFile;
    private String sku;

    @Column(nullable = false)
    private String name;

    @Lob
    private String description;

    private String brand;
    private String category;
    private String subcategory;
    private String itemType;
    private String unitName;
    private String packageType;
    private String packageDescription;
    private String agroxxiUrl;
    private Long bitrixProductId;
    private Long bitrixPriceId;
    private String bitrixSyncHash;

    @Column
    private Instant bitrixSyncedAt;

    @Column(precision = 14, scale = 2)
    private BigDecimal price;

    @Column(precision = 14, scale = 3)
    private BigDecimal stockQuantity;

    @Column(precision = 14, scale = 3)
    private BigDecimal minOrderQuantity;

    @Column(precision = 14, scale = 3)
    private BigDecimal orderStep;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private String culturesIndex = "";

    @Column(nullable = false)
    private String purposesIndex = "";

    @Column(nullable = false)
    private String tagsIndex = "";

    @Lob
    private String culturesJson = "[]";

    @Lob
    private String purposesJson = "[]";

    @Lob
    private String tagsJson = "[]";

    @Lob
    private String filterMapJson = "{}";

    @Lob
    private String rawDataJson = "{}";

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public void setSubcategory(String subcategory) {
        this.subcategory = subcategory;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getPackageType() {
        return packageType;
    }

    public void setPackageType(String packageType) {
        this.packageType = packageType;
    }

    public String getPackageDescription() {
        return packageDescription;
    }

    public void setPackageDescription(String packageDescription) {
        this.packageDescription = packageDescription;
    }

    public String getAgroxxiUrl() {
        return agroxxiUrl;
    }

    public void setAgroxxiUrl(String agroxxiUrl) {
        this.agroxxiUrl = agroxxiUrl;
    }

    public Long getBitrixProductId() {
        return bitrixProductId;
    }

    public void setBitrixProductId(Long bitrixProductId) {
        this.bitrixProductId = bitrixProductId;
    }

    public Long getBitrixPriceId() {
        return bitrixPriceId;
    }

    public void setBitrixPriceId(Long bitrixPriceId) {
        this.bitrixPriceId = bitrixPriceId;
    }

    public String getBitrixSyncHash() {
        return bitrixSyncHash;
    }

    public void setBitrixSyncHash(String bitrixSyncHash) {
        this.bitrixSyncHash = bitrixSyncHash;
    }

    public Instant getBitrixSyncedAt() {
        return bitrixSyncedAt;
    }

    public void setBitrixSyncedAt(Instant bitrixSyncedAt) {
        this.bitrixSyncedAt = bitrixSyncedAt;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(BigDecimal stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public BigDecimal getMinOrderQuantity() {
        return minOrderQuantity;
    }

    public void setMinOrderQuantity(BigDecimal minOrderQuantity) {
        this.minOrderQuantity = minOrderQuantity;
    }

    public BigDecimal getOrderStep() {
        return orderStep;
    }

    public void setOrderStep(BigDecimal orderStep) {
        this.orderStep = orderStep;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCulturesIndex() {
        return culturesIndex;
    }

    public void setCulturesIndex(String culturesIndex) {
        this.culturesIndex = culturesIndex;
    }

    public String getPurposesIndex() {
        return purposesIndex;
    }

    public void setPurposesIndex(String purposesIndex) {
        this.purposesIndex = purposesIndex;
    }

    public String getTagsIndex() {
        return tagsIndex;
    }

    public void setTagsIndex(String tagsIndex) {
        this.tagsIndex = tagsIndex;
    }

    public String getCulturesJson() {
        return culturesJson;
    }

    public void setCulturesJson(String culturesJson) {
        this.culturesJson = culturesJson;
    }

    public String getPurposesJson() {
        return purposesJson;
    }

    public void setPurposesJson(String purposesJson) {
        this.purposesJson = purposesJson;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getFilterMapJson() {
        return filterMapJson;
    }

    public void setFilterMapJson(String filterMapJson) {
        this.filterMapJson = filterMapJson;
    }

    public String getRawDataJson() {
        return rawDataJson;
    }

    public void setRawDataJson(String rawDataJson) {
        this.rawDataJson = rawDataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
