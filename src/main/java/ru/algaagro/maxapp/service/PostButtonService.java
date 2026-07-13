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
        ensureDefaultCatalogButton();
        return postButtonRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public void ensureDefaultButtons() {
        ensureDefaultCatalogButton();
    }

    @Transactional
    public PostButton createButton(String label, String url) {
        ensureDefaultCatalogButton();
        String normalizedLabel = label == null ? "" : label.trim();
        String normalizedUrl = url == null ? "" : url.trim();
        if (normalizedLabel.isBlank() || normalizedUrl.isBlank()) {
            throw new IllegalArgumentException("Button label and url are required");
        }
        return postButtonRepository.findFirstByLabelAndUrl(normalizedLabel, normalizedUrl)
                .map(button -> {
                    button.setActive(true);
                    if (button.getSortOrder() < 1) {
                        button.setSortOrder(nextSortOrder());
                    }
                    return postButtonRepository.save(button);
                })
                .orElseGet(() -> {
                    PostButton button = new PostButton();
                    button.setLabel(normalizedLabel);
                    button.setUrl(normalizedUrl);
                    button.setActive(true);
                    button.setSortOrder(nextSortOrder());
                    return postButtonRepository.save(button);
                });
    }

    @Transactional
    public boolean deleteButton(Long id) {
        PostButton defaultButton = ensureDefaultCatalogButton();
        if (id == null || defaultButton.getId().equals(id)) {
            return false;
        }
        return postButtonRepository.findById(id)
                .map(button -> {
                    if (!button.isActive()) {
                        return false;
                    }
                    button.setActive(false);
                    postButtonRepository.save(button);
                    return true;
                })
                .orElse(false);
    }

    private int nextSortOrder() {
        return postButtonRepository.findAll().stream()
                .mapToInt(PostButton::getSortOrder)
                .max()
                .orElse(0) + 1;
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
