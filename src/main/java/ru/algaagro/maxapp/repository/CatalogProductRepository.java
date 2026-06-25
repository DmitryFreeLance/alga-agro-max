package ru.algaagro.maxapp.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.CatalogProduct;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    List<CatalogProduct> findAllByActiveTrue();
    List<CatalogProduct> findAllByActiveTrueOrderByNameAsc();
    Optional<CatalogProduct> findByExternalId(String externalId);
    List<CatalogProduct> findAllByExternalIdOrderByUpdatedAtDesc(String externalId);
    Optional<CatalogProduct> findByBitrixProductId(Long bitrixProductId);
    List<CatalogProduct> findAllByBitrixProductIdOrderByUpdatedAtDesc(Long bitrixProductId);
    List<CatalogProduct> findAllByBitrixProductIdIsNotNull();
    List<CatalogProduct> findAllBySourceFile(String sourceFile);
}
