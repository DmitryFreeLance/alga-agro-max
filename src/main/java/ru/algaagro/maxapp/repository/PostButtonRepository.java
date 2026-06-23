package ru.algaagro.maxapp.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.PostButton;

public interface PostButtonRepository extends JpaRepository<PostButton, Long> {
    List<PostButton> findAllByActiveTrueOrderBySortOrderAscIdAsc();
}
