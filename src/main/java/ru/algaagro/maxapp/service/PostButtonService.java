package ru.algaagro.maxapp.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.PostButton;
import ru.algaagro.maxapp.repository.PostButtonRepository;

@Service
public class PostButtonService {

    public static final String DEFAULT_CATALOG_LABEL = "\uD83D\uDED2 Каталог";
    public static final String DEFAULT_CATALOG_URL = "https://max.ru/id9729390997_bot";

    private final PostButtonRepository postButtonRepository;

    public PostButtonService(PostButtonRepository postButtonRepository) {
        this.postButtonRepository = postButtonRepository;
    }

    @Transactional
    public List<PostButton> getActiveButtons() {
        return List.of(ensureDefaultCatalogButton());
    }

    @Transactional
    public void ensureDefaultButtons() {
        PostButton defaultButton = ensureDefaultCatalogButton();
        postButtonRepository.findAll().forEach(button -> {
            boolean isDefault = button.getId() != null && button.getId().equals(defaultButton.getId());
            if (!isDefault && button.isActive()) {
                button.setActive(false);
                postButtonRepository.save(button);
            }
        });
    }

    @Transactional
    public PostButton createButton(String label, String url) {
        return ensureDefaultCatalogButton();
    }

    @Transactional
    public boolean deleteButton(Long id) {
        ensureDefaultCatalogButton();
        return false;
    }

    private PostButton ensureDefaultCatalogButton() {
        PostButton defaultButton = postButtonRepository.findFirstByLabelAndUrl(DEFAULT_CATALOG_LABEL, DEFAULT_CATALOG_URL)
                .orElseGet(() -> {
                    PostButton button = new PostButton();
                    button.setLabel(DEFAULT_CATALOG_LABEL);
                    button.setUrl(DEFAULT_CATALOG_URL);
                    button.setActive(true);
                    button.setSortOrder(0);
                    return postButtonRepository.save(button);
                });
        boolean changed = false;
        if (!defaultButton.isActive()) {
            defaultButton.setActive(true);
            changed = true;
        }
        if (defaultButton.getSortOrder() != 0) {
            defaultButton.setSortOrder(0);
            changed = true;
        }
        if (changed) {
            defaultButton = postButtonRepository.save(defaultButton);
        }
        return defaultButton;
    }
}
