package ru.algaagro.maxapp.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.CatalogOrder;

public interface CatalogOrderRepository extends JpaRepository<CatalogOrder, Long> {
    Page<CatalogOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
