package ru.algaagro.maxapp.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TextUtils {

    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(new Locale("ru", "RU")));

    private TextUtils() {
    }

    public static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    public static String toIndex(Collection<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(TextUtils::normalizeToken)
                .filter(s -> !s.isBlank())
                .distinct()
                .map(token -> "|" + token + "|")
                .collect(Collectors.joining());
    }

    public static String trimTo(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    public static String formatPrice(BigDecimal value) {
        return formatPrice(value, "RUB");
    }

    public static String formatPrice(BigDecimal value, String currencyCode) {
        if (value == null) {
            return "По запросу";
        }
        return PRICE_FORMAT.format(value) + " " + currencySymbol(currencyCode);
    }

    private static String currencySymbol(String currencyCode) {
        String normalized = currencyCode == null ? "" : currencyCode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USD" -> "$";
            case "EUR" -> "€";
            default -> "₽";
        };
    }

    public static boolean containsToken(String index, String token) {
        String normalized = normalizeToken(token);
        if (normalized.isBlank()) {
            return true;
        }
        return index != null && index.contains("|" + normalized + "|");
    }
}
