package ru.algaagro.maxapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.JsonHelper;

class ExcelImportServiceTest {

    private ExcelImportService service;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        CatalogProductRepository catalogProductRepository = proxy(CatalogProductRepository.class, (methodName) -> switch (methodName) {
            case "findByExternalId" -> Optional.empty();
            case "findAll", "findAllByActiveTrueOrderByNameAsc" -> List.of();
            default -> null;
        });
        productService = new ProductService(
                catalogProductRepository,
                new JsonHelper(new ObjectMapper()),
                proxy(ObjectProvider.class, (methodName) -> switch (methodName) {
                    case "iterator" -> List.of().iterator();
                    default -> null;
                }),
                proxy(ObjectProvider.class, (methodName) -> switch (methodName) {
                    case "iterator" -> List.of().iterator();
                    default -> null;
                })
        );

        service = new ExcelImportService(
                null,
                null,
                productService,
                null,
                new JsonHelper(new ObjectMapper()),
                new AppProperties()
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> answers) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] { type },
                (instance, method, args) -> answers.apply(method.getName())
        );
    }

    @Test
    void parseDecimalKeepsThousandsAndFraction() {
        BigDecimal groupedInteger = ReflectionTestUtils.invokeMethod(service, "parseDecimal", "2 243");
        BigDecimal groupedFraction = ReflectionTestUtils.invokeMethod(service, "parseDecimal", "2 242,5");
        BigDecimal nbspGroupedFraction = ReflectionTestUtils.invokeMethod(service, "parseDecimal", "1\u00A0150,75");

        assertThat(groupedInteger).isEqualByComparingTo("2243");
        assertThat(groupedFraction).isEqualByComparingTo("2242.5");
        assertThat(nbspGroupedFraction).isEqualByComparingTo("1150.75");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseStructuredGridMergesContinuationRows() {
        List<List<String>> matrix = List.of(
                List.of("", "", "", "", "", "", "", "", ""),
                List.of("Название", "", "Фор-ма", "Произво-дитель", "Действующее вещество", "Упаковка", "Норма расхода", "цена (увеличить на 15%)", "Категория"),
                List.of("Клонрин", "", "КЭ", "«ФМРус»", "150 г/л клотианидина +", "5 л", "0,1-0,2 л/га", "1 150", "инсектициды"),
                List.of("", "", "", "", "100 г/л зета-циперметрина", "", "", "", ""),
                List.of("Тайпан", "", "КЭ", "", "90 г/л финоксапроп-П-этила + 90 г/л клодинафоп-пропаргила", "5 л", "0,25-0,35 л/га", "1 265", "гербициды"),
                List.of("", "", "", "«Форвард»; «АГРОДИМ»", "", "", "", "", ""),
                List.of("ТриЗаРа", "", "КЭ", "«ФМРус»", "267 г/л прохлораза+", "5 л", "1,5-2,0 л/га", "1 495", "фунгициды"),
                List.of("Супер", "", "", "", "100 г/л тебуконазола +", "", "", "", ""),
                List.of("", "", "", "", "83 г/л азоксистробина", "", "", "", "")
        );

        List<ExcelImportService.ImportRow> rows = (List<ExcelImportService.ImportRow>) ReflectionTestUtils.invokeMethod(
                service,
                "parseStructuredGrid",
                matrix,
                "фм.xlsx",
                "Лист1"
        );

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).columns().get("Действующее вещество"))
                .isEqualTo("150 г/л клотианидина +100 г/л зета-циперметрина");
        assertThat(rows.get(1).columns().get("Произво-дитель"))
                .isEqualTo("«Форвард»; «АГРОДИМ»");
        assertThat(rows.get(2).columns().get("Название"))
                .isEqualTo("ТриЗаРа Супер");
        assertThat(rows.get(2).columns().get("Действующее вещество"))
                .isEqualTo("267 г/л прохлораза+100 г/л тебуконазола +83 г/л азоксистробина");
    }

    @Test
    @SuppressWarnings("unchecked")
    void localWorkbookProductPreservesFullGroupedPrice() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("Название", "Клипер");
        columns.put("Действующее вещество", "100 г/л бифентрина");
        columns.put("Норма расхода", "0,2-0,3 л/га");
        columns.put("цена (увеличить на 15%)", "2 242,5");
        columns.put("Категория", "инсектициды");
        ExcelImportService.ImportRow row = new ExcelImportService.ImportRow(
                "row-1",
                "фм.xlsx",
                "Лист1",
                1,
                columns,
                "Клипер",
                ""
        );

        Optional<ProductService.ImportedProduct> product = (Optional<ProductService.ImportedProduct>) ReflectionTestUtils.invokeMethod(
                service,
                "tryBuildLocalWorkbookProduct",
                row
        );

        assertThat(product).isPresent();
        assertThat(product.orElseThrow().price()).isEqualByComparingTo("2242.5");
    }
}
