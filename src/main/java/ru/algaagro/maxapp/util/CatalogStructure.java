package ru.algaagro.maxapp.util;

import java.util.List;

public final class CatalogStructure {

    public static final String SEEDS = "Семена";
    public static final String PESTICIDES = "Пестициды";
    public static final String AGROCHEMICALS = "Агрохимикаты";
    public static final String MELIORANTS = "Мелиоранты";
    public static final String CLOSED_GROUND = "Препараты для закрытого грунта";
    public static final String PAVS = "ПАВы";
    public static final String DEFOAMERS = "Пеногасители";
    public static final String SPECIAL = "Спецпрепараты";
    public static final String PLANT_GLUE = "Клей для сельхоз растений";
    public static final String OTHER = "Прочее";

    public static final List<String> SECTIONS = List.of(
            SEEDS,
            PESTICIDES,
            AGROCHEMICALS,
            MELIORANTS,
            CLOSED_GROUND,
            PAVS,
            DEFOAMERS,
            SPECIAL,
            PLANT_GLUE
    );

    public static final List<String> SEED_SUBCATEGORIES = List.of(
            "Подсолнечник",
            "Кукуруза",
            "Рапс",
            "Горох",
            "Соя",
            "Озимая пшеница",
            "Яровая пшеница",
            "Яровой ячмень",
            "Озимая рожь",
            "Озимый тритикале",
            "Яровой тритикале",
            "Гречиха",
            "Овес",
            "Бобовые травы",
            "Люцерна",
            "Травосмеси",
            "Масличные травы",
            "Злаковые травы"
    );

    public static final List<String> PESTICIDE_SUBCATEGORIES = List.of(
            "Фунгициды",
            "Гербициды",
            "Инсектициды",
            "Защита семян (протравители)",
            "Десиканты",
            "Нематоциды",
            "Регуляторы роста",
            "Бактерициды",
            "Акарициды",
            "Моллюскоциды",
            "Зооциды",
            "Альгициды"
    );

    public static final List<String> SPECIAL_SUBCATEGORIES = List.of(
            "Амбарные вредители",
            "От крыс и мышей",
            "Обработка складских помещений"
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
        if (normalized.contains("клей для сельхоз") || normalized.contains("клей для с х") || normalized.contains("клей для сх") || normalized.contains("клей")) return PLANT_GLUE;
        return value == null ? "" : value.trim();
    }

    public static String inferSection(String context, String preferredSubcategory) {
        String preferred = normalizePesticideSubcategory(preferredSubcategory);
        if (!preferred.isBlank()) {
            return PESTICIDES;
        }
        String normalized = TextUtils.normalizeToken(context);
        if (hasPesticideSignals(normalized)) {
            return PESTICIDES;
        }
        if (normalized.contains("подсолнеч") || normalized.contains("кукуруз") || normalized.contains("рапс")
                || normalized.contains("горох") || normalized.contains("соя") || normalized.contains("пшениц")
                || normalized.contains("ячмен") || normalized.contains("гречих") || normalized.contains("овес")
                || normalized.contains("люцерн") || normalized.contains("клевер") || normalized.contains("вика")
                || normalized.contains("райграс") || normalized.contains("овсяниц") || normalized.contains("фестулолиум")
                || normalized.contains("тимофеевк") || normalized.contains("семен") || normalized.contains("гибрид")) {
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
                || normalized.contains("прилип") || normalized.contains("смачив") || normalized.contains("сурфакт")
                || normalized.contains("этоксилат") || normalized.contains("трисилоксан")
                || normalized.contains("метилирован") || normalized.contains("масл")) {
            return PAVS;
        }
        if (normalized.contains("роденти") || normalized.contains("репелент") || normalized.contains("амбарн")
                || normalized.contains("склад") || normalized.contains("мыш") || normalized.contains("крыс")) {
            return SPECIAL;
        }
        if (normalized.contains("клей")) {
            return PLANT_GLUE;
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
                || normalized.contains("протрав") || normalized.contains("регулятор рост")
                || normalized.contains("альгицид")) {
            return PESTICIDES;
        }
        return OTHER;
    }

