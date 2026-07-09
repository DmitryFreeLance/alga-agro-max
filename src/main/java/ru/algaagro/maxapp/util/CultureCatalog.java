package ru.algaagro.maxapp.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class CultureCatalog {

    public static final List<String> FIXED_SEED_CULTURE_OPTIONS = List.of(
            "Подсолнечник",
            "Кукуруза",
            "Рапс",
            "Сахарная свекла",
            "Горох",
            "Соя",
            "Озимая пшеница",
            "Яровая пшеница",
            "Яровой ячмень",
            "Озимая рожь",
            "Тритикале",
            "Гречиха",
            "Овес",
            "Бобовые травы",
            "Люцерна",
            "Травосмеси",
            "Масличные травы",
            "Злаковые травы"
    );

    public static final List<String> FIXED_PESTICIDE_CULTURE_OPTIONS = List.of(
            "Подсолнечник",
            "Кукуруза",
            "Рапс",
            "Горох",
            "Соя",
            "Озимая пшеница",
            "Яровая пшеница",
            "Яровой ячмень",
            "Озимая рожь",
            "Тритикале",
            "Гречиха",
            "Овес",
            "Люцерна",
            "Сахарная свекла",
            "Картофель",
            "Капуста",
            "Яблоня",
            "Вишня",
            "Виноград"
    );

    private CultureCatalog() {
    }

    public static List<String> fixedOptionsForSection(String sectionName) {
        String normalizedSection = CatalogStructure.normalizeSectionName(sectionName);
        if (CatalogStructure.SEEDS.equals(normalizedSection)) {
            return FIXED_SEED_CULTURE_OPTIONS;
        }
        if (CatalogStructure.PESTICIDES.equals(normalizedSection)) {
            return FIXED_PESTICIDE_CULTURE_OPTIONS;
        }
        return List.of();
    }

    public static List<String> allFixedOptions() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(FIXED_SEED_CULTURE_OPTIONS);
        values.addAll(FIXED_PESTICIDE_CULTURE_OPTIONS);
        return new ArrayList<>(values);
    }

    public static List<String> researchFixedOptions() {
        return FIXED_SEED_CULTURE_OPTIONS;
    }

    public static List<String> normalizeForResearch(List<String> values) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                resolved.addAll(expandToFixedOptions(value, CatalogStructure.SEEDS));
            }
        }
        return new ArrayList<>(resolved);
    }

    public static List<String> normalizeForSection(String sectionName, List<String> values) {
        List<String> options = fixedOptionsForSection(sectionName);
        if (options.isEmpty()) {
            return normalizeFreeform(values);
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                resolved.addAll(expandToFixedOptions(value, sectionName));
            }
        }
        return new ArrayList<>(resolved);
    }

    public static List<String> expandToFixedOptions(String value, String sectionName) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> options = fixedOptionsForSection(sectionName);
        if (options.isEmpty()) {
            return List.of(value == null ? "" : value.trim()).stream().filter(item -> !item.isBlank()).toList();
        }

        List<String> direct = options.stream()
                .filter(option -> TextUtils.normalizeToken(option).equals(normalized))
                .toList();
        if (!direct.isEmpty()) {
            return direct;
        }

        List<String> matches = new ArrayList<>();
        if (normalized.contains("подсолнеч")) matches = List.of("Подсолнечник");
        else if (normalized.contains("кукуруз")) matches = List.of("Кукуруза");
        else if (normalized.contains("рапс") && !normalized.contains("масл") && !normalized.contains("эфир")) matches = List.of("Рапс");
        else if (normalized.contains("горох")) matches = List.of("Горох");
        else if (normalized.contains("соя")) matches = List.of("Соя");
        else if (normalized.contains("гречих")) matches = List.of("Гречиха");
        else if (normalized.contains("овес")) matches = List.of("Овес");
        else if (normalized.contains("люцерн")) matches = List.of("Люцерна");
        else if (normalized.contains("ячмен")) matches = List.of("Яровой ячмень");
        else if (normalized.contains("рож")) matches = List.of("Озимая рожь");
        else if (normalized.contains("тритикал")) matches = List.of("Тритикале");
        else if (normalized.contains("пшениц")) matches = List.of("Озимая пшеница", "Яровая пшеница");
        else if (normalized.contains("свекл")) matches = List.of("Сахарная свекла");
        else if (normalized.contains("картоф")) matches = List.of("Картофель");
        else if (normalized.contains("капуст")) matches = List.of("Капуста");
        else if (normalized.contains("яблон")) matches = List.of("Яблоня");
        else if (normalized.contains("вишн")) matches = List.of("Вишня");
        else if (normalized.contains("виноград")) matches = List.of("Виноград");
        else if (normalized.contains("травосм") || normalized.contains("смес") || normalized.contains("комби")) matches = List.of("Травосмеси");
        else if (normalized.contains("бобов") && normalized.contains("трав")) matches = List.of("Бобовые травы");
        else if (normalized.contains("маслич") && normalized.contains("трав")) matches = List.of("Масличные травы");
        else if (normalized.contains("злаков") && normalized.contains("трав")) matches = List.of("Злаковые травы");
        else if (normalized.contains("клевер") || normalized.contains("вика") || normalized.contains("лядвен")) matches = List.of("Бобовые травы");
        else if (normalized.contains("райграс") || normalized.contains("овсяниц") || normalized.contains("тимофеевк")
                || normalized.contains("фестулолиум") || normalized.contains("ежа")) {
            matches = List.of("Злаковые травы");
        } else if (normalized.contains("зернов")) {
            matches = List.of("Озимая пшеница", "Яровая пшеница", "Яровой ячмень", "Озимая рожь", "Тритикале", "Овес");
        } else if (normalized.contains("маслич")) {
            matches = List.of("Подсолнечник", "Рапс", "Масличные травы");
        } else if (normalized.contains("бобов")) {
            matches = List.of("Горох", "Соя", "Бобовые травы", "Люцерна");
        }

        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String match : matches) {
            for (String option : options) {
                if (TextUtils.normalizeToken(option).equals(TextUtils.normalizeToken(match))) {
                    resolved.add(option);
                }
            }
        }
        return new ArrayList<>(resolved);
    }

    private static List<String> normalizeFreeform(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                resolved.add(trimmed);
            }
        }
        return new ArrayList<>(resolved);
    }
}
