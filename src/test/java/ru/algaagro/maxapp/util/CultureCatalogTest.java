package ru.algaagro.maxapp.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CultureCatalogTest {

    @Test
    void normalizesPesticideCulturesToMiniAppOptions() {
        List<String> values = CultureCatalog.normalizeForSection(CatalogStructure.PESTICIDES, List.of("Пшеница", "ячмень"));

        assertThat(values).containsExactly(
                "Озимая пшеница",
                "Яровая пшеница",
                "Яровой ячмень"
        );
    }

    @Test
    void normalizesSeedTriticaleToSingleMiniAppValue() {
        List<String> values = CultureCatalog.normalizeForSection(CatalogStructure.SEEDS, List.of("Озимый тритикале", "яровой тритикале"));

        assertThat(values).containsExactly("Тритикале");
    }
}