    public static String inferSubcategory(String section, String context) {
        String normalizedSection = normalizeSectionName(section);
        String normalizedContext = TextUtils.normalizeToken(context);
        if (SEEDS.equals(normalizedSection)) {
            return normalizeSeedSubcategory(normalizedContext);
        }
        if (PESTICIDES.equals(normalizedSection)) {
            return normalizePesticideSubcategory(normalizedContext);
        }
        if (MELIORANTS.equals(normalizedSection)) {
            return normalizedContext.contains("кальциприлл") ? "Кальциприлл" : "";
        }
        if (SPECIAL.equals(normalizedSection)) {
            if (normalizedContext.contains("амбарн")) return "Амбарные вредители";
            if (normalizedContext.contains("крыс") || normalizedContext.contains("мыш") || normalizedContext.contains("роденти")) return "От крыс и мышей";
            if (normalizedContext.contains("склад") || normalizedContext.contains("помещ")) return "Обработка складских помещений";
            return "";
        }
        return "";
    }

    public static String normalizePesticideSubcategory(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (normalized.contains("фунгиц")) return "Фунгициды";
        if (normalized.contains("гербиц")) return "Гербициды";
        if (normalized.contains("инсекти")) return "Инсектициды";
        if (normalized.contains("десикант")) return "Десиканты";
        if (normalized.contains("дикват")) return "Десиканты";
        if (normalized.contains("нематоцид")) return "Нематоциды";
        if (normalized.contains("регулятор рост")) return "Регуляторы роста";
        if (normalized.contains("бактерицид")) return "Бактерициды";
        if (normalized.contains("акарицид")) return "Акарициды";
        if (normalized.contains("моллюскоцид")) return "Моллюскоциды";
        if (normalized.contains("зооцид")) return "Зооциды";
        if (normalized.contains("альгицид")) return "Альгициды";
        if (normalized.contains("протрав") || normalized.contains("защита семян")) {
            return "Защита семян (протравители)";
        }
        return "";
    }

    public static String normalizeSeedSubcategory(String value) {
        String normalized = TextUtils.normalizeToken(value);
        if (hasSeedExclusionSignals(normalized)) {
            return "";
        }
        if (normalized.contains("подсолнеч")) return "Подсолнечник";
        if (normalized.contains("кукуруз")) return "Кукуруза";
        if (normalized.contains("рапс")) return "Рапс";
        if (normalized.contains("горох")) return "Горох";
        if (normalized.contains("соя")) return "Соя";
        if (normalized.contains("озим") && normalized.contains("пшениц")) return "Озимая пшеница";
        if (normalized.contains("яров") && normalized.contains("пшениц")) return "Яровая пшеница";
        if (normalized.contains("ячмен")) return "Яровой ячмень";
        if (normalized.contains("озим") && normalized.contains("рож")) return "Озимая рожь";
        if (normalized.contains("озим") && normalized.contains("тритикал")) return "Озимый тритикале";
        if (normalized.contains("яров") && normalized.contains("тритикал")) return "Яровой тритикале";
        if (normalized.contains("гречих")) return "Гречиха";
        if (normalized.contains("овес")) return "Овес";
        if (normalized.contains("люцерн")) return "Люцерна";
        if (normalized.contains("клевер") || normalized.contains("вика") || normalized.contains("лядвен")) return "Бобовые травы";
        if (normalized.contains("лен") || normalized.contains("рыжик")) return "Масличные травы";
        if (normalized.contains("райграс") || normalized.contains("овсяниц") || normalized.contains("тимофеевк")
                || normalized.contains("фестулолиум") || normalized.contains("ежа")) {
            return "Злаковые травы";
        }
        if (normalized.contains("травосм") || normalized.contains("комби") || normalized.contains("смесь")
                || normalized.contains("%") || normalized.contains("/")) {
            return "Травосмеси";
        }
        return "";
    }

    private static boolean hasPesticideSignals(String normalized) {
        return normalized.contains("действующее вещество")
                || normalized.contains("гербиц")
                || normalized.contains("фунгиц")
                || normalized.contains("инсекти")
                || normalized.contains("десикант")
                || normalized.contains("нематоцид")
                || normalized.contains("бактерицид")
                || normalized.contains("акарицид")
                || normalized.contains("моллюскоцид")
                || normalized.contains("зооцид")
                || normalized.contains("альгицид")
                || normalized.contains("протрав")
                || normalized.contains("регулятор рост");
    }

    private static boolean hasSeedExclusionSignals(String normalized) {
        return normalized.contains("состав")
                || normalized.contains("расход")
                || hasPesticideSignals(normalized);
    }
}
