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
    public static final String DEFAULT_SUGAR_BEET_LABEL = "\uD83E\uDEDC Сахарная свекла";
    public static final String DEFAULT_SUGAR_BEET_URL = "https://algaagro.ru/miniapp/?section=%D0%A1%D0%B5%D0%BC%D0%B5%D0%BD%D0%B0&subcategory=%D0%A1%D0%B0%D1%85%D0%B0%D1%80%D0%BD%D0%B0%D1%8F%20%D1%81%D0%B2%D0%B5%D0%BA%D0%BB%D0%B0&manufacturer=MariboHilleshog";

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
        ensureDefaultPostButtons();
    }

    @Transactional
    public PostButton createButton(String label, String url) {
        ensureDefaultPostButtons();
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
        List<PostButton> defaultButtons = ensureDefaultPostButtons();
        if (id == null || defaultButtons.stream().anyMatch(button -> button.getId().equals(id))) {
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

    private List<PostButton> ensureDefaultPostButtons() {
        return List.of(
                ensureDefaultButton(DEFAULT_CATALOG_LABEL, DEFAULT_CATALOG_URL, 0),
                ensureDefaultButton(DEFAULT_SUGAR_BEET_LABEL, DEFAULT_SUGAR_BEET_URL, 1)
        );
    }

    private PostButton ensureDefaultCatalogButton() {
        ensureDefaultPostButtons();
        return postButtonRepository.findFirstByLabelAndUrl(DEFAULT_CATALOG_LABEL, DEFAULT_CATALOG_URL)
                .orElseThrow();
    }

    private PostButton ensureDefaultButton(String label, String url, int sortOrder) {
        PostButton defaultButton = postButtonRepository.findFirstByLabelAndUrl(label, url)
                .orElseGet(() -> {
                    PostButton button = new PostButton();
                    button.setLabel(label);
                    button.setUrl(url);
                    button.setActive(true);
                    button.setSortOrder(sortOrder);
                    return postButtonRepository.save(button);
                });
        boolean changed = false;
        if (!defaultButton.isActive()) {
            defaultButton.setActive(true);
            changed = true;
        }
        if (defaultButton.getSortOrder() != sortOrder) {
            defaultButton.setSortOrder(sortOrder);
            changed = true;
        }
        if (changed) {
            defaultButton = postButtonRepository.save(defaultButton);
        }
        return defaultButton;
    }
}
