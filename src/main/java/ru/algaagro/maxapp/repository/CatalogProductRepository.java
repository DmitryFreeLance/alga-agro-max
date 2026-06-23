package ru.algaagro.maxapp.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.CatalogProduct;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {
    List<CatalogProduct> findAllByActiveTrueOrderByNameAsc();
    Optional<CatalogProduct> findByExternalId(String externalId);
}
