package ru.algaagro.maxapp.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.PostButton;
import ru.algaagro.maxapp.repository.PostButtonRepository;

@Service
public class PostButtonService {

    private final PostButtonRepository postButtonRepository;

    public PostButtonService(PostButtonRepository postButtonRepository) {
        this.postButtonRepository = postButtonRepository;
    }

    public List<PostButton> getActiveButtons() {
        return postButtonRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public PostButton createButton(String label, String url) {
        PostButton button = new PostButton();
        button.setLabel(label);
        button.setUrl(url);
        button.setSortOrder((int) postButtonRepository.count());
        return postButtonRepository.save(button);
    }

    @Transactional
    public boolean deleteButton(Long id) {
        return postButtonRepository.findById(id).map(button -> {
            postButtonRepository.delete(button);
            return true;
        }).orElse(false);
    }
}
