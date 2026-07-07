package ru.algaagro.maxapp.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.model.Manufacturer;
import ru.algaagro.maxapp.repository.ManufacturerRepository;

@Service
public class ManufacturerService {

    private final ManufacturerRepository manufacturerRepository;

    public ManufacturerService(ManufacturerRepository manufacturerRepository) {
        this.manufacturerRepository = manufacturerRepository;
    }

    public List<Map<String, Object>> listManufacturers(List<CatalogProduct> products) {
        Map<String, Long> countsByName = products.stream()
                .map(CatalogProduct::getBrand)
                .filter(brand -> brand != null && !brand.isBlank())
                .collect(Collectors.groupingBy(this::normalizeKey, LinkedHashMap::new, Collectors.counting()));
        Map<String, String> displayNames = products.stream()
                .map(CatalogProduct::getBrand)
                .filter(brand -> brand != null && !brand.isBlank())
                .collect(Collectors.toMap(this::normalizeKey, String::trim, (left, right) -> left, LinkedHashMap::new));

        manufacturerRepository.findAll().forEach(manufacturer -> {
            String key = normalizeKey(manufacturer.getName());
            displayNames.putIfAbsent(key, manufacturer.getName());
            countsByName.putIfAbsent(key, 0L);
        });

        return displayNames.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> {
                    Manufacturer manufacturer = manufacturerRepository.findByNameIgnoreCase(entry.getValue()).orElse(null);
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id", manufacturer == null ? null : manufacturer.getId());
                    dto.put("name", entry.getValue());
                    dto.put("productsCount", countsByName.getOrDefault(entry.getKey(), 0L));
                    return dto;
                })
                .toList();
    }

    @Transactional
    public Manufacturer createManufacturer(String name) {
        String normalized = sanitizeName(name);
        manufacturerRepository.findByNameIgnoreCase(normalized).ifPresent(existing -> {
            throw new IllegalArgumentException("Производитель уже существует");
        });
        Manufacturer manufacturer = new Manufacturer();
        manufacturer.setName(normalized);
        return manufacturerRepository.save(manufacturer);
    }

    @Transactional
    public void renameManufacturer(Long id, String name, List<CatalogProduct> products, ProductService productService) {
        Manufacturer manufacturer = manufacturerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Производитель не найден"));
        String nextName = sanitizeName(name);
        Optional<Manufacturer> duplicate = manufacturerRepository.findByNameIgnoreCase(nextName);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            throw new IllegalArgumentException("Производитель с таким названием уже существует");
        }
        String previousName = manufacturer.getName();
        manufacturer.setName(nextName);
        manufacturerRepository.save(manufacturer);
        products.stream()
                .filter(product -> product.getBrand() != null && product.getBrand().equalsIgnoreCase(previousName))
                .sorted(Comparator.comparing(CatalogProduct::getId))
                .forEach(product -> productService.updateManufacturerName(product.getId(), nextName));
    }

    @Transactional
    public void deleteManufacturer(Long id, List<CatalogProduct> products, ProductService productService) {
        Manufacturer manufacturer = manufacturerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Производитель не найден"));
        products.stream()
                .filter(product -> product.getBrand() != null && product.getBrand().equalsIgnoreCase(manufacturer.getName()))
                .sorted(Comparator.comparing(CatalogProduct::getId))
                .forEach(product -> productService.updateManufacturerName(product.getId(), ""));
        manufacturerRepository.delete(manufacturer);
    }

    @Transactional
    public void ensureExists(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        manufacturerRepository.findByNameIgnoreCase(name.trim()).orElseGet(() -> {
            Manufacturer manufacturer = new Manufacturer();
            manufacturer.setName(name.trim());
            return manufacturerRepository.save(manufacturer);
        });
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название производителя обязательно");
        }
        return name.trim();
    }

    private String normalizeKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
