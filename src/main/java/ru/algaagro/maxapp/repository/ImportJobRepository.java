package ru.algaagro.maxapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.ImportJob;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
}
