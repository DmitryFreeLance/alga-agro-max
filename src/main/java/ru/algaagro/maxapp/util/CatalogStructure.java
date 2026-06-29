package ru.algaagro.maxapp.util;

import java.util.List;

public final class CatalogStructure {

    public static final String SEEDS = "Семена";
    public static final String PESTICIDES = "Пестициды";
    public static final String AGROCHEMICALS = "Агрохимикаты";
    public static final String MELIORANTS = "Хим Мелиоранты";
    public static final String CLOSED_GROUND = "Препараты для закрытого грунта";
    public static final String PAVS = "ПАВы";
    public static final String DEFOAMERS = "Пеногасители";
    public static final String SPECIAL = "Спецпрепараты";
    public static final String OTHER = "Прочее";
    public static final List<String> SECTIONS = List.of(
            SEEDS,
            PESTICIDES,
            AGROCHEMICALS,
            MELIORANTS,
            CLOSED_GROUND,
            PAVS,
            DEFOAMERS,
            SPECIAL
    );

    public static final List<String> PESTICIDE_SUBCATEGORIES = List.of(
            "Фунгициды",
            "Гербициды",
            "Инсектициды",
            "Десиканты",
            "Нематоциды",
            "Регуляторы роста растений",
            "Бактерициды",
            "Акарициды",
            "Моллюскоциды",
            "Зооциды",
            "Протравители"
    );

    private CatalogStructure() {
    }

    public static String normalizeSectionName(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("семен")) return SEEDS;
        if (normalized.contains("пестиц") || normalized.equals("сзр")) return PESTICIDES;
        if (normalized.contains("агропитан") || normalized.contains("агрохим")) return AGROCHEMICALS;
        if (normalized.contains("адъюв") || normalized.contains("адьюв") || normalized.equals("павы") || normalized.equals("пав")) return PAVS;
        if (normalized.contains("мелиор")) return MELIORANTS;
        if (normalized.contains("закрыт") || normalized.contains("теплиц")) return CLOSED_GROUND;
        if (normalized.contains("пеногас")) return DEFOAMERS;
        if (normalized.contains("спец")) return SPECIAL;
        return value == null ? "" : value.trim();
    }

    public static String inferSection(String context, String preferredSubcategory) {
        String preferred = normalizePesticideSubcategory(preferredSubcategory);
        if (!preferred.isBlank()) {
            return PESTICIDES;
        }
        String normalized = TextUtils.normalizeToken(context);
        if (normalized.contains("подсолнеч") || normalized.contains("кукуруз") || normalized.contains("рапс")
                || normalized.contains("горох") || normalized.contains("соя") || normalized.contains("пшениц")
                || normalized.contains("ячмен") || normalized.contains("гречих") || normalized.contains("овес")
                || normalized.contains("люцерн") || normalized.contains("семен") || normalized.contains("гибрид")) {
            return SEEDS;
        }
        if (normalized.contains("кальциприлл") || normalized.contains("мелиор") || normalized.contains("извест")) {
            return MELIORANTS;
        }
        if (normalized.contains("закрыт") || normalized.contains("теплиц")) {
            return CLOSED_GROUND;
        }
        if (normalized.contains("пеногас")) {
            return DEFOAMERS;
        }
        if (normalized.contains("пав") || normalized.contains("адъюв") || normalized.contains("адьюв")
                || normalized.contains("прилип") || normalized.contains("смачив") || normalized.contains("сурфакт")) {
            return PAVS;
        }
        if (normalized.contains("роденти") || normalized.contains("репелент") || normalized.contains("амбарн")
                || normalized.contains("склад") || normalized.contains("мыш") || normalized.contains("крыс")) {
            return SPECIAL;
        }
        if (normalized.contains("удобр") || normalized.contains("микроудобр") || normalized.contains("биостим")
                || normalized.contains("инокулянт") || normalized.contains("подкорм") || normalized.contains("npk")
                || normalized.contains("бор") || normalized.contains("цинк") || normalized.contains("магний")
                || normalized.contains("сер") || normalized.contains("аминокислот")) {
            return AGROCHEMICALS;
        }
        if (normalized.contains("гербиц") || normalized.contains("фунгиц") || normalized.contains("инсекти")
                || normalized.contains("десикант") || normalized.contains("нематоцид")
                || normalized.contains("бактерицид") || normalized.contains("акарицид")
                || normalized.contains("моллюскоцид") || normalized.contains("зооцид")
                || normalized.contains("протрав") || normalized.contains("регулятор рост")) {
            return PESTICIDES;
        }
        return OTHER;
    }

    public static String inferSubcategory(String section, String context) {
        String normalizedSection = normalizeSectionName(section);
        String normalizedContext = TextUtils.normalizeToken(context);
        if (PESTICIDES.equals(normalizedSection)) {
            return normalizePesticideSubcategory(normalizedContext);
        }
        if (MELIORANTS.equals(normalizedSection)) {
            return normalizedContext.contains("кальциприлл") ? "Кальциприлл" : "Химические мелиоранты";
        }
        if (PAVS.equals(normalizedSection)) {
            return PAVS;
        }
        if (DEFOAMERS.equals(normalizedSection)) {
            return DEFOAMERS;
        }
        if (CLOSED_GROUND.equals(normalizedSection)) {
            return CLOSED_GROUND;
        }
        if (SPECIAL.equals(normalizedSection)) {
            if (normalizedContext.contains("амбарн")) return "Амбарные вредители";
            if (normalizedContext.contains("крыс") || normalizedContext.contains("мыш") || normalizedContext.contains("роденти")) return "От крыс и мышей";
            if (normalizedContext.contains("склад") || normalizedContext.contains("помещ")) return "Обработка складских помещений";
            return "Спецпрепараты";
        }
        if (AGROCHEMICALS.equals(normalizedSection)) {
            if (normalizedContext.contains("микроудобр")) return "Микроудобрения";
            if (normalizedContext.contains("биостим")) return "Биостимуляторы";
            if (normalizedContext.contains("инокулянт")) return "Инокулянты";
            return "Удобрения";
        }
        return "";
    }

    public static String normalizePesticideSubcategory(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.contains("фунгиц")) return "Фунгициды";
        if (normalized.contains("гербиц")) return "Гербициды";
        if (normalized.contains("инсекти")) return "Инсектициды";
        if (normalized.contains("десикант")) return "Десиканты";
        if (normalized.contains("нематоцид")) return "Нематоциды";
        if (normalized.contains("регулятор рост")) return "Регуляторы роста растений";
        if (normalized.contains("бактерицид")) return "Бактерициды";
        if (normalized.contains("акарицид")) return "Акарициды";
        if (normalized.contains("моллюскоцид")) return "Моллюскоциды";
        if (normalized.contains("зооцид") || normalized.contains("зооцид")) return "Зооциды";
        if (normalized.contains("протрав")) return "Протравители";
        return "";
    }
}
