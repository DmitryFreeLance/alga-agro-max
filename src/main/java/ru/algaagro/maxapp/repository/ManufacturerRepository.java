package ru.algaagro.maxapp.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.Manufacturer;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {
    Optional<Manufacturer> findByNameIgnoreCase(String name);
}
