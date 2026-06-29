const root = document.getElementById("appRoot");
const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
const bridgeUserId = parseMaxUserIdFromBridgeInitData();
const queryUserId = new URLSearchParams(window.location.search).get("maxUserId");
let clientStateSyncTimer = null;
let noticeTimer = null;

const FIXED_SECTION_DEFINITIONS = [
    {
        name: "Семена",
        key: "seeds",
        icon: "./assets/category-seeds-flaticon.png",
        palette: ["#fff2b7", "#ffd681"],
        description: "Подсолнечник, зерновые, бобовые и травы",
        aliases: ["семен", "seed"],
        subcategories: [
            "Подсолнечник",
            "Кукуруза",
            "Рапс",
            "Горох",
            "Соя",
            "Озимая пшеница",
            "Яровая пшеница",
            "Ячмень",
            "Озимая рожь",
            "Озимый тритикале",
            "Яровой тритикале",
            "Гречиха",
            "Овес",
            "Бобовые травы",
            "Люцерна",
            "Травосмеси",
            "Многолетние травы",
            "Злаковые травы",
        ],
    },
    {
        name: "Пестициды",
        key: "pesticides",
        icon: "./assets/category-pesticides-ref.png",
        palette: ["#dff3d8", "#eaf7d8"],
        description: "Гербициды, фунгициды, инсектициды и протравители",
        aliases: ["пестиц", "сзр"],
        subcategories: [
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
            "Протравители",
        ],
    },
    {
        name: "Агрохимикаты",
        key: "agrochemicals",
        icon: "./assets/category-nutrition-flaticon.png",
        palette: ["#c8eff6", "#a8e0ee"],
        description: "Удобрения, микроэлементы и биостимуляторы",
        aliases: ["агрохим", "агропитан", "удобр", "питан"],
        subcategories: [
            "Удобрения",
            "Микроудобрения",
            "Биостимуляторы",
            "Инокулянты",
        ],
    },
    {
        name: "Хим Мелиоранты",
        key: "meliorants",
        icon: "./assets/category-meliorants-fixed.png",
        palette: ["#d9e9cf", "#cce3bc"],
        description: "Препараты для корректировки и восстановления почвы",
        aliases: ["мелиор"],
        subcategories: ["Кальциприлл", "Химические мелиоранты"],
    },
    {
        name: "Препараты для закрытого грунта",
        key: "closed-ground",
        icon: "./assets/category-closed-ground-fixed.png",
        palette: ["#d8f1d9", "#c7ebc5"],
        description: "Решения для теплиц и закрытого грунта",
        aliases: ["закрыт", "теплиц"],
        subcategories: ["Препараты для закрытого грунта"],
    },
    {
        name: "ПАВы",
        key: "pavs",
        icon: "./assets/category-pavs-fixed.png",
        palette: ["#d9edf0", "#d3f4e1"],
        description: "Прилипатели и поверхностно-активные вещества",
        aliases: ["пав", "адъюв", "адьюв", "прилип", "смачив", "сурфакт"],
        subcategories: ["ПАВы"],
    },
    {
        name: "Пеногасители",
        key: "defoamers",
        icon: "./assets/category-defoamers-fixed.png",
        palette: ["#d9e4f6", "#cedcf2"],
        description: "Технологические добавки для подавления пены",
        aliases: ["пеногас"],
        subcategories: ["Пеногасители"],
    },
    {
        name: "Спецпрепараты",
        key: "special",
        icon: "./assets/category-special-fixed.png",
        palette: ["#ece4fd", "#ddd1f6"],
        description: "Складская защита и специальные составы",
        aliases: ["спец", "специальн", "роденти", "репелент", "красител", "амбарн", "склад"],
        subcategories: [
            "Амбарные вредители",
            "От крыс и мышей",
            "Обработка складских помещений",
        ],
    },
];

const SECTION_INDEX = new Map(FIXED_SECTION_DEFINITIONS.map((section, index) => [normalize(section.name), index]));
const SECTION_DEFINITION_MAP = new Map(FIXED_SECTION_DEFINITIONS.map(section => [normalize(section.name), section]));
const SEED_FAO_RANGES = [
    { label: "ФАО до 150", min: null, max: 149 },
    { label: "ФАО 150-200", min: 150, max: 199 },
    { label: "ФАО 200-250", min: 200, max: 249 },
    { label: "ФАО 250-300", min: 250, max: 299 },
    { label: "ФАО 300-350", min: 300, max: 349 },
    { label: "ФАО 350-400", min: 350, max: 399 },
    { label: "ФАО 400-450", min: 400, max: 450 },
    { label: "ФАО свыше 450", min: 451, max: null },
];
const SEED_MATURITY_GROUPS = [
    "Ультраскороспелые",
    "Очень скороспелые",
    "Скороспелые",
    "Среднескороспелые",
    "Среднеспелые",
    "Среднепозднеспелые",
    "Позднеспелые",
    "Очень позднеспелые",
    "Исключительно позднеспелые",
];
const SEED_TREATMENT_TECHNOLOGIES = [
    "Классическая технология",
    "Технология экспресс (ExpressSun)",
    "Технология Clearfield («чистое поле»)",
];

const state = {
    maxUserId: resolveMaxUserId(),
    meta: null,
    profile: null,
    products: [],
    sections: [],
    profileOrders: [],
    nav: "catalog",
    favorites: loadStorage("alga-favorites", []),
    cart: loadStorage("alga-cart", []),
    successOrderCode: "",
    notice: { open: false, message: "", type: "success" },
    app: {
        catalogLoading: true,
        profileLoading: Boolean(resolveMaxUserId()),
        profileOrdersLoading: false,
        profileOrdersReady: false,
    },
    catalog: {
        query: "",
        section: "",
        sort: "default",
        filtersOpen: false,
        filterFocus: "",
        filterPanels: emptyFilterPanels(),
        draft: null,
        applied: emptyFilters(),
    },
    productModal: {
        open: false,
        productId: null,
        quantity: 1,
        imageIndex: 0,
    },
    checkout: {
        open: false,
        submitting: false,
        touched: false,
        uploaded: [],
        errors: {},
        form: {
            organization: "",
            farmName: "",
            inn: "",
            phone: "",
            email: "",
            address: "",
            comment: "",
        },
    },
    profileOrdersFilter: "ALL",
    admin: {
        ready: false,
        loading: false,
        menu: "dashboard",
        dashboard: null,
        products: [],
        manufacturers: [],
        orders: [],
        customers: [],
        broadcasts: { stats: null, history: [] },
        catalogSearch: "",
        catalogStatus: "ALL",
        catalogSection: "",
        catalogCategory: "",
        customerSearch: "",
        productEditor: { open: false, productId: null, categoryDraft: "" },
        orderModal: { open: false, orderId: null },
        customerModal: { open: false, maxUserId: null },
        manufacturerModal: { open: false, id: null, name: "" },
        broadcastForm: { text: "", imageUrl: "", imageName: "", sending: false },
        orderFilters: { search: "", status: "ALL", from: "", to: "" },
    },
};

if (maxBridge?.ready) {
    try {
        maxBridge.ready();
    } catch (error) {
        console.warn("MAX WebApp.ready failed", error);
    }
}

if (maxBridge?.expand) {
    try {
        maxBridge.expand();
    } catch (error) {
        console.warn("MAX WebApp.expand failed", error);
    }
}

render();

bootstrap().catch(error => {
    console.error(error);
    root.innerHTML = `<div class="page"><div class="empty-box">Не удалось загрузить каталог. Попробуйте обновить мини-приложение.</div></div>`;
});

root.addEventListener("click", handleClick);
root.addEventListener("input", handleInput);
root.addEventListener("change", handleChange);
root.addEventListener("submit", handleSubmit);
root.addEventListener("focusin", handleFocusIn);
root.addEventListener("focusout", handleFocusOut);

async function bootstrap() {
    const criticalProducts = fetchJson("/api/catalog/products?sort=name").then(products => {
        state.products = products;
        state.sections = getCatalogSections();
        state.app.catalogLoading = false;
        render();
    });

    fetchJson("/api/meta")
        .then(meta => {
            state.meta = meta;
            render();
        })
        .catch(error => console.warn("Meta preload failed", error));

    if (state.maxUserId) {
        fetchJson(`/api/profile?maxUserId=${state.maxUserId}`)
            .then(profile => {
                state.profile = profile;
                state.app.profileLoading = false;
                if (!state.cart.length && Array.isArray(profile.savedCart) && profile.savedCart.length) {
                    state.cart = profile.savedCart;
                    saveStorage("alga-cart", state.cart);
                }
                hydrateCheckoutFromProfile();
                rememberMaxUserId(state.maxUserId);
                scheduleClientStateSync(150);
                render();
            })
            .catch(error => {
                state.app.profileLoading = false;
                console.warn("Profile preload failed", error);
                render();
            });
    } else {
        state.profile = { admin: false, displayName: "Пользователь MAX" };
        state.app.profileLoading = false;
        render();
    }

    await criticalProducts;
}

async function loadAdminData() {
    const [dashboard, products, manufacturers, orders, customers, broadcasts] = await Promise.all([
        fetchJson(`/api/admin/dashboard?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/products?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/manufacturers?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/orders?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/customers?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/broadcasts?maxUserId=${state.maxUserId}`),
    ]);
    state.admin.ready = true;
    state.admin.dashboard = dashboard;
    state.admin.products = products;
    state.admin.manufacturers = manufacturers;
    state.admin.orders = orders;
    state.admin.customers = customers;
    state.admin.broadcasts = broadcasts;
    const tree = getAdminSectionTree();
    if (tree.length) {
        if (!state.admin.catalogSection || !tree.some(item => item.name === state.admin.catalogSection)) {
            state.admin.catalogSection = tree[0].name;
        }
        const section = tree.find(item => item.name === state.admin.catalogSection);
        if (state.admin.catalogCategory && section && !section.children.some(item => item.name === state.admin.catalogCategory)) {
            state.admin.catalogCategory = "";
        }
    } else {
        state.admin.catalogSection = "";
        state.admin.catalogCategory = "";
    }
}

function render() {
    if (isAdminWorkspaceActive()) {
        root.innerHTML = `
            <div class="admin-app">
                ${renderAdminPage()}
                ${renderAdminProductModal()}
                ${renderAdminManufacturerModal()}
                ${renderAdminOrderModal()}
                ${renderAdminCustomerModal()}
                ${renderNotice()}
            </div>
        `;
        return;
    }
    root.innerHTML = `
        <div class="mobile-app">
            ${renderTopbar()}
            ${renderCurrentPage()}
            ${renderBottomNav()}
            ${renderProductModal()}
            ${renderFiltersDrawer()}
            ${renderAdminProductModal()}
            ${renderAdminManufacturerModal()}
            ${renderAdminOrderModal()}
            ${renderAdminCustomerModal()}
            ${renderNotice()}
        </div>
    `;
    applyPendingFilterFocus();
}

function renderNotice() {
    if (!state.notice?.open || !state.notice.message) {
        return "";
    }
    return `<div class="app-notice app-notice-${escapeAttr(state.notice.type || "success")}">${escapeHtml(state.notice.message)}</div>`;
}

function renderPreservingFocus() {
    const active = document.activeElement;
    const field = active?.dataset?.field;
    const selectionStart = typeof active?.selectionStart === "number" ? active.selectionStart : null;
    const selectionEnd = typeof active?.selectionEnd === "number" ? active.selectionEnd : null;
    render();
    if (!field) {
        return;
    }
    const next = root.querySelector(`[data-field="${CSS.escape(field)}"]`);
    if (next && typeof next.focus === "function") {
        next.focus({ preventScroll: true });
        if (selectionStart !== null && selectionEnd !== null && typeof next.setSelectionRange === "function") {
            next.setSelectionRange(selectionStart, selectionEnd);
        }
    }
}

function renderTopbar() {
    if (isAdminWorkspaceActive()) {
        return "";
    }
    const activeTitle = getTopbarTitle();
    return `
        <header class="topbar">
            <div class="topbar-row">
                <div class="topbar-logo"><img src="./assets/logo.png" alt="ООО Алга Агро Групп"></div>
                <div class="topbar-title">
                    <h1>${escapeHtml(state.meta?.company || "ООО «Алга Агро Групп»")}</h1>
                    <p>Только вперёд!</p>
                </div>
                ${shouldShowBackButton()
                    ? `<button class="topbar-action" data-action="back">←</button>`
                    : `<button class="topbar-action" data-action="open-manager">⋯</button>`}
            </div>
            ${activeTitle.subtitle
                ? `<div class="page-head" style="margin-top:14px;"><h2>${escapeHtml(activeTitle.title)}</h2><p>${escapeHtml(activeTitle.subtitle)}</p></div>`
                : ""}
        </header>
    `;
}

function getTopbarTitle() {
    if (state.nav === "catalog" && state.catalog.section) {
        return { title: getSectionDisplayName(state.catalog.section), subtitle: `${getSectionProducts(state.catalog.section).length} товаров` };
    }
    if (state.nav === "favorites") {
        return { title: "Избранное", subtitle: "" };
    }
    if (state.nav === "cart") {
        return { title: state.checkout.open ? "Оформление заявки" : "Корзина", subtitle: state.checkout.open ? "Менеджер свяжется с вами и выставит счёт" : "" };
    }
    if (state.nav === "profile") {
        return { title: state.profile?.admin ? "Админ-панель" : "Профиль", subtitle: "" };
    }
    return { title: "", subtitle: "" };
}

function renderCurrentPage() {
    switch (state.nav) {
        case "cart":
            return renderCartPage();
        case "favorites":
            return renderFavoritesPage();
        case "profile":
            return state.profile?.admin ? renderAdminPage() : renderProfilePage();
        case "catalog":
        default:
            return renderCatalogPage();
    }
}

function renderCatalogPage() {
    const searchQuery = state.catalog.query.trim();
    const results = searchQuery ? getSearchResults(searchQuery) : [];
    return `
        <section class="page stack">
            <div class="search-shell">
                <label class="search-field">
                    <span>🔎</span>
                    <input data-field="catalog-query" type="search" placeholder="Поиск по каталогу..." value="${escapeAttr(state.catalog.query)}">
                    ${searchQuery ? `<button type="button" class="search-clear-btn" data-action="clear-search">×</button>` : `<span></span>`}
                </label>
            </div>
            ${state.catalog.section ? renderSectionPage() : renderCatalogHome(results)}
        </section>
    `;
}

function renderCatalogHome(results) {
    if (state.app.catalogLoading) {
        return `<div class="empty-box">Загружаем каталог...</div>`;
    }
    const searching = state.catalog.query.trim().length > 0;
    if (searching) {
        return `
            <div class="stack">
                <div class="search-muted">Найдено: ${results.length} товаров</div>
                ${renderProductsGrid(results)}
            </div>
        `;
    }
    return `
        <div class="stack">
            <div class="section-label">Разделы каталога</div>
            <div class="section-list">
                ${getCatalogSections().map(renderSectionCard).join("")}
            </div>
        </div>
    `;
}

function renderSectionCard(section) {
    const visual = getSectionVisual(section.name);
    return `
        <button type="button" class="section-card" data-action="open-section" data-section="${escapeAttr(section.name)}">
            <div class="section-card-visual" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                <img class="${escapeAttr(getVisualImageClass(visual))}" src="${visual.icon}" alt="${escapeAttr(getSectionDisplayName(section.name))}">
            </div>
            <div class="section-card-body">
                <p class="section-card-title">${escapeHtml(getSectionDisplayName(section.name))}</p>
                <p class="section-card-desc">${escapeHtml(section.description || visual.description)}</p>
            </div>
            <div class="section-card-end">
                <span class="pill-count">${section.productsCount}</span>
                <span class="section-arrow">›</span>
            </div>
        </button>
    `;
}

function renderSectionPage() {
    const products = getSectionProducts(state.catalog.section);
    const filtered = applyCatalogFilters(products);
    return `
        <div class="stack">
            ${renderSectionTabs()}
            <div class="catalog-section-layout">
                ${renderInlineFiltersSidebar()}
                <div class="catalog-results-panel">
                    <div class="toolbar-row catalog-toolbar-row">
                        <div class="catalog-results-meta">Товаров: ${filtered.length}</div>
                        <select class="toolbar-select" data-field="catalog-sort">
                            ${renderSortOptions()}
                        </select>
                    </div>
                    ${renderProductsGrid(filtered)}
                </div>
            </div>
        </div>
    `;
}

function renderSectionTabs() {
    const currentSection = state.catalog.section;
    return `
        <div class="quick-filters-row section-tabs-row">
            ${getCatalogSections().map(section => `
                <button type="button" class="quick-filter-btn section-tab-btn ${section.name === currentSection ? "active" : ""}" data-action="open-section" data-section="${escapeAttr(section.name)}">
                    ${escapeHtml(getSectionDisplayName(section.name))}
                </button>
            `).join("")}
        </div>
    `;
}

function getSectionDefinition(name) {
    const normalized = normalize(name);
    return FIXED_SECTION_DEFINITIONS.find(section =>
        normalize(section.name) === normalized
        || (section.aliases || []).some(alias => normalized.includes(normalize(alias)))
    ) || null;
}

function sortValuesByConfiguredOrder(sectionName, values) {
    const definition = getSectionDefinition(sectionName);
    const orderMap = new Map((definition?.subcategories || []).map((name, index) => [normalize(name), index]));
    return [...values].sort((left, right) => {
        const leftOrder = orderMap.has(normalize(left)) ? orderMap.get(normalize(left)) : Number.MAX_SAFE_INTEGER;
        const rightOrder = orderMap.has(normalize(right)) ? orderMap.get(normalize(right)) : Number.MAX_SAFE_INTEGER;
        if (leftOrder !== rightOrder) {
            return leftOrder - rightOrder;
        }
        return String(left).localeCompare(String(right), "ru", { sensitivity: "base" });
    });
}

function resolveSectionSubcategory(rawValue, sectionName) {
    const raw = String(rawValue || "").replace(/\s+/g, " ").trim();
    if (!raw) {
        return "";
    }
    const normalized = normalize(raw);
    const normalizedSection = normalize(sectionName);
    if (normalizedSection === normalize("Семена")) {
        if (normalized.includes("подсолнеч")) return "Подсолнечник";
        if (normalized.includes("кукуруз")) return "Кукуруза";
        if (normalized.includes("рапс")) return "Рапс";
        if (normalized.includes("горох")) return "Горох";
        if (normalized.includes("соя")) return "Соя";
        if (normalized.includes("озим") && normalized.includes("пшениц")) return "Озимая пшеница";
        if (normalized.includes("яров") && normalized.includes("пшениц")) return "Яровая пшеница";
        if (normalized.includes("ячмен")) return "Ячмень";
        if (normalized.includes("озим") && normalized.includes("рож")) return "Озимая рожь";
        if (normalized.includes("озим") && normalized.includes("тритикал")) return "Озимый тритикале";
        if (normalized.includes("яров") && normalized.includes("тритикал")) return "Яровой тритикале";
        if (normalized.includes("гречих")) return "Гречиха";
        if (normalized.includes("овес")) return "Овес";
        if (normalized.includes("бобов") && normalized.includes("трав")) return "Бобовые травы";
        if (normalized.includes("люцерн")) return "Люцерна";
        if (normalized.includes("травосм")) return "Травосмеси";
        if (normalized.includes("многолет") && normalized.includes("трав")) return "Многолетние травы";
        if (normalized.includes("злаков") && normalized.includes("трав")) return "Злаковые травы";
        return "";
    }
    if (normalizedSection === normalize("Пестициды")) {
        if (normalized.includes("фунгиц")) return "Фунгициды";
        if (normalized.includes("гербиц")) return "Гербициды";
        if (normalized.includes("инсекти")) return "Инсектициды";
        if (normalized.includes("десикант")) return "Десиканты";
        if (normalized.includes("нематоцид")) return "Нематоциды";
        if (normalized.includes("бактерицид")) return "Бактерициды";
        if (normalized.includes("акарицид")) return "Акарициды";
        if (normalized.includes("моллюскоцид")) return "Моллюскоциды";
        if (normalized.includes("зооцид")) return "Зооциды";
        if (normalized.includes("протрав")) return "Протравители";
        if (normalized.includes("регулятор рост")) return "Регуляторы роста растений";
        return "";
    }
    if (normalizedSection === normalize("Агрохимикаты")) {
        if (normalized.includes("микроудобр")) return "Микроудобрения";
        if (normalized.includes("биостим")) return "Биостимуляторы";
        if (normalized.includes("инокулянт")) return "Инокулянты";
        if (normalized.includes("удобр") || normalized.includes("агрохим") || normalized.includes("питан")) return "Удобрения";
        return "";
    }
    if (normalizedSection === normalize("Хим Мелиоранты")) {
        return normalized.includes("кальциприлл") ? "Кальциприлл" : "Химические мелиоранты";
    }
    if (normalizedSection === normalize("Препараты для закрытого грунта")) {
        return "Препараты для закрытого грунта";
    }
    if (normalizedSection === normalize("ПАВы")) {
        return "ПАВы";
    }
    if (normalizedSection === normalize("Пеногасители")) {
        return "Пеногасители";
    }
    if (normalizedSection === normalize("Спецпрепараты")) {
        if (normalized.includes("амбарн")) return "Амбарные вредители";
        if (normalized.includes("крыс") || normalized.includes("мыш")) return "От крыс и мышей";
        if (normalized.includes("роденти")) return "От крыс и мышей";
        if (normalized.includes("склад") || normalized.includes("помещ")) return "Обработка складских помещений";
        return "";
    }
    return "";
}

function mapProductToSectionSubcategory(product, sectionName) {
    const definition = getSectionDefinition(sectionName);
    const candidates = [
        ...(toStringArray(product?.filterMap?.cultureGroup).map(formatSeedGroupLabel)),
        product?.subcategory,
        product?.itemType,
        product?.category,
        product?.name,
        ...(product?.cultures || []),
    ];
    for (const candidate of candidates) {
        const resolved = resolveSectionSubcategory(candidate, sectionName);
        if (resolved) {
            return resolved;
        }
    }
    return definition?.subcategories?.[0] || normalizeFilterLabel(product?.subcategory || product?.itemType || "") || "";
}

function renderProductsGrid(products) {
    if (!products.length) {
        return `<div class="empty-box">По выбранным параметрам товары не найдены.</div>`;
    }
    return `<div class="products-grid">${products.map(renderProductCard).join("")}</div>`;
}

function renderProductCard(product) {
    const visual = getProductVisual(product);
    const favorite = isFavorite(product.id);
    const cartItem = getCartItem(product.id);
    const price = renderProductPrice(product);
    const subtitle = getProductCardSubtitle(product);
    return `
        <article class="product-card" data-action="open-product" data-product-id="${product.id}">
            <div class="product-card-visual" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                <img class="${escapeAttr(getVisualImageClass(visual))}" src="${visual.icon}" alt="${escapeAttr(product.name)}">
                <button type="button" class="favorite-fab ${favorite ? "active" : ""}" data-action="toggle-favorite" data-product-id="${product.id}">${favorite ? "♥" : "♡"}</button>
            </div>
            <div class="product-card-body">
                <p class="product-card-title">${escapeHtml(product.name)}</p>
                <p class="product-card-subtitle">${escapeHtml(subtitle)}</p>
                <div class="price-line">
                    ${price}
                    ${product.price == null
                        ? `<span></span>`
                        : cartItem
                            ? renderStepper(product.id, cartItem.quantity, "card")
                            : `<button type="button" class="card-cart-btn" data-action="add-product" data-product-id="${product.id}">+</button>`}
                </div>
            </div>
        </article>
    `;
}

function getProductCardSubtitle(product) {
    if (normalize(product?.category) === normalize("Пестициды")) {
        return formatCompactPackageDisplay(product) || product.brand || getProductLeafSectionName(product) || "";
    }
    return product.brand || getProductLeafSectionName(product) || "";
}

function formatCompactPackageDisplay(product) {
    const config = inferAdminOrderConfig(product);
    const volume = String(config.packageVolume || "").trim();
    const units = Number(config.unitsPerPackage || 1);
    const normalizedUnit = normalize(config.unitName || "");
    if (!volume) {
        return String(product?.packageDescription || product?.packageType || product?.unitName || "").trim();
    }
    if ((normalizedUnit === "л" || normalizedUnit.includes("лит") || normalizedUnit === "кг" || normalizedUnit.includes("кил")) && units > 1) {
        return `${formatAdminNumber(volume)}x${formatAdminNumber(units)}`;
    }
    if (normalizedUnit === "л" || normalizedUnit.includes("лит")) {
        return `${formatAdminNumber(volume)}л`;
    }
    if (normalizedUnit === "кг" || normalizedUnit.includes("кил")) {
        return `${formatAdminNumber(volume)}кг`;
    }
    return String(product?.packageDescription || "").trim();
}

function renderProductPrice(product) {
    const oldPrice = normalizePrice(product.oldPrice);
    if (product.price == null) {
        return `<div class="price-block request"><div class="old-price">&nbsp;</div><strong>По запросу</strong></div>`;
    }
    return `
        <div class="price-block ${oldPrice ? "discounted" : ""}">
            <div class="old-price">${oldPrice ? `${formatPrice(oldPrice)}` : "&nbsp;"}</div>
            <strong class="${oldPrice ? "price-current-discount" : ""}">${formatPrice(product.price)}</strong>
        </div>
    `;
}

function renderCartPage() {
    if (state.checkout.open) {
        return renderCheckoutPage();
    }
    if (!state.cart.length) {
        return `
            <section class="page stack">
                <div class="empty-box">
                    <p>Корзина пока пуста.</p>
                    <button class="primary-btn" data-action="go-catalog">Перейти в каталог</button>
                </div>
            </section>
        `;
    }
    const items = getCartProducts();
    return `
        <section class="page stack">
            <div class="cart-list">${items.map(renderCartItem).join("")}</div>
            <div class="summary-card">
                <div class="summary-grid">
                    <div class="summary-row"><span>Позиций</span><span>${items.length}</span></div>
                    <div class="summary-row"><span>Единиц</span><span>${sumCartUnits()}</span></div>
                    <div class="summary-row"><span>Примерная сумма</span><strong>${formatApproximateTotal(items)}</strong></div>
                </div>
                <button class="primary-btn" data-action="open-checkout">Оформить заявку →</button>
            </div>
        </section>
    `;
}

function renderCartItem(item) {
    const visual = getProductVisual(item.product);
    return `
        <article class="cart-item">
            <div class="cart-thumb" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                <img src="${visual.icon}" alt="${escapeAttr(item.product.name)}">
            </div>
            <div class="cart-main">
                <h3>${escapeHtml(item.product.name)}</h3>
                <div class="cart-meta">${escapeHtml(item.product.brand || item.product.packageDescription || item.product.unitName || "")}</div>
                ${renderStepper(item.product.id, item.quantity, "cart")}
            </div>
            <div class="cart-end">
                <button class="delete-btn" data-action="remove-cart" data-product-id="${item.product.id}">×</button>
                <strong>${item.product.price == null ? "По запросу" : formatPrice(item.product.price * item.quantity)}</strong>
            </div>
        </article>
    `;
}

function renderCheckoutPage() {
    if (state.successOrderCode) {
        return `
            <section class="page stack">
                <div class="success-screen summary-card">
                    <h3>Заявка отправлена!</h3>
                    <p>Менеджер свяжется с вами в течение 30 минут.</p>
                    <div>Номер заявки: <strong>${escapeHtml(state.successOrderCode)}</strong></div>
                    <button class="primary-btn" data-action="go-catalog">Вернуться в каталог</button>
                </div>
            </section>
        `;
    }
    const attachmentHtml = state.checkout.uploaded.map((item, index) => `
        <div class="attachment-item">
            <div>
                <strong>${escapeHtml(item.originalName || item.storedName)}</strong>
                <div class="search-muted">${escapeHtml(item.contentType || "Файл")}</div>
            </div>
            <button class="delete-btn" data-action="remove-attachment" data-index="${index}">×</button>
        </div>
    `).join("");
    return `
        <section class="page stack">
            <button class="search-muted" data-action="close-checkout">‹ Назад</button>
            <div class="checkout-panel">
                <div class="form-grid">
                    ${renderField("organization", "Организация / ФИО", state.checkout.form.organization, "ООО Агро-Степь", true)}
                    ${renderField("farmName", "Название хозяйства", state.checkout.form.farmName, "КФХ Рассвет", false)}
                    ${renderField("inn", "ИНН", state.checkout.form.inn, "1234567890", false)}
                    ${renderField("phone", "Телефон", state.checkout.form.phone, "+7 900 000-00-00", true)}
                    ${renderField("email", "Email", state.checkout.form.email, "mail@company.ru", true)}
                    ${renderField("address", "Адрес доставки", state.checkout.form.address, "Регион, район, населённый пункт", true)}
                    <div class="field">
                        <label>Реквизиты</label>
                        <label class="upload-box">
                            <input type="file" data-field="checkout-attachment" multiple>
                            <div style="font-size:2rem;">📎</div>
                            <div>Прикрепить реквизиты</div>
                            <div class="upload-hint">Фото, PDF, Word — любой формат, до 25 МБ</div>
                        </label>
                    </div>
                    ${attachmentHtml ? `<div class="attachment-list">${attachmentHtml}</div>` : ""}
                    <div class="field">
                        <label>Состав заявки</label>
                        <div class="summary-card">
                            ${getCartProducts().map(item => `<div class="summary-row"><span>${escapeHtml(item.product.name)}</span><span>${formatQuantity(item.quantity)} ${escapeHtml(item.product.unitName || "ед.")}</span></div>`).join("")}
                            <div class="summary-row"><strong>Итого позиций</strong><strong>${getCartProducts().length}</strong></div>
                        </div>
                    </div>
                    <div class="field">
                        <label>Комментарий</label>
                        <textarea data-field="checkout-comment" placeholder="Удобное время звонка, особые условия...">${escapeHtml(state.checkout.form.comment)}</textarea>
                    </div>
                </div>
                <button class="primary-btn" ${state.checkout.submitting ? "disabled" : ""} data-action="submit-order">Отправить заявку →</button>
            </div>
        </section>
    `;
}

function renderFavoritesPage() {
    const products = state.products.filter(product => isFavorite(product.id));
    return `
        <section class="page stack">
            ${products.length ? `<div class="products-grid">${products.map(renderProductCard).join("")}</div>` : `<div class="empty-box">Вы еще не добавили товары в избранное.</div>`}
        </section>
    `;
}

function renderProfilePage() {
    const orders = filterProfileOrders();
    const ordersBlock = state.app.profileOrdersLoading
        ? `<div class="empty-box">Загружаем заявки...</div>`
        : (orders.length ? orders.map(renderOrderCard).join("") : `<div class="empty-box">Заявок по этому фильтру пока нет.</div>`);
    return `
        <section class="page stack">
            <div class="profile-card">
                <div class="profile-hero">
                    <div class="profile-avatar">👤</div>
                    <h2>${escapeHtml(state.profile?.displayName || "Пользователь")}</h2>
                    <p>${escapeHtml(state.profile?.phone || "")}</p>
                </div>
            </div>
            <div class="profile-section stack">
                <button class="link-tile" data-action="toggle-profile-orders">
                    <div class="link-tile-text">
                        <strong>Мои заявки</strong>
                        <p>История с статусами</p>
                    </div>
                    <span class="cell-arrow">›</span>
                </button>
                <div class="profile-status-tabs">
                    ${["ALL", "NEW", "IN_PROGRESS", "COMPLETED", "CANCELLED"].map(status => `
                        <button class="status-chip ${state.profileOrdersFilter === status ? "active" : ""}" data-action="profile-status" data-status="${status}">
                            ${profileStatusLabel(status)}
                        </button>
                    `).join("")}
                </div>
                <div class="orders-stack">
                    ${ordersBlock}
                </div>
            </div>
            <div class="profile-section">
                <div class="section-label" style="margin-bottom:10px;">Контакты</div>
                <div class="profile-links">
                    <a class="link-tile" href="tel:${escapeAttr(state.meta?.managerPhone || "")}">
                        <div class="link-tile-text">
                            <strong>Позвонить менеджеру</strong>
                            <p>${escapeHtml(state.meta?.managerPhone || "")} — ${escapeHtml(state.meta?.managerName || "Марат")}</p>
                        </div>
                        <span class="cell-arrow">›</span>
                    </a>
                    <button class="link-tile" data-action="open-manager">
                        <div class="link-tile-text">
                            <strong>Написать в MAX</strong>
                            <p>${escapeHtml(state.meta?.managerName || "Менеджер")}</p>
                        </div>
                        <span class="cell-arrow">›</span>
                    </button>
                </div>
            </div>
        </section>
    `;
}

function renderOrderCard(order) {
    return `
        <article class="summary-card">
            <div class="summary-row">
                <strong>${escapeHtml(order.publicCode)}</strong>
                <span class="status-chip active">${escapeHtml(order.statusLabel || profileStatusLabel(order.status))}</span>
            </div>
            <div class="search-muted">${formatDate(order.createdAt)}</div>
            <div class="search-muted">${order.items.map(item => `${escapeHtml(item.productName)} × ${formatQuantity(item.quantity)} ${escapeHtml(item.unitName || "ед.")}`).join(", ")}</div>
        </article>
    `;
}

function renderAdminPage() {
    if (state.admin.loading && !state.admin.ready) {
        return `
            <section class="admin-page">
                <div class="admin-shell">
                    <div class="admin-main" style="width:100%;">
                        <div class="admin-card admin-card-spacious">
                            <h3>Загружаем админ-панель...</h3>
                            <p>Подтягиваем каталог, заявки и клиентов.</p>
                        </div>
                    </div>
                </div>
            </section>
        `;
    }
    const meta = getAdminPageMeta();
    return `
        <section class="admin-page">
            <div class="admin-shell">
                <aside class="admin-sidebar">
                    <div class="admin-brand">
                        <img src="./assets/logo-green-bg.png" alt="Алга Агро">
                        <div class="admin-brand-text">
                            <strong>${escapeHtml(state.meta?.company || "ООО «Алга Агро Групп»")}</strong>
                            <span>Панель управления</span>
                        </div>
                    </div>
                    <div class="admin-sidebar-group">
                        <div class="admin-sidebar-label">Главное</div>
                        ${renderAdminMenuButton("dashboard", "▦", "Дашборд", null)}
                    </div>
                    <div class="admin-sidebar-group">
                        <div class="admin-sidebar-label">Каталог</div>
                        ${renderAdminMenuButton("catalog", "◫", "Каталог", null)}
                        ${renderAdminMenuButton("manufacturers", "◪", "Производители", null)}
                    </div>
                    <div class="admin-sidebar-group">
                        <div class="admin-sidebar-label">Продажи</div>
                        ${renderAdminMenuButton("orders", "◩", "Заявки", getPendingOrdersCount())}
                        ${renderAdminMenuButton("customers", "◎", "Клиенты", null)}
                    </div>
                    <div class="admin-sidebar-group">
                        <div class="admin-sidebar-label">Коммуникации</div>
                        ${renderAdminMenuButton("broadcasts", "✦", "Рассылка", null)}
                    </div>
                    <button class="admin-exit-btn" data-action="admin-exit">← Клиентский каталог</button>
                    <div class="admin-sidebar-footer">Алга Агро Групп · 2026</div>
                </aside>
                <div class="admin-main">
                    <header class="admin-header">
                        <div class="admin-header-copy">
                            <h2>${escapeHtml(meta.title)}</h2>
                            <p>${escapeHtml(meta.subtitle)}</p>
                        </div>
                        <div class="admin-user">
                            <span>${escapeHtml(getAdminDisplayName())}</span>
                            <div class="admin-user-avatar">${escapeHtml(getAdminDisplayInitial())}</div>
                        </div>
                    </header>
                    <div class="admin-content">
                    ${renderAdminContent()}
                    </div>
                </div>
            </div>
        </section>
    `;
}

function renderAdminMenuButton(key, icon, label, badge) {
    return `
        <button class="admin-menu-btn ${state.admin.menu === key ? "active" : ""}" data-action="admin-menu" data-menu="${key}">
            <span class="admin-menu-icon">${icon}</span>
            <span class="admin-menu-text">${escapeHtml(label)}</span>
            ${badge ? `<span class="pending-badge">${badge}</span>` : ""}
        </button>
    `;
}

function getAdminPageMeta() {
    switch (state.admin.menu) {
        case "catalog":
            return { title: "Каталог", subtitle: "Структура разделов, товары и быстрые действия" };
        case "manufacturers":
            return { title: "Производители", subtitle: `Всего: ${state.admin.manufacturers.length} производителей` };
        case "orders":
            return { title: "Заявки", subtitle: "Фильтрация, просмотр состава и смена статусов" };
        case "customers":
            return { title: "Клиенты", subtitle: "Посетители бота, корзины, черновики и история заказов" };
        case "broadcasts":
            return { title: "Рассылка", subtitle: "Сообщения всем пользователям и история кампаний" };
        case "dashboard":
        default:
            return { title: "Дашборд", subtitle: "Ключевые показатели и последние заявки" };
    }
}

function getAdminDisplayName() {
    return state.profile?.displayName || state.meta?.managerName || "Администратор";
}

function getAdminDisplayInitial() {
    return String(getAdminDisplayName()).trim().charAt(0).toUpperCase() || "A";
}

function renderAdminContent() {
    switch (state.admin.menu) {
        case "catalog":
            return renderAdminCatalog();
        case "manufacturers":
            return renderAdminManufacturers();
        case "orders":
            return renderAdminOrders();
        case "customers":
            return renderAdminCustomers();
        case "broadcasts":
            return renderAdminBroadcasts();
        case "dashboard":
        default:
            return renderAdminDashboard();
    }
}

function renderAdminDashboard() {
    const dashboard = state.admin.dashboard || { latestOrders: [] };
    return `
        <div class="admin-kpi-grid">
            ${renderAdminKpiCard("◫", dashboard.ordersTotal || 0, "Заявок всего", `+${dashboard.ordersAddedThisWeek || 0} за неделю`)}
            ${renderAdminKpiCard("⧖", dashboard.ordersPending || 0, "В ожидании", "Требуют ответа", "warning")}
            ${renderAdminKpiCard("◪", dashboard.productsTotal || 0, "Товаров", "В каталоге", "neutral")}
            ${renderAdminKpiCard("◉", dashboard.usersTotal || 0, "Пользователей", `+${dashboard.usersAddedThisMonth || 0} за месяц`, "accent")}
        </div>
        <div class="admin-card admin-card-spacious">
            <div class="admin-section-head">
                <div>
                    <h3>Новые заявки</h3>
                    <p>Последние обращения клиентов по каталогу</p>
                </div>
                <button class="admin-outline-btn" data-action="admin-menu" data-menu="orders">Все заявки</button>
            </div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead><tr><th>Номер</th><th>Клиент</th><th>Телефон</th><th>Товары</th><th>Дата</th><th>Статус</th><th></th></tr></thead>
                    <tbody>
                        ${(dashboard.latestOrders || []).map(order => `
                            <tr>
                                <td data-label="Номер">${escapeHtml(order.publicCode)}</td>
                                <td data-label="Клиент">${escapeHtml(order.customerName || "")}</td>
                                <td data-label="Телефон">${escapeHtml(order.customerPhone || "")}</td>
                                <td data-label="Товары">${escapeHtml(summarizeOrderItems(order.items || []))}</td>
                                <td data-label="Дата">${formatDate(order.createdAt)}</td>
                                <td data-label="Статус">${renderAdminStatusBadge(order.status, order.statusLabel)}</td>
                                <td data-label="Действие"><button class="admin-table-btn" data-action="open-admin-order" data-order-id="${order.id}">Открыть</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        </div>
    `;
}

function renderAdminCatalog() {
    const tree = getAdminSectionTree();
    const activeSectionName = state.admin.catalogSection || tree[0]?.name || "";
    const activeSection = tree.find(item => item.name === activeSectionName) || null;
    const products = getAdminFilteredProducts();
    const sectionProducts = activeSectionName
        ? state.admin.products.filter(item => getProductSectionName(item) === activeSectionName)
        : state.admin.products;
    const visibleCount = sectionProducts.filter(item => item.active).length;
    const sectionVisual = activeSection?.visual || getSectionVisual(activeSectionName || FIXED_SECTION_DEFINITIONS[0]?.name || "");
    return `
        <div class="admin-catalog-layout">
            <div class="admin-card admin-card-spacious">
                <div class="admin-section-head">
                    <div>
                        <h3>Структура</h3>
                        <p>Разделы и подкатегории каталога</p>
                    </div>
                    <button class="admin-outline-btn" disabled>+ Раздел</button>
                </div>
                <div class="admin-tree">
                    ${tree.map(section => `
                        <div class="admin-tree-group ${activeSectionName === section.name ? "active" : ""}">
                            <div class="admin-tree-title">
                                <button class="admin-tree-parent" data-action="admin-section" data-section="${escapeAttr(section.name)}">
                                    <span class="admin-tree-icon" style="background:linear-gradient(135deg, ${section.visual.palette[0]}, ${section.visual.palette[1]});">
                                        <img src="${section.visual.icon}" alt="${escapeAttr(section.name)}">
                                    </span>
                                    <span>${escapeHtml(getSectionDisplayName(section.name))}</span>
                                </button>
                                <span class="pill-count">${section.count}</span>
                            </div>
                            ${activeSectionName === section.name ? `
                                <div class="admin-tree-list">
                                    ${section.children.map(child => `
                                        <button class="admin-tree-item ${state.admin.catalogCategory === child.name ? "active" : ""}" data-action="admin-section" data-section="${escapeAttr(section.name)}" data-category="${escapeAttr(child.name)}">
                                            <span>${escapeHtml(child.name)}</span>
                                            <span class="search-muted">${child.count}</span>
                                        </button>
                                    `).join("")}
                                </div>
                            ` : ""}
                        </div>
                    `).join("")}
                </div>
            </div>
            <div class="admin-stack">
                ${activeSection ? `
                    <div class="admin-card admin-card-spacious">
                        <div class="admin-section-summary">
                            <div class="admin-section-summary-visual" style="background:linear-gradient(135deg, ${sectionVisual.palette[0]}, ${sectionVisual.palette[1]});">
                                <img src="${sectionVisual.icon}" alt="${escapeAttr(activeSection.name)}">
                            </div>
                            <div class="admin-section-summary-copy">
                                <div class="search-muted">Каталог / ${escapeHtml(getSectionDisplayName(activeSection.name))}</div>
                                <h3>${escapeHtml(getSectionDisplayName(activeSection.name))}</h3>
                                <p>${sectionProducts.length} товаров в разделе · ${visibleCount} видимых</p>
                            </div>
                            <div class="admin-section-summary-actions">
                                <span class="admin-visibility-pill">${visibleCount}/${sectionProducts.length} видны</span>
                                <button class="admin-outline-btn" disabled>+ Добавить подкатегорию</button>
                            </div>
                        </div>
                    </div>
                ` : ""}
                <div class="admin-card admin-card-spacious">
                    <div class="admin-section-head">
                        <div>
                            <h3>Товары${activeSection ? ` — ${escapeHtml(getSectionDisplayName(activeSection.name))}` : ""}</h3>
                            <p>${products.length} позиций по текущим фильтрам</p>
                        </div>
                        <button class="admin-primary-btn" data-action="open-admin-product">+ Добавить товар</button>
                    </div>
                    <div class="admin-toolbar">
                        <label class="search-field">
                            <span>🔎</span>
                            <input type="search" data-field="admin-product-search" placeholder="Поиск по товарам" value="${escapeAttr(state.admin.catalogSearch)}">
                            <span></span>
                        </label>
                        <select class="toolbar-select" data-field="admin-product-status">
                            ${renderOptions([
                                ["ALL", "Все"],
                                ["ACTIVE", "Активен"],
                                ["HIDDEN", "Скрыт"],
                            ], state.admin.catalogStatus)}
                        </select>
                    </div>
                    <div class="admin-table-wrap">
                        <table class="admin-table">
                            <thead><tr><th>Название</th><th>Категория</th><th>Цена</th><th>Статус</th><th>Действия</th></tr></thead>
                            <tbody>
                                ${products.map(product => `
                                    <tr>
                                        <td data-label="Название">
                                            <div class="admin-product-cell">
                                                <span class="admin-product-thumb" style="background:${getProductVisual(product).palette[0]};">
                                                    <img src="${getProductVisual(product).icon}" alt="${escapeAttr(product.name)}">
                                                </span>
                                                <div>
                                                    <strong>${escapeHtml(product.name)}</strong>
                                                    <div class="search-muted">${escapeHtml(product.brand || "Без производителя")}</div>
                                                </div>
                                            </div>
                                        </td>
                                        <td data-label="Категория">${escapeHtml(getAdminCatalogChildName(product) || product.category || "—")}</td>
                                        <td data-label="Цена">
                                            <strong>${product.price == null ? "По запросу" : formatPrice(product.price)}</strong>
                                            ${product.oldPrice ? `<div class="search-muted"><s>${formatPrice(product.oldPrice)}</s></div>` : ""}
                                        </td>
                                        <td data-label="Статус">${renderAdminStatusBadge(product.active ? "ACTIVE" : "HIDDEN", product.active ? "Активен" : "Скрыт")}</td>
                                        <td data-label="Действия">
                                            <div class="admin-row-actions">
                                                <button class="admin-table-btn" data-action="open-admin-product" data-product-id="${product.id}">Ред.</button>
                                                <button class="admin-table-btn ${product.active ? "danger" : ""}" data-action="toggle-admin-product-active" data-product-id="${product.id}">
                                                    ${product.active ? "Скрыть" : "Показать"}
                                                </button>
                                            </div>
                                        </td>
                                    </tr>
                                `).join("")}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>
    `;
}

function renderAdminManufacturers() {
    return `
        <div class="admin-card admin-card-spacious">
            <div class="admin-section-head">
                <div>
                    <h3>Производители</h3>
                    <p>Всего: ${state.admin.manufacturers.length} производителей</p>
                </div>
                <button class="admin-primary-btn" data-action="open-manufacturer-modal">+ Добавить производителя</button>
            </div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead><tr><th>Название</th><th>Товаров</th><th>Действия</th></tr></thead>
                    <tbody>
                        ${state.admin.manufacturers.map(item => `
                            <tr>
                                <td data-label="Название">${escapeHtml(item.name)}</td>
                                <td data-label="Товаров">${item.productsCount} тов.</td>
                                <td data-label="Действия">
                                    <div class="admin-row-actions">
                                        <button class="admin-table-btn" data-action="edit-manufacturer" data-id="${item.id || ""}" data-name="${escapeAttr(item.name)}">Ред.</button>
                                        ${item.id ? `<button class="admin-table-btn danger" data-action="delete-manufacturer" data-id="${item.id}">Удалить</button>` : ""}
                                    </div>
                                </td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
            <div class="admin-footnote">${state.admin.manufacturers.length ? `1–${state.admin.manufacturers.length} из ${state.admin.manufacturers.length}` : "Список пуст"}</div>
        </div>
    `;
}

function renderAdminOrders() {
    const orders = getFilteredAdminOrders();
    return `
        <div class="admin-card admin-card-spacious">
            <div class="admin-section-head">
                <div>
                    <h3>Заявки</h3>
                    <p>${orders.length} записей по текущим фильтрам</p>
                </div>
            </div>
            <div class="admin-toolbar admin-toolbar-orders">
                <label class="search-field">
                    <span>🔎</span>
                    <input type="search" data-field="admin-order-search" placeholder="Поиск по имени или телефону" value="${escapeAttr(state.admin.orderFilters.search)}">
                    <span></span>
                </label>
                <select class="toolbar-select" data-field="admin-order-status">
                    ${renderOptions([
                        ["ALL", "Все"],
                        ["NEW", "В ожидании"],
                        ["IN_PROGRESS", "В работе"],
                        ["COMPLETED", "Оплачен"],
                        ["CANCELLED", "Отменён"],
                    ], state.admin.orderFilters.status)}
                </select>
                <div class="admin-form-row admin-order-dates">
                    <input type="date" data-field="admin-order-from" value="${escapeAttr(state.admin.orderFilters.from)}">
                    <input type="date" data-field="admin-order-to" value="${escapeAttr(state.admin.orderFilters.to)}">
                </div>
                <button class="admin-outline-btn" data-action="reset-admin-order-filters">Сбросить</button>
            </div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead><tr><th>Номер</th><th>Клиент</th><th>Телефон</th><th>Сумма</th><th>Дата</th><th>Статус</th><th></th></tr></thead>
                    <tbody>
                        ${orders.map(order => `
                            <tr>
                                <td data-label="Номер">${escapeHtml(order.publicCode)}</td>
                                <td data-label="Клиент">${escapeHtml(order.customerName || "")}</td>
                                <td data-label="Телефон">${escapeHtml(order.customerPhone || "")}</td>
                                <td data-label="Сумма">${formatPrice(order.totalPrice || 0)}</td>
                                <td data-label="Дата">${formatDate(order.createdAt)}</td>
                                <td data-label="Статус">${renderAdminStatusBadge(order.status, order.statusLabel)}</td>
                                <td data-label="Действие"><button class="admin-table-btn" data-action="open-admin-order" data-order-id="${order.id}">Открыть</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        </div>
    `;
}

function renderAdminCustomers() {
    const customers = getFilteredAdminCustomers();
    const withCart = customers.filter(item => item.cartItemsCount > 0).length;
    const withDraft = customers.filter(item => item.draftFilled).length;
    const withOrders = customers.filter(item => item.ordersCount > 0).length;
    return `
        <div class="admin-kpi-grid">
            ${renderAdminKpiCard("◎", state.admin.customers.length || 0, "Клиентов", "Запускали бота или мини апп")}
            ${renderAdminKpiCard("◔", withCart, "С корзиной", "Есть товары перед оформлением", "warning")}
            ${renderAdminKpiCard("✎", withDraft, "С черновиком", "Заполняли заявку, но не отправили", "accent")}
            ${renderAdminKpiCard("◩", withOrders, "С заказами", "Уже оформляли заявки", "neutral")}
        </div>
        <div class="admin-card admin-card-spacious">
            <div class="admin-section-head">
                <div>
                    <h3>Клиентская база</h3>
                    <p>${customers.length} записей по текущему поиску</p>
                </div>
            </div>
            <div class="admin-toolbar">
                <label class="search-field">
                    <span>🔎</span>
                    <input type="search" data-field="admin-customer-search" placeholder="Поиск по имени, username, телефону, ИНН" value="${escapeAttr(state.admin.customerSearch)}">
                    <span></span>
                </label>
            </div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead><tr><th>Клиент</th><th>Контакты</th><th>Корзина</th><th>Заказы</th><th>Статус</th><th>Последняя активность</th><th></th></tr></thead>
                    <tbody>
                        ${customers.map(customer => `
                            <tr>
                                <td data-label="Клиент">
                                    <div class="admin-customer-meta">
                                        <strong>${escapeHtml(customer.displayName || "Пользователь MAX")}</strong>
                                        <div class="search-muted">${customer.username ? `@${escapeHtml(customer.username)}` : `ID ${customer.maxUserId}`}</div>
                                    </div>
                                </td>
                                <td data-label="Контакты">
                                    <div class="admin-list-stack compact">
                                        <span>${escapeHtml(customer.contactPhone || "Телефон не указан")}</span>
                                        <span class="search-muted">${escapeHtml(customer.contactEmail || customer.organization || "Без доп. данных")}</span>
                                    </div>
                                </td>
                                <td data-label="Корзина">
                                    <div class="admin-list-stack compact">
                                        <strong>${customer.cartItemsCount || 0} поз.</strong>
                                        <span class="search-muted">${customer.cartUnits || 0} ед.</span>
                                    </div>
                                </td>
                                <td data-label="Заказы">
                                    <div class="admin-list-stack compact">
                                        <strong>${customer.ordersCount || 0}</strong>
                                        <span class="search-muted">Оплачено: ${customer.completedOrdersCount || 0}</span>
                                    </div>
                                </td>
                                <td data-label="Статус">
                                    <div class="admin-inline-badges">
                                        ${customer.draftFilled ? renderAdminStatusBadge("NEW", "Черновик") : ""}
                                        ${customer.cartItemsCount ? renderAdminStatusBadge("IN_PROGRESS", "Корзина") : ""}
                                        ${!customer.draftFilled && !customer.cartItemsCount ? renderAdminStatusBadge("HIDDEN", "Только просмотр") : ""}
                                    </div>
                                </td>
                                <td data-label="Последняя активность">${formatDate(customer.lastSeenAt)}</td>
                                <td data-label="Действие"><button class="admin-table-btn" data-action="open-admin-customer" data-max-user-id="${customer.maxUserId}">Открыть</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        </div>
    `;
}

function renderAdminBroadcasts() {
    const stats = state.admin.broadcasts?.stats || {};
    const history = state.admin.broadcasts?.history || [];
    return `
        <div class="admin-broadcast-layout">
            <div class="admin-card admin-card-spacious">
                <h3>Рассылка</h3>
                <div class="admin-broadcast-form">
                    <div class="field">
                        <label>Фото (необязательно)</label>
                        <label class="upload-box">
                            <input type="file" accept="image/*" data-field="broadcast-image">
                            <div class="admin-upload-icon">▣</div>
                            <div>${state.admin.broadcastForm.imageName ? escapeHtml(state.admin.broadcastForm.imageName) : "Нажмите для загрузки фото"}</div>
                            <small>JPG, PNG, WebP — необязательно</small>
                        </label>
                    </div>
                    <div class="field">
                        <label>Текст сообщения</label>
                        <textarea data-field="broadcast-text" placeholder="Текст сообщения для всех пользователей">${escapeHtml(state.admin.broadcastForm.text)}</textarea>
                    </div>
                    <div class="admin-preview-card">
                        <div class="admin-preview-title">Предпросмотр сообщения</div>
                        ${state.admin.broadcastForm.imageUrl ? `<img src="${escapeAttr(state.admin.broadcastForm.imageUrl)}" alt="" class="admin-preview-image">` : ""}
                        <div>${escapeHtml(state.admin.broadcastForm.text || "Сообщение появится здесь")}</div>
                    </div>
                    <button class="admin-primary-btn" data-action="send-broadcast" ${state.admin.broadcastForm.sending ? "disabled" : ""}>📢 Отправить всем (${stats.subscribersCount || 0} чел.)</button>
                </div>
            </div>
            <div class="admin-stack">
                <div class="admin-card admin-card-spacious">
                    <h3>Статистика</h3>
                    <div class="admin-mini-stats">
                        ${renderAdminMiniStat(stats.subscribersCount || 0, "Подписчиков")}
                        ${renderAdminMiniStat(stats.broadcastsCount || 0, "Рассылок всего", "warning")}
                        ${renderAdminMiniStat(`+${stats.newUsersThisMonth || 0}`, "Новых за месяц")}
                        ${renderAdminMiniStat(stats.lastBroadcastAt ? formatDate(stats.lastBroadcastAt) : "—", "Последняя рассылка")}
                    </div>
                </div>
                <div class="admin-card admin-card-spacious">
                    <h3>История рассылок</h3>
                    <div class="history-list admin-history-list">
                        ${history.length ? history.map(item => `
                            <div class="history-card">
                                <strong>${formatDate(item.createdAt)}</strong>
                                <div>${item.messageType === "photo" ? "Фото" : "Текст"} · ${item.recipientsCount} получателей</div>
                                <div class="search-muted">${escapeHtml(item.text)}</div>
                            </div>
                        `).join("") : `<div class="empty-box">История пока пуста.</div>`}
                    </div>
                </div>
            </div>
        </div>
    `;
}

function renderBottomNav() {
    if (isAdminWorkspaceActive()) {
        return "";
    }
    const profileLabel = state.profile?.admin ? "Админка" : "Профиль";
    return `
        <nav class="bottom-nav">
            <div class="bottom-nav-grid">
                ${renderNavButton("catalog", "🗂", "Каталог", null)}
                ${renderNavButton("cart", "🛒", "Корзина", state.cart.length || null)}
                ${renderNavButton("favorites", "♡", "Избранное", null)}
                ${renderNavButton("profile", "👤", profileLabel, null)}
            </div>
        </nav>
    `;
}

function renderNavButton(key, icon, label, badge) {
    return `
        <button class="nav-btn ${state.nav === key ? "active" : ""}" data-action="nav" data-nav="${key}">
            ${badge ? `<span class="nav-badge">${badge}</span>` : ""}
            <span class="nav-icon">${icon}</span>
            <span class="nav-label">${escapeHtml(label)}</span>
        </button>
    `;
}

function renderProductModal() {
    if (!state.productModal.open) return "";
    const product = getProductById(state.productModal.productId);
    if (!product) return "";
    const visual = getProductVisual(product);
    const sectionName = getSectionDisplayName(getProductSectionName(product));
    const subcategoryName = getAdminCatalogChildName(product);
    const modalSubtitle = [subcategoryName, product.brand].filter(Boolean).join(" · ");
    const galleryItems = [visual.icon];
    const currentImage = galleryItems[state.productModal.imageIndex] || visual.icon;
    const favorite = isFavorite(product.id);
    const canBuy = product.price != null;
    const currentQty = getCartItem(product.id)?.quantity || state.productModal.quantity;
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-product"></div>
            <div class="modal-sheet">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">${escapeHtml(product.name)}</div>
                        <div class="modal-subtitle">${escapeHtml(modalSubtitle)}</div>
                    </div>
                    <button class="modal-close" data-action="close-product">×</button>
                </div>
                <div class="product-gallery">
                    <div class="gallery-stage" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                        ${galleryItems.length > 1 ? `<button class="gallery-nav prev" data-action="gallery-prev">‹</button>` : ""}
                        <img src="${currentImage}" alt="${escapeAttr(product.name)}">
                        <button type="button" class="favorite-fab ${favorite ? "active" : ""}" data-action="toggle-favorite" data-product-id="${product.id}">${favorite ? "♥" : "♡"}</button>
                        ${galleryItems.length > 1 ? `<button class="gallery-nav next" data-action="gallery-next">›</button>` : ""}
                    </div>
                    ${galleryItems.length > 1 ? `<div class="gallery-dots">${galleryItems.map((_, index) => `<span class="gallery-dot ${index === state.productModal.imageIndex ? "active" : ""}"></span>`).join("")}</div>` : ""}
                </div>
                <div class="product-detail-body">
                    <div>
                        <div class="eyebrow">${escapeHtml(sectionName || product.category || "Каталог")}</div>
                        <h3>${escapeHtml(product.name)}</h3>
                        <div class="product-spec-list">
                            ${product.activeIngredient ? `
                                <div class="product-spec-item">
                                    <span>ДВ</span>
                                    <strong>${escapeHtml(product.activeIngredient)}</strong>
                                </div>
                            ` : ""}
                            <div class="product-spec-item">
                                <span>Упаковка</span>
                                    <strong>${escapeHtml(formatCompactPackageDisplay(product) || product.packageDescription || product.packageType || product.unitName || "Не указана")}</strong>
                            </div>
                        </div>
                        ${subcategoryName ? `<div class="product-brand">${escapeHtml(subcategoryName)}</div>` : ""}
                        <div class="product-brand">${escapeHtml(product.brand || "")}</div>
                    </div>
                    <div class="detail-price">
                        ${product.oldPrice ? `<div class="old-price">${formatPrice(product.oldPrice)}</div>` : ""}
                        <strong class="${product.oldPrice ? "price-current-discount" : ""}">${product.price == null ? "По запросу" : formatPrice(product.price)}</strong>
                    </div>
                    <div class="packaging-chips">
                        <span class="packaging-chip">${escapeHtml(formatCompactPackageDisplay(product) || product.packageDescription || product.packageType || product.unitName || "Упаковка не указана")}</span>
                    </div>
                    ${product.description ? `<p class="detail-paragraph">${escapeHtml(product.description)}</p>` : ""}
                    ${canBuy ? `
                        <div class="detail-footer">
                            ${renderStepper(product.id, currentQty, "modal")}
                            <button class="primary-btn" data-action="confirm-product-cart" data-product-id="${product.id}">
                                ${getCartItem(product.id) ? "✓ В корзине" : "В корзину"}
                            </button>
                        </div>
                    ` : `<button class="secondary-btn" data-action="open-manager">Связаться с менеджером</button>`}
                </div>
            </div>
        </div>
    `;
}

function renderFiltersDrawer() {
    if (!state.catalog.filtersOpen) return "";
    return `
        <div class="drawer">
            <div class="drawer-backdrop" data-action="close-filters"></div>
            <div class="drawer-sheet">
                <div class="drawer-top">
                    <div class="modal-title">Фильтры</div>
                    <button class="secondary-btn" data-action="reset-filters">Сбросить всё</button>
                </div>
                ${renderCatalogFiltersPanel({ inline: false })}
                <div class="drawer-actions">
                    <button class="ghost-btn" data-action="close-filters">Сбросить</button>
                    <button class="primary-btn" data-action="apply-filters">Применить</button>
                </div>
            </div>
        </div>
    `;
}

function renderInlineFiltersSidebar() {
    return `
        <aside class="catalog-filters-sidebar">
            <div class="catalog-filters-card">
                <div class="catalog-filters-head">
                    <div class="catalog-filters-title">Фильтры</div>
                    <button class="catalog-reset-link" data-action="reset-inline-filters">Сбросить</button>
                </div>
                ${renderCatalogFiltersPanel({ inline: true })}
            </div>
        </aside>
    `;
}

function renderCatalogFiltersPanel({ inline = false } = {}) {
    const draft = state.catalog.draft || cloneFilters(state.catalog.applied);
    const panels = state.catalog.filterPanels || emptyFilterPanels();
    const sectionProducts = getDraftSectionProducts(draft);
    const effectiveSection = draft.sections[0] || state.catalog.section || "";
    const manufacturers = uniqueValues(sectionProducts.map(item => item.brand).filter(Boolean));
    const cultures = uniqueValues(sectionProducts.flatMap(item => item.cultures || []).filter(Boolean));
    const cultureKeys = new Set(cultures.map(normalize));
    const subcategories = uniqueValues(sectionProducts.flatMap(item => getFilterSubcategoryValues(item, effectiveSection)).filter(Boolean))
        .filter(name => !cultureKeys.has(normalize(name)));
    const subcategoryTitle = getSubcategoryFilterTitle(effectiveSection);
    const isSeedsSection = normalize(effectiveSection) === normalize("Семена");
    const isPesticidesSection = normalize(effectiveSection) === normalize("Пестициды");
    const activeIngredients = uniqueValues(sectionProducts.map(item => item.activeIngredient || item.filterMap?.activeIngredient).filter(Boolean));
    const seedFaoRanges = SEED_FAO_RANGES.map(item => item.label);
    const seedMaturityGroups = SEED_MATURITY_GROUPS;
    const seedTechnologies = SEED_TREATMENT_TECHNOLOGIES;
    const wrapperClass = inline ? "catalog-filters-panel inline" : "catalog-filters-panel";
    return `
        <div class="${wrapperClass}">
            <div class="drawer-section" data-filter-anchor="culture">
                <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="culture">
                    <span>Культура</span>
                    <span class="drawer-section-arrow">${panels.culture ? "▾" : "▸"}</span>
                </button>
                ${panels.culture ? `
                    <div class="checkbox-list">
                        ${cultures.map(name => `
                            <label class="checkbox-row">
                                <input type="checkbox" data-field="filter-culture" value="${escapeAttr(name)}" ${draft.cultures.includes(name) ? "checked" : ""}>
                                <span>${escapeHtml(name)}</span>
                            </label>
                        `).join("")}
                    </div>
                ` : ""}
            </div>
            <div class="drawer-section" data-filter-anchor="manufacturer">
                <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="manufacturer">
                    <span>Производитель</span>
                    <span class="drawer-section-arrow">${panels.manufacturer ? "▾" : "▸"}</span>
                </button>
                ${panels.manufacturer ? `
                    <div class="checkbox-list">
                        ${manufacturers.map(name => `
                            <label class="checkbox-row">
                                <input type="checkbox" data-field="filter-manufacturer" value="${escapeAttr(name)}" ${draft.manufacturers.includes(name) ? "checked" : ""}>
                                <span>${escapeHtml(name)}</span>
                            </label>
                        `).join("")}
                    </div>
                ` : ""}
            </div>
            ${isPesticidesSection ? `
                <div class="drawer-section" data-filter-anchor="active-ingredient">
                    <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="activeIngredient">
                        <span>Действующее вещество</span>
                        <span class="drawer-section-arrow">${panels.activeIngredient ? "▾" : "▸"}</span>
                    </button>
                    ${panels.activeIngredient ? `
                        <div class="checkbox-list">
                            ${activeIngredients.map(name => `
                                <label class="checkbox-row">
                                    <input type="checkbox" data-field="filter-active-ingredient" value="${escapeAttr(name)}" ${draft.activeIngredients.includes(name) ? "checked" : ""}>
                                    <span>${escapeHtml(name)}</span>
                                </label>
                            `).join("")}
                        </div>
                    ` : ""}
                </div>
            ` : ""}
            ${subcategoryTitle !== "Культура" ? `
                <div class="drawer-section" data-filter-anchor="subcategory">
                    <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="subcategory">
                        <span>${escapeHtml(subcategoryTitle)}</span>
                        <span class="drawer-section-arrow">${panels.subcategory ? "▾" : "▸"}</span>
                    </button>
                    ${panels.subcategory ? `
                        <div class="checkbox-list">
                            ${subcategories.map(name => `
                                <label class="checkbox-row">
                                    <input type="checkbox" data-field="filter-subcategory" value="${escapeAttr(name)}" ${draft.subcategories.includes(name) ? "checked" : ""}>
                                    <span>${escapeHtml(name)}</span>
                                </label>
                            `).join("")}
                        </div>
                    ` : ""}
                </div>
            ` : ""}
            ${isSeedsSection ? `
                <div class="drawer-section" data-filter-anchor="fao">
                    <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="fao">
                        <span>ФАО</span>
                        <span class="drawer-section-arrow">${panels.fao ? "▾" : "▸"}</span>
                    </button>
                    ${panels.fao ? `
                        <div class="checkbox-list">
                            ${seedFaoRanges.map(name => `
                                <label class="checkbox-row">
                                    <input type="checkbox" data-field="filter-seed-fao" value="${escapeAttr(name)}" ${draft.seedFaoRanges.includes(name) ? "checked" : ""}>
                                    <span>${escapeHtml(name)}</span>
                                </label>
                            `).join("")}
                        </div>
                    ` : ""}
                </div>
                <div class="drawer-section" data-filter-anchor="maturity">
                    <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="maturity">
                        <span>Группа спелости</span>
                        <span class="drawer-section-arrow">${panels.maturity ? "▾" : "▸"}</span>
                    </button>
                    ${panels.maturity ? `
                        <div class="checkbox-list">
                            ${seedMaturityGroups.map(name => `
                                <label class="checkbox-row">
                                    <input type="checkbox" data-field="filter-seed-maturity" value="${escapeAttr(name)}" ${draft.seedMaturityGroups.includes(name) ? "checked" : ""}>
                                    <span>${escapeHtml(name)}</span>
                                </label>
                            `).join("")}
                        </div>
                    ` : ""}
                </div>
                <div class="drawer-section" data-filter-anchor="technology">
                    <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="technology">
                        <span>Технология обработки</span>
                        <span class="drawer-section-arrow">${panels.technology ? "▾" : "▸"}</span>
                    </button>
                    ${panels.technology ? `
                        <div class="checkbox-list">
                            ${seedTechnologies.map(name => `
                                <label class="checkbox-row">
                                    <input type="checkbox" data-field="filter-seed-technology" value="${escapeAttr(name)}" ${draft.seedTreatmentTechnologies.includes(name) ? "checked" : ""}>
                                    <span>${escapeHtml(name)}</span>
                                </label>
                            `).join("")}
                        </div>
                    ` : ""}
                </div>
            ` : ""}
            <div class="drawer-section" data-filter-anchor="more">
                <button class="drawer-section-toggle" data-action="toggle-filter-panel" data-panel="more">
                    <span>Цена</span>
                    <span class="drawer-section-arrow">${panels.more ? "▾" : "▸"}</span>
                </button>
                ${panels.more ? `
                    <div class="price-range">
                        <input type="number" data-field="filter-price-min" value="${escapeAttr(draft.priceMin)}" placeholder="от">
                        <span>—</span>
                        <input type="number" data-field="filter-price-max" value="${escapeAttr(draft.priceMax)}" placeholder="до">
                    </div>
                ` : ""}
            </div>
        </div>
    `;
}

function renderDatalist(id, options) {
    const values = uniqueValues(options.filter(Boolean));
    if (!values.length) {
        return "";
    }
    return `
        <datalist id="${escapeAttr(id)}">
            ${values.map(value => `<option value="${escapeAttr(value)}"></option>`).join("")}
        </datalist>
    `;
}

function renderAdminSuggestionBox(field) {
    return `<div class="admin-inline-suggestions" data-suggestions-for="${escapeAttr(field)}"></div>`;
}

function getAdminPrimarySections() {
    return FIXED_SECTION_DEFINITIONS.map(section => section.name);
}

function getAdminCatalogChildName(product) {
    const sectionName = getProductSectionName(product);
    const mapped = mapProductToSectionSubcategory(product, sectionName);
    return mapped || "Без категории";
}

function getAdminSubcategoryOptions(sectionName) {
    const definition = getSectionDefinition(sectionName);
    return [...(definition?.subcategories || [])];
}

function getAdminBrandOptions() {
    return uniqueValues([
        ...(state.admin.manufacturers || []).map(item => item.name),
        ...(state.admin.products || []).map(item => item.brand),
    ].filter(Boolean));
}

function getAdminFieldOptions(field) {
    if (field === "activeIngredient") {
        return uniqueValues((state.admin.products || []).map(item => item.activeIngredient || item.filterMap?.activeIngredient).filter(Boolean));
    }
    if (field === "cultures") {
        return uniqueValues((state.admin.products || []).flatMap(item => item.cultures || []).filter(Boolean));
    }
    if (field === "tags") {
        return uniqueValues((state.admin.products || []).flatMap(item => [
            ...(item.tags || []),
            ...(item.purposes || []),
        ]).filter(Boolean));
    }
    if (field === "packageDescription") {
        return uniqueValues((state.admin.products || []).map(item => item.packageDescription).filter(Boolean));
    }
    if (field === "packageVolume") {
        return uniqueValues((state.admin.products || [])
            .map(item => inferAdminOrderConfig(item).packageVolume)
            .filter(Boolean));
    }
    if (field === "unitsPerPackage") {
        return uniqueValues((state.admin.products || [])
            .map(item => inferAdminOrderConfig(item).unitsPerPackage)
            .filter(Boolean));
    }
    return [];
}

function inferAdminOrderPreset(product) {
    const packageType = normalize(product?.packageType);
    const unitName = normalize(product?.unitName);
    if (unitName.includes("п.е")) return "pe";
    if (unitName === "т" || unitName.includes("тон")) return "ton";
    if (unitName === "кг" || unitName.includes("кил")) return "kg";
    if (unitName === "л" || unitName.includes("лит")) return "liters";
    if (packageType.includes("короб")) return "box";
    if (packageType.includes("канистр")) return "canister";
    if (packageType.includes("меш")) return "bag";
    return "manual";
}

function getAdminOrderMode(product) {
    const preset = inferAdminOrderPreset(product);
    if (preset === "kg") return "kg";
    if (preset === "liters") return "liters";
    if (preset === "pe") return "pe";
    if (preset === "ton") return "ton";
    return "liters";
}

function inferAdminOrderConfig(product) {
    const preset = inferAdminOrderPreset(product);
    const description = String(product?.packageDescription || "").trim();
    const step = Number(product?.orderStep || 1);
    const unitName = String(product?.unitName || "л").trim();
    let packageVolume = "";
    let unitsPerPackage = "";
    const boxLike = description.match(/(\d+(?:[.,]\d+)?)\s*(?:x|х)\s*(\d+(?:[.,]\d+)?)/i)
        || description.match(/(\d+(?:[.,]\d+)?)\s*(?:шт|упаков(?:ки|ок)|канистр)[^\d]{0,12}(?:по\s*)?(\d+(?:[.,]\d+)?)/i);
    const singleLike = description.match(/(\d+(?:[.,]\d+)?)\s*(л|лит|кг|кил|т)\b/i);
    if (boxLike) {
        unitsPerPackage = String(boxLike[1]).replace(",", ".");
        packageVolume = String(boxLike[2]).replace(",", ".");
    } else if (singleLike) {
        packageVolume = String(singleLike[1]).replace(",", ".");
        unitsPerPackage = "1";
    } else if (step > 0) {
        packageVolume = String(step).replace(",", ".");
        unitsPerPackage = "1";
    }
    if (preset === "pe" || preset === "ton") {
        packageVolume = "1";
        unitsPerPackage = "1";
    }
    return {
        preset,
        unitName,
        packageVolume,
        unitsPerPackage,
    };
}

function renderAdminProductModal() {
    if (!state.admin.productEditor.open) return "";
    const product = state.admin.productEditor.productId ? state.admin.products.find(item => item.id === state.admin.productEditor.productId) : null;
    const categories = getAdminPrimarySections();
    const selectedCategory = state.admin.productEditor.categoryDraft || (product ? getProductSectionName(product) : (state.admin.catalogSection || categories[0] || ""));
    const selectedSubcategory = product ? getAdminCatalogChildName(product) : (state.admin.catalogCategory || "");
    const subcategoryOptions = getAdminSubcategoryOptions(selectedCategory);
    const brandOptions = getAdminBrandOptions();
    const activeIngredientOptions = getAdminFieldOptions("activeIngredient");
    const cultureOptions = getAdminFieldOptions("cultures");
    const tagOptions = getAdminFieldOptions("tags");
    const packageDescriptionOptions = getAdminFieldOptions("packageDescription");
    const packageVolumeOptions = getAdminFieldOptions("packageVolume");
    const unitsPerPackageOptions = getAdminFieldOptions("unitsPerPackage");
    const orderMode = getAdminOrderMode(product);
    const orderConfig = inferAdminOrderConfig(product);
    const packageDescriptionValue = selectedCategory === "Пестициды"
        ? (formatCompactPackageDisplay(product) || product?.packageDescription || "")
        : (product?.packageDescription || "");
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-admin-product"></div>
            <div class="modal-sheet modal-sheet-wide modal-sheet-admin-product">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">${product ? "Редактирование товара" : "Добавить товар"}</div>
                        <div class="modal-subtitle">${product ? escapeHtml(product.name) : "Новая позиция каталога"}</div>
                    </div>
                    <button class="modal-close" data-action="close-admin-product">×</button>
                </div>
                <form class="admin-form-grid admin-product-form" data-form="admin-product">
                    <input type="hidden" name="productId" value="${product?.id || ""}">
                    ${renderDatalist("admin-subcategory-options", subcategoryOptions)}
                    ${renderDatalist("admin-brand-options", brandOptions)}
                    ${renderDatalist("admin-active-ingredient-options", activeIngredientOptions)}
                    ${renderDatalist("admin-culture-options", cultureOptions)}
                    ${renderDatalist("admin-tag-options", tagOptions)}
                    ${renderDatalist("admin-package-description-options", packageDescriptionOptions)}
                    ${renderDatalist("admin-package-volume-options", packageVolumeOptions)}
                    ${renderDatalist("admin-units-per-package-options", unitsPerPackageOptions)}
                    <div class="admin-form-section admin-form-section-product">
                        <div class="admin-form-section-title">Карточка товара</div>
                        <div class="admin-form-row admin-form-row-3">
                            <div class="admin-field admin-field-span-2"><label>Название</label><input name="name" required value="${escapeAttr(product?.name || "")}"></div>
                            <div class="admin-field"><label>Показывать</label><select name="active">${renderOptions([["true", "Да"], ["false", "Нет"]], String(product?.active ?? true))}</select></div>
                        </div>
                        <div class="admin-form-row admin-form-row-3">
                            <div class="admin-field"><label>Раздел</label><select name="category" data-field="admin-product-category">${renderOptions(categories.map(item => [item, getSectionDisplayName(item)]), selectedCategory)}</select></div>
                            <div class="admin-field"><label>Подкатегория</label><select name="subcategory">${renderOptions(subcategoryOptions.map(item => [item, item]), selectedSubcategory || subcategoryOptions[0] || "")}</select></div>
                            <div class="admin-field"><label>Производитель</label><input name="brand" list="admin-brand-options" data-field="admin-product-brand" data-options-id="admin-brand-options" data-suggest-mode="single" required value="${escapeAttr(product?.brand || "")}" placeholder="Выберите или введите нового">${renderAdminSuggestionBox("admin-product-brand")}</div>
                        </div>
                        <div class="admin-form-row admin-form-row-3">
                            <div class="admin-field"><label>Единица заказа</label><select name="orderMode">${renderOptions([["liters", "Литры"], ["kg", "Килограммы"], ["pe", "П.е."], ["ton", "Тонны"]], orderMode)}</select></div>
                            <div class="admin-field"><label>Объем упаковки</label><input name="packageVolume" type="number" min="0.001" step="0.001" list="admin-package-volume-options" value="${escapeAttr(orderConfig.packageVolume || "")}" placeholder="10"></div>
                            <div class="admin-field"><label>Упаковок в коробке</label><input name="unitsPerPackage" type="number" min="1" step="1" list="admin-units-per-package-options" value="${escapeAttr(orderConfig.unitsPerPackage || "")}" placeholder="2"></div>
                        </div>
                        <div class="admin-form-row admin-form-row-2-compact">
                            <div class="admin-field"><label>Остаток</label><input name="stockQuantity" type="number" min="0" step="0.001" value="${escapeAttr(product?.stockQuantity ?? "")}"></div>
                            <div class="admin-field"><label>Фасовка</label><input name="packageDescription" list="admin-package-description-options" data-field="admin-product-package-description" data-options-id="admin-package-description-options" data-suggest-mode="single" value="${escapeAttr(packageDescriptionValue)}" placeholder="${selectedCategory === "Пестициды" ? "25x4" : "Коробка 2 × 10 л"}">${renderAdminSuggestionBox("admin-product-package-description")}</div>
                        </div>
                    </div>
                    <div class="admin-form-section admin-form-section-order">
                        <div class="admin-form-section-title">Цена и заказ</div>
                        <div class="admin-form-row admin-form-row-3">
                            <div class="admin-field"><label>Цена</label><input name="price" type="number" min="0" step="0.01" value="${escapeAttr(product?.price ?? "")}" placeholder="2239"></div>
                            <div class="admin-field"><label>Скидка, %</label><input name="discountPercent" type="number" min="0" max="100" step="0.01" value="${escapeAttr(product?.discountPercent ?? "")}" placeholder="10"></div>
                        </div>
                        <div class="admin-form-row admin-form-row-2-compact">
                            <div class="admin-field"><label>Мин. объем заказа</label><input name="minOrderQuantity" type="number" min="0.001" step="0.001" required value="${escapeAttr(product?.minOrderQuantity ?? 1)}"></div>
                            <div class="admin-field"><label>Кратность</label><input name="orderStep" type="number" min="0.001" step="0.001" value="${escapeAttr(product?.orderStep ?? 1)}"></div>
                        </div>
                    </div>
                    <div class="admin-form-section admin-form-section-meta">
                        <div class="admin-form-section-title">Дополнительно</div>
                        <div class="admin-form-row admin-form-row-4 admin-form-row-compact">
                            <div class="admin-field admin-field-span-2"><label>Описание</label><textarea name="description" rows="2">${escapeHtml(product?.description || "")}</textarea></div>
                            <div class="admin-field"><label>Действующее вещество</label><input name="activeIngredient" list="admin-active-ingredient-options" data-field="admin-product-active-ingredient" data-options-id="admin-active-ingredient-options" data-suggest-mode="single" value="${escapeAttr(product?.activeIngredient || "")}" placeholder="Выберите или введите">${renderAdminSuggestionBox("admin-product-active-ingredient")}</div>
                            <div class="admin-field"><label>Культуры</label><input name="cultures" list="admin-culture-options" data-field="admin-product-cultures" data-options-id="admin-culture-options" data-suggest-mode="multi" value="${escapeAttr((product?.cultures || []).join(", "))}" placeholder="Выберите или введите">${renderAdminSuggestionBox("admin-product-cultures")}</div>
                        </div>
                        <div class="admin-form-row">
                            <div class="admin-field"><label>Теги / назначение</label><input name="tags" list="admin-tag-options" data-field="admin-product-tags" data-options-id="admin-tag-options" data-suggest-mode="multi" value="${escapeAttr((product?.tags || []).join(", "))}" placeholder="Выберите или введите">${renderAdminSuggestionBox("admin-product-tags")}</div>
                        </div>
                    </div>
                    <div class="admin-actions">
                        ${product ? `<button type="button" class="ghost-btn ${product.active ? "danger" : ""}" data-action="toggle-admin-product-active" data-product-id="${product.id}">${product.active ? "Скрыть" : "Показать"}</button>` : ""}
                        ${product ? `<button type="button" class="ghost-btn" data-action="delete-admin-product" data-product-id="${product.id}">Удалить</button>` : ""}
                        <button type="button" class="ghost-btn" data-action="close-admin-product">Отмена</button>
                        <button type="submit" class="primary-btn">Сохранить</button>
                    </div>
                </form>
            </div>
        </div>
    `;
}

function renderAdminManufacturerModal() {
    if (!state.admin.manufacturerModal.open) return "";
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-manufacturer-modal"></div>
            <div class="modal-sheet modal-sheet-compact">
                <div class="modal-head">
                    <div class="modal-title-wrap"><div class="modal-title">${state.admin.manufacturerModal.id ? "Редактировать производителя" : "Добавить производителя"}</div></div>
                    <button class="modal-close" data-action="close-manufacturer-modal">×</button>
                </div>
                <form class="admin-form-grid" data-form="manufacturer">
                    <input type="hidden" name="id" value="${state.admin.manufacturerModal.id || ""}">
                    <div class="admin-field">
                        <label>Название</label>
                        <input name="name" required value="${escapeAttr(state.admin.manufacturerModal.name || "")}">
                    </div>
                    <div class="admin-actions">
                        <button type="button" class="ghost-btn" data-action="close-manufacturer-modal">Отмена</button>
                        <button type="submit" class="primary-btn">Сохранить</button>
                    </div>
                </form>
            </div>
        </div>
    `;
}

function renderAdminOrderModal() {
    if (!state.admin.orderModal.open) return "";
    const order = state.admin.orders.find(item => item.id === state.admin.orderModal.orderId);
    if (!order) return "";
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-admin-order"></div>
            <div class="modal-sheet modal-sheet-wide">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">Заявка ${escapeHtml(order.publicCode)}</div>
                        <div class="modal-subtitle">${escapeHtml(order.statusLabel)}</div>
                    </div>
                    <button class="modal-close" data-action="close-admin-order">×</button>
                </div>
                <div class="admin-order-modal-body">
                    <div class="admin-order-grid">
                        <div class="summary-card">
                            <div class="admin-block-title">Клиент</div>
                            <div class="summary-row"><span>Организация / ФИО</span><strong>${escapeHtml(order.customerName || "")}</strong></div>
                            <div class="summary-row"><span>Хозяйство</span><span>${escapeHtml(order.customerFarmName || "—")}</span></div>
                            <div class="summary-row"><span>ИНН</span><span>${escapeHtml(order.customerInn || "—")}</span></div>
                            <div class="summary-row"><span>Телефон</span><span>${escapeHtml(order.customerPhone || "")}</span></div>
                            <div class="summary-row"><span>Email</span><span>${escapeHtml(order.customerEmail || "—")}</span></div>
                            <div class="summary-row"><span>Адрес</span><span>${escapeHtml(order.deliveryAddress || "—")}</span></div>
                            <div class="summary-row"><span>Комментарий</span><span>${escapeHtml(order.comment || "—")}</span></div>
                        </div>
                        <div class="summary-card">
                            <div class="admin-block-title">Состав и сумма</div>
                            ${order.items.map(item => `<div class="summary-row"><span>${escapeHtml(item.productName)} × ${formatQuantity(item.quantity)} ${escapeHtml(item.unitName || "ед.")}</span><strong>${formatPrice((item.unitPrice || 0) * item.quantity)}</strong></div>`).join("")}
                            <div class="summary-row"><span>Дата</span><strong>${formatDate(order.createdAt)}</strong></div>
                            <div class="summary-row"><span>Статус</span>${renderAdminStatusBadge(order.status, order.statusLabel)}</div>
                            <div class="summary-row"><strong>Итого</strong><strong>${formatPrice(order.totalPrice || 0)}</strong></div>
                        </div>
                    </div>
                    ${(order.attachments || []).length ? `
                        <div class="summary-card admin-attachments-card">
                            <strong>Прикреплённые реквизиты</strong>
                            ${(order.attachments || []).map(file => `
                                <a class="link-tile" href="${escapeAttr(file.downloadUrl)}" target="_blank">
                                    <div class="link-tile-text">
                                        <strong>${escapeHtml(file.originalName || file.storedName)}</strong>
                                        <p>Реквизиты клиента</p>
                                    </div>
                                    <span class="admin-table-btn">Скачать</span>
                                </a>
                            `).join("")}
                        </div>
                    ` : ""}
                    <div class="admin-actions admin-actions-panel">
                        ${renderOrderStatusActions(order)}
                    </div>
                </div>
            </div>
        </div>
    `;
}

function renderOrderStatusActions(order) {
    if (order.status === "NEW") {
        return `
            <button class="ghost-btn danger" data-action="set-order-status" data-order-id="${order.id}" data-status="CANCELLED">Отменить заявку</button>
            <button class="primary-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="IN_PROGRESS">Принять → В работе</button>
        `;
    }
    if (order.status === "IN_PROGRESS") {
        return `
            <button class="ghost-btn danger" data-action="set-order-status" data-order-id="${order.id}" data-status="CANCELLED">Отменить заявку</button>
            <button class="primary-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="COMPLETED">Отметить как оплачен</button>
        `;
    }
    return `<button class="secondary-btn" disabled>Заявка завершена</button>`;
}

function renderAdminCustomerModal() {
    if (!state.admin.customerModal.open) return "";
    const customer = state.admin.customers.find(item => String(item.maxUserId) === String(state.admin.customerModal.maxUserId));
    if (!customer) return "";
    const draft = customer.draftCheckout || {};
    const attachments = Array.isArray(draft.attachments) ? draft.attachments : [];
    const recentOrders = Array.isArray(customer.orders) ? customer.orders : [];
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-admin-customer"></div>
            <div class="modal-sheet modal-sheet-wide">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">${escapeHtml(customer.displayName || "Пользователь MAX")}</div>
                        <div class="modal-subtitle">${customer.username ? `@${escapeHtml(customer.username)}` : `MAX ID ${customer.maxUserId}`}</div>
                    </div>
                    <button class="modal-close" data-action="close-admin-customer">×</button>
                </div>
                <div class="admin-order-modal-body">
                    <div class="admin-inline-badges">
                        ${renderAdminStatusBadge("INFO", `Последняя активность: ${formatDate(customer.lastSeenAt)}`)}
                        ${customer.draftFilled ? renderAdminStatusBadge("NEW", "Есть черновик заявки") : renderAdminStatusBadge("HIDDEN", "Черновика нет")}
                        ${customer.cartItemsCount ? renderAdminStatusBadge("IN_PROGRESS", `Корзина: ${customer.cartItemsCount} поз.`) : renderAdminStatusBadge("HIDDEN", "Корзина пуста")}
                    </div>
                    <div class="admin-customer-grid">
                        <div class="summary-card">
                            <div class="admin-block-title">Профиль клиента</div>
                            <div class="summary-row"><span>Имя в MAX</span><strong>${escapeHtml(customer.displayName || "Пользователь MAX")}</strong></div>
                            <div class="summary-row"><span>Username</span><span>${escapeHtml(customer.username || "—")}</span></div>
                            <div class="summary-row"><span>Телефон</span><span>${escapeHtml(customer.contactPhone || "—")}</span></div>
                            <div class="summary-row"><span>Email</span><span>${escapeHtml(customer.contactEmail || "—")}</span></div>
                            <div class="summary-row"><span>Организация / ФИО</span><span>${escapeHtml(customer.organization || "—")}</span></div>
                            <div class="summary-row"><span>Хозяйство</span><span>${escapeHtml(customer.farmName || "—")}</span></div>
                            <div class="summary-row"><span>ИНН</span><span>${escapeHtml(customer.inn || "—")}</span></div>
                            <div class="summary-row"><span>Адрес</span><span>${escapeHtml(customer.deliveryAddress || "—")}</span></div>
                        </div>
                        <div class="summary-card">
                            <div class="admin-block-title">Корзина и заказы</div>
                            <div class="summary-row"><span>Позиции в корзине</span><strong>${customer.cartItemsCount || 0}</strong></div>
                            <div class="summary-row"><span>Единиц в корзине</span><strong>${customer.cartUnits || 0}</strong></div>
                            <div class="summary-row"><span>Примерная сумма</span><strong>${formatCustomerCartTotal(customer)}</strong></div>
                            <div class="summary-row"><span>Всего заказов</span><strong>${customer.ordersCount || 0}</strong></div>
                            <div class="summary-row"><span>Оплаченных</span><strong>${customer.completedOrdersCount || 0}</strong></div>
                            <div class="summary-row"><span>Последний заказ</span><span>${escapeHtml(customer.latestOrderCode || "—")}</span></div>
                        </div>
                    </div>
                    <div class="admin-customer-grid">
                        <div class="summary-card">
                            <div class="admin-block-title">Черновик заявки</div>
                            <div class="admin-list-stack compact">
                                <span><strong>Организация:</strong> ${escapeHtml(draft.organization || "—")}</span>
                                <span><strong>Хозяйство:</strong> ${escapeHtml(draft.farmName || "—")}</span>
                                <span><strong>ИНН:</strong> ${escapeHtml(draft.inn || "—")}</span>
                                <span><strong>Телефон:</strong> ${escapeHtml(draft.phone || "—")}</span>
                                <span><strong>Email:</strong> ${escapeHtml(draft.email || "—")}</span>
                                <span><strong>Адрес:</strong> ${escapeHtml(draft.address || "—")}</span>
                                <span><strong>Комментарий:</strong> ${escapeHtml(draft.comment || "—")}</span>
                            </div>
                            ${attachments.length ? `
                                <div class="admin-customer-files">
                                    ${attachments.map(file => `
                                        <a class="link-tile" href="${escapeAttr(file.downloadUrl)}" target="_blank">
                                            <div class="link-tile-text">
                                                <strong>${escapeHtml(file.originalName || file.storedName || "Файл")}</strong>
                                                <p>Черновик клиента</p>
                                            </div>
                                            <span class="admin-table-btn">Скачать</span>
                                        </a>
                                    `).join("")}
                                </div>
                            ` : `<div class="search-muted">Реквизиты в черновик не прикреплялись.</div>`}
                        </div>
                        <div class="summary-card">
                            <div class="admin-block-title">Корзина клиента</div>
                            ${customer.cartItems?.length ? `
                                <div class="admin-customer-cart-list">
                                    ${customer.cartItems.map(item => `
                                        <div class="summary-row">
                                            <span>${escapeHtml(item.name)} × ${formatQuantity(item.quantity)} ${escapeHtml(item.unitName || "")}</span>
                                            <strong>${item.priceOnRequest ? "По запросу" : formatPrice(item.totalPrice || 0)}</strong>
                                        </div>
                                    `).join("")}
                                </div>
                            ` : `<div class="search-muted">Корзина пуста.</div>`}
                        </div>
                    </div>
                    <div class="summary-card">
                        <div class="admin-block-title">Последние заявки</div>
                        ${recentOrders.length ? `
                            <div class="admin-customer-order-list">
                                ${recentOrders.map(order => `
                                    <div class="admin-customer-order-row">
                                        <div>
                                            <strong>${escapeHtml(order.publicCode)}</strong>
                                            <div class="search-muted">${escapeHtml(summarizeOrderItems(order.items || []))}</div>
                                        </div>
                                        <div class="admin-list-stack compact end">
                                            ${renderAdminStatusBadge(order.status, order.statusLabel)}
                                            <span class="search-muted">${formatDate(order.createdAt)}</span>
                                        </div>
                                    </div>
                                `).join("")}
                            </div>
                        ` : `<div class="search-muted">Пока нет оформленных заказов.</div>`}
                    </div>
                </div>
            </div>
        </div>
    `;
}

function renderAdminKpiCard(icon, value, label, hint, tone = "") {
    return `
        <article class="admin-kpi-card ${tone ? `tone-${tone}` : ""}">
            <span class="admin-kpi-icon">${icon}</span>
            <strong>${escapeHtml(value)}</strong>
            <span>${escapeHtml(label)}</span>
            <small>${escapeHtml(hint || "")}</small>
        </article>
    `;
}

function renderAdminMiniStat(value, label, tone = "") {
    return `
        <div class="admin-mini-stat ${tone ? `tone-${tone}` : ""}">
            <strong>${escapeHtml(value)}</strong>
            <span>${escapeHtml(label)}</span>
        </div>
    `;
}

function renderAdminStatusBadge(status, label) {
    const normalized = String(status || "").toUpperCase();
    const tone = {
        NEW: "warning",
        INFO: "info",
        IN_PROGRESS: "info",
        COMPLETED: "success",
        CANCELLED: "danger",
        ACTIVE: "success",
        HIDDEN: "muted",
    }[normalized] || "muted";
    return `<span class="admin-status admin-status-${tone}">${escapeHtml(label || profileStatusLabel(normalized))}</span>`;
}

function summarizeOrderItems(items) {
    const safeItems = Array.isArray(items) ? items : [];
    if (!safeItems.length) {
        return "—";
    }
    const preview = safeItems.slice(0, 2).map(item => `${item.productName} × ${formatQuantity(item.quantity)} ${item.unitName || "ед."}`);
    return safeItems.length > 2 ? `${preview.join(", ")} + ещё ${safeItems.length - 2}` : preview.join(", ");
}

function formatCustomerCartTotal(customer) {
    const total = Number(customer?.cartTotal || 0);
    if (!customer?.cartItemsCount) {
        return "—";
    }
    if (customer?.cartContainsRequestPrice && total <= 0) {
        return "По запросу";
    }
    return `${customer?.cartContainsRequestPrice ? "~" : ""}${formatPrice(total)}`;
}

function isAdminWorkspaceActive() {
    return state.nav === "profile" && Boolean(state.profile?.admin);
}

function handleClick(event) {
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const { action } = button.dataset;
    if (action === "pick-admin-suggestion") {
        applyAdminSuggestion(button.dataset.field, button.dataset.value || "");
        return;
    }
    if (action === "nav") {
        state.nav = button.dataset.nav;
        state.catalog.query = "";
        if (state.nav === "profile") {
            loadProfileOrders().catch(error => console.warn("Profile orders preload failed", error));
            ensureAdminDataLoaded().catch(error => console.warn("Admin preload failed", error));
        }
        render();
        return;
    }
    if (action === "open-section") {
        state.catalog.section = button.dataset.section;
        state.catalog.applied = emptyFilters();
        state.catalog.draft = cloneFilters(state.catalog.applied);
        state.catalog.filterPanels = defaultFilterPanels();
        state.catalog.filterFocus = "";
        render();
        return;
    }
    if (action === "back") {
        if (state.productModal.open) {
            closeProductModal();
        } else if (state.checkout.open) {
            state.checkout.open = false;
            state.successOrderCode = "";
        } else if (state.catalog.section) {
            state.catalog.section = "";
            state.catalog.applied = emptyFilters();
            state.catalog.filtersOpen = false;
        } else {
            state.nav = "catalog";
        }
        render();
        return;
    }
    if (action === "clear-search") {
        state.catalog.query = "";
        render();
        return;
    }
    if (action === "open-product") {
        state.productModal = { open: true, productId: Number(button.dataset.productId), quantity: 1, imageIndex: 0 };
        render();
        return;
    }
    if (action === "close-product") {
        closeProductModal();
        render();
        return;
    }
    if (action === "gallery-prev" || action === "gallery-next") {
        state.productModal.imageIndex = 0;
        render();
        return;
    }
    if (action === "toggle-favorite") {
        toggleFavorite(Number(button.dataset.productId));
        render();
        return;
    }
    if (action === "add-product") {
        const productId = Number(button.dataset.productId);
        const product = getProductById(productId);
        if (product?.price == null) {
            openManagerLink();
            return;
        }
        addToCart(productId, getInitialQuantity(product));
        render();
        return;
    }
    if (action === "confirm-product-cart") {
        const product = getProductById(Number(button.dataset.productId));
        if (product) {
            setCartQuantity(product.id, getSanitizedQuantityForProduct(product, getCartItem(product.id)?.quantity || state.productModal.quantity));
            saveCart();
        }
        closeProductModal();
        render();
        return;
    }
    if (action === "cart-minus" || action === "cart-plus") {
        adjustCartQuantity(Number(button.dataset.productId), action === "cart-plus" ? 1 : -1);
        render();
        return;
    }
    if (action === "remove-cart") {
        removeFromCart(Number(button.dataset.productId));
        render();
        return;
    }
    if (action === "open-checkout") {
        state.checkout.open = true;
        state.successOrderCode = "";
        render();
        return;
    }
    if (action === "close-checkout") {
        state.checkout.open = false;
        state.successOrderCode = "";
        render();
        return;
    }
    if (action === "submit-order") {
        submitOrder().catch(handleActionError);
        return;
    }
    if (action === "remove-attachment") {
        state.checkout.uploaded.splice(Number(button.dataset.index), 1);
        state.checkout.touched = true;
        scheduleClientStateSync();
        render();
        return;
    }
    if (action === "go-catalog") {
        state.nav = "catalog";
        state.catalog.section = "";
        state.catalog.query = "";
        state.checkout.open = false;
        state.successOrderCode = "";
        render();
        return;
    }
    if (action === "profile-status") {
        state.profileOrdersFilter = button.dataset.status;
        loadProfileOrders().catch(error => console.warn("Profile orders load failed", error));
        render();
        return;
    }
    if (action === "open-manager") {
        openManagerLink();
        return;
    }
    if (action === "open-filters") {
        ensureCatalogDraft();
        state.catalog.filtersOpen = true;
        state.catalog.filterFocus = "";
        state.catalog.filterPanels = defaultFilterPanels();
        state.catalog.draft = cloneFilters(state.catalog.applied);
        render();
        return;
    }
    if (action === "open-filter-focus") {
        ensureCatalogDraft();
        state.catalog.filterFocus = button.dataset.focus || "";
        state.catalog.filterPanels = {
            ...(state.catalog.filterPanels || defaultFilterPanels()),
            [state.catalog.filterFocus || "more"]: true,
        };
        render();
        applyPendingFilterFocus();
        return;
    }
    if (action === "toggle-filter-panel") {
        const panel = button.dataset.panel;
        state.catalog.filterPanels = {
            ...(state.catalog.filterPanels || defaultFilterPanels()),
            [panel]: !(state.catalog.filterPanels || defaultFilterPanels())[panel],
        };
        render();
        return;
    }
    if (action === "close-filters") {
        state.catalog.filtersOpen = false;
        state.catalog.filterFocus = "";
        state.catalog.filterPanels = defaultFilterPanels();
        render();
        return;
    }
    if (action === "reset-filters") {
        state.catalog.draft = emptyFilters();
        state.catalog.applied = emptyFilters();
        render();
        return;
    }
    if (action === "reset-inline-filters") {
        state.catalog.draft = emptyFilters();
        state.catalog.applied = emptyFilters();
        render();
        return;
    }
    if (action === "apply-filters") {
        state.catalog.applied = cloneFilters(state.catalog.draft || emptyFilters());
        state.catalog.filtersOpen = false;
        state.catalog.filterFocus = "";
        state.catalog.filterPanels = defaultFilterPanels();
        render();
        return;
    }
    if (action === "toggle-filter-section") {
        const section = button.dataset.section;
        const current = state.catalog.draft.sections[0] || "";
        state.catalog.draft.sections = current === section ? [] : [section];
        trimDraftFiltersToAvailable();
        render();
        return;
    }
    if (action === "admin-exit") {
        state.nav = "catalog";
        state.catalog.section = "";
        state.catalog.query = "";
        render();
        return;
    }
    if (action === "admin-menu") {
        state.admin.menu = button.dataset.menu;
        if (state.admin.menu === "catalog" && !state.admin.catalogSection) {
            state.admin.catalogSection = getAdminSectionTree()[0]?.name || "";
        }
        ensureAdminDataLoaded().catch(error => console.warn("Admin data load failed", error));
        render();
        return;
    }
    if (action === "admin-section") {
        state.admin.menu = "catalog";
        state.admin.catalogSection = button.dataset.section || "";
        state.admin.catalogCategory = button.dataset.category || "";
        render();
        return;
    }
    if (action === "open-admin-product") {
        const productId = button.dataset.productId ? Number(button.dataset.productId) : null;
        const product = productId ? state.admin.products.find(item => item.id === productId) : null;
        state.admin.productEditor = {
            open: true,
            productId,
            categoryDraft: product ? getProductSectionName(product) : (state.admin.catalogSection || getAdminPrimarySections()[0] || ""),
        };
        render();
        return;
    }
    if (action === "close-admin-product") {
        state.admin.productEditor = { open: false, productId: null, categoryDraft: "" };
        render();
        return;
    }
    if (action === "delete-admin-product") {
        deleteAdminProduct(Number(button.dataset.productId)).catch(handleActionError);
        return;
    }
    if (action === "toggle-admin-product-active") {
        toggleAdminProductVisibility(Number(button.dataset.productId)).catch(handleActionError);
        return;
    }
    if (action === "open-manufacturer-modal") {
        state.admin.manufacturerModal = { open: true, id: null, name: "" };
        render();
        return;
    }
    if (action === "edit-manufacturer") {
        state.admin.manufacturerModal = { open: true, id: button.dataset.id ? Number(button.dataset.id) : null, name: button.dataset.name || "" };
        render();
        return;
    }
    if (action === "close-manufacturer-modal") {
        state.admin.manufacturerModal = { open: false, id: null, name: "" };
        render();
        return;
    }
    if (action === "delete-manufacturer") {
        deleteManufacturer(Number(button.dataset.id)).catch(handleActionError);
        return;
    }
    if (action === "open-admin-order") {
        state.admin.orderModal = { open: true, orderId: Number(button.dataset.orderId) };
        render();
        return;
    }
    if (action === "open-admin-customer") {
        state.admin.customerModal = { open: true, maxUserId: button.dataset.maxUserId };
        render();
        return;
    }
    if (action === "close-admin-order") {
        state.admin.orderModal = { open: false, orderId: null };
        render();
        return;
    }
    if (action === "close-admin-customer") {
        state.admin.customerModal = { open: false, maxUserId: null };
        render();
        return;
    }
    if (action === "set-order-status") {
        updateOrderStatus(Number(button.dataset.orderId), button.dataset.status).catch(handleActionError);
        return;
    }
    if (action === "reset-admin-order-filters") {
        state.admin.orderFilters = { search: "", status: "ALL", from: "", to: "" };
        render();
        return;
    }
    if (action === "send-broadcast") {
        sendBroadcast().catch(handleActionError);
    }
}

function handleInput(event) {
    const { field } = event.target.dataset;
    if (!field) return;
    if (event.target.dataset.optionsId) {
        updateAdminSuggestions(event.target);
        return;
    }
    if (field === "catalog-query") {
        state.catalog.query = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field.startsWith("checkout-")) {
        const key = field.replace("checkout-", "");
        state.checkout.form[key] = event.target.value;
        state.checkout.touched = true;
        if (state.checkout.errors[key]) {
            delete state.checkout.errors[key];
        }
        scheduleClientStateSync();
        return;
    }
    if (field === "admin-product-search") {
        state.admin.catalogSearch = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field === "admin-product-category") {
        state.admin.productEditor.categoryDraft = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field === "admin-order-search") {
        state.admin.orderFilters.search = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field === "admin-customer-search") {
        state.admin.customerSearch = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field === "broadcast-text") {
        state.admin.broadcastForm.text = event.target.value;
        renderPreservingFocus();
        return;
    }
    if (field === "filter-price-min") {
        ensureCatalogDraft();
        state.catalog.draft.priceMin = event.target.value;
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-price-max") {
        ensureCatalogDraft();
        state.catalog.draft.priceMax = event.target.value;
        syncAppliedFiltersFromDraft();
        return;
    }
}

function handleFocusIn(event) {
    const input = event.target.closest("input[data-options-id]");
    if (!input) return;
    updateAdminSuggestions(input);
}

function handleFocusOut(event) {
    const input = event.target.closest("input[data-options-id]");
    if (!input) return;
    const field = input.dataset.field;
    window.setTimeout(() => {
        if (document.activeElement?.dataset?.field === field) {
            return;
        }
        clearAdminSuggestions(field);
    }, 120);
}

function handleChange(event) {
    const { field } = event.target.dataset;
    if (!field) return;
    if (field === "catalog-sort") {
        state.catalog.sort = event.target.value;
        render();
        return;
    }
    if (field === "checkout-attachment") {
        uploadCheckoutAttachments(event.target.files).catch(handleActionError);
        return;
    }
    if (field === "broadcast-image") {
        uploadBroadcastImage(event.target.files?.[0]).catch(handleActionError);
        return;
    }
    if (field === "filter-manufacturer") {
        ensureCatalogDraft();
        toggleDraftFilterArray("manufacturers", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-culture") {
        ensureCatalogDraft();
        toggleDraftFilterArray("cultures", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-active-ingredient") {
        ensureCatalogDraft();
        toggleDraftFilterArray("activeIngredients", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-subcategory") {
        ensureCatalogDraft();
        toggleDraftFilterArray("subcategories", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-seed-fao") {
        ensureCatalogDraft();
        toggleDraftFilterArray("seedFaoRanges", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-seed-maturity") {
        ensureCatalogDraft();
        toggleDraftFilterArray("seedMaturityGroups", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "filter-seed-technology") {
        ensureCatalogDraft();
        toggleDraftFilterArray("seedTreatmentTechnologies", event.target.value, event.target.checked);
        syncAppliedFiltersFromDraft();
        return;
    }
    if (field === "admin-product-status") {
        state.admin.catalogStatus = event.target.value;
        render();
        return;
    }
    if (field === "admin-order-status") {
        state.admin.orderFilters.status = event.target.value;
        render();
        return;
    }
    if (field === "admin-order-from") {
        state.admin.orderFilters.from = event.target.value;
        render();
        return;
    }
    if (field === "admin-order-to") {
        state.admin.orderFilters.to = event.target.value;
        render();
    }
}

function handleSubmit(event) {
    event.preventDefault();
    const form = event.target.dataset.form;
    if (form === "admin-product") {
        saveAdminProduct(new FormData(event.target)).catch(handleActionError);
        return;
    }
    if (form === "manufacturer") {
        saveManufacturer(new FormData(event.target)).catch(handleActionError);
    }
}

function getAdminSuggestionOptions(input) {
    const listId = input?.dataset?.optionsId;
    if (!listId) return [];
    return [...root.querySelectorAll(`#${CSS.escape(listId)} option`)]
        .map(option => option.value)
        .filter(Boolean);
}

function getAdminSuggestionState(input) {
    const mode = input.dataset.suggestMode || "single";
    const rawValue = String(input.value || "");
    if (mode !== "multi") {
        return {
            mode,
            prefix: "",
            query: rawValue.trim(),
            selectedKeys: new Set(),
        };
    }
    const tokens = rawValue.split(",");
    const currentToken = String(tokens.pop() || "").trim();
    const selectedKeys = new Set(tokens.map(item => normalize(item)).filter(Boolean));
    const prefix = tokens
        .map(item => item.trim())
        .filter(Boolean);
    return {
        mode,
        prefix,
        query: currentToken,
        selectedKeys,
    };
}

function getAdminSuggestionMatches(input) {
    const options = uniqueValues(getAdminSuggestionOptions(input));
    const { query, selectedKeys } = getAdminSuggestionState(input);
    const normalizedQuery = normalize(query);
    return options
        .filter(option => !selectedKeys.has(normalize(option)))
        .filter(option => !normalizedQuery || normalize(option).includes(normalizedQuery))
        .slice(0, 8);
}

function updateAdminSuggestions(input) {
    const field = input.dataset.field;
    if (!field) return;
    const holder = root.querySelector(`[data-suggestions-for="${CSS.escape(field)}"]`);
    if (!holder) return;
    const matches = getAdminSuggestionMatches(input);
    if (!matches.length) {
        holder.innerHTML = "";
        holder.classList.remove("visible");
        return;
    }
    holder.innerHTML = matches.map(value => `
        <button type="button" class="admin-suggestion-btn" data-action="pick-admin-suggestion" data-field="${escapeAttr(field)}" data-value="${escapeAttr(value)}">${escapeHtml(value)}</button>
    `).join("");
    holder.classList.add("visible");
}

function clearAdminSuggestions(field) {
    if (!field) return;
    const holder = root.querySelector(`[data-suggestions-for="${CSS.escape(field)}"]`);
    if (!holder) return;
    holder.innerHTML = "";
    holder.classList.remove("visible");
}

function applyAdminSuggestion(field, value) {
    const input = root.querySelector(`input[data-field="${CSS.escape(field)}"]`);
    if (!input) return;
    const mode = input.dataset.suggestMode || "single";
    if (mode === "multi") {
        const state = getAdminSuggestionState(input);
        const nextTokens = [...state.prefix, value];
        input.value = `${nextTokens.join(", ")}, `;
    } else {
        input.value = value;
    }
    updateAdminSuggestions(input);
    input.focus();
    if (typeof input.setSelectionRange === "function") {
        const pos = input.value.length;
        input.setSelectionRange(pos, pos);
    }
}

async function submitOrder() {
    const validation = validateCheckout();
    if (!validation.valid) {
        state.checkout.errors = validation.errors;
        render();
        return;
    }
    state.checkout.submitting = true;
    render();
    const items = state.cart.map(item => ({
        productId: item.productId,
        quantity: item.quantity,
    }));
    const organization = state.checkout.form.organization.trim();
    const payload = {
        maxUserId: state.maxUserId,
        name: organization,
        phone: state.checkout.form.phone.trim(),
        company: organization,
        farmName: state.checkout.form.farmName.trim(),
        inn: state.checkout.form.inn.trim(),
        email: state.checkout.form.email.trim(),
        deliveryAddress: state.checkout.form.address.trim(),
        comment: state.checkout.form.comment.trim(),
        attachments: state.checkout.uploaded,
        items,
    };
    const response = await fetchJson("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
    state.cart = [];
    saveCart();
    state.checkout.submitting = false;
    state.checkout.touched = false;
    state.checkout.open = true;
    state.checkout.uploaded = [];
    state.successOrderCode = response.orderCode || "";
    state.nav = "cart";
    state.profileOrders = state.maxUserId ? await fetchJson(`/api/profile/orders?maxUserId=${state.maxUserId}`) : [];
    if (state.profile?.admin) {
        await loadAdminData();
    }
    render();
}

async function uploadCheckoutAttachments(fileList) {
    if (!fileList?.length) return;
    for (const file of Array.from(fileList)) {
        if (file.size > 25 * 1024 * 1024) {
            throw new Error(`Файл «${file.name}» больше 25 МБ. Уменьши вложение и попробуй снова.`);
        }
        const formData = new FormData();
        formData.append("maxUserId", String(state.maxUserId || 0));
        formData.append("file", file);
        const uploaded = await fetchJson("/api/uploads/order-attachment", {
            method: "POST",
            body: formData,
        });
        state.checkout.uploaded.push(uploaded);
    }
    state.checkout.touched = true;
    scheduleClientStateSync();
    render();
}

async function uploadBroadcastImage(file) {
    if (!file) return;
    const formData = new FormData();
    formData.append("maxUserId", String(state.maxUserId || 0));
    formData.append("file", file);
    const uploaded = await fetchJson("/api/uploads/media", {
        method: "POST",
        body: formData,
    });
    state.admin.broadcastForm.imageUrl = uploaded.downloadUrl;
    state.admin.broadcastForm.imageName = uploaded.originalName || uploaded.storedName;
    render();
}

async function saveAdminProduct(formData) {
    const id = Number(formData.get("productId")) || null;
    const activeIngredient = String(formData.get("activeIngredient") || "").trim();
    const discountPercent = parseOptionalNumber(formData.get("discountPercent"));
    const minOrderQuantity = parseOptionalNumber(formData.get("minOrderQuantity"));
    if (minOrderQuantity == null || minOrderQuantity <= 0) {
        throw new Error("Минимальный объем заказа обязателен.");
    }
    const orderMode = String(formData.get("orderMode") || "liters").trim();
    let category = String(formData.get("category") || "").trim();
    const availableSubcategories = getAdminSubcategoryOptions(category);
    let subcategory = String(formData.get("subcategory") || "").trim();
    if (!availableSubcategories.includes(subcategory)) {
        subcategory = availableSubcategories[0] || "";
    }
    let unitName = "л";
    let packageType = "упаковка";
    let packageDescription = String(formData.get("packageDescription") || "").trim();
    let orderStep = parseOptionalNumber(formData.get("orderStep")) || 1;
    const packageVolume = parseOptionalNumber(formData.get("packageVolume"));
    const unitsPerPackage = parseOptionalNumber(formData.get("unitsPerPackage"));

    if (orderMode === "kg") {
        unitName = "кг";
    } else if (orderMode === "pe") {
        unitName = "п.е.";
        packageType = "п.е.";
        packageDescription = "П.е.";
    } else if (orderMode === "ton") {
        unitName = "т";
        packageType = "тонна";
        packageDescription = "Тонна";
    }

    if (orderMode === "liters" || orderMode === "kg") {
        if (unitsPerPackage != null && unitsPerPackage > 1 && packageVolume != null && packageVolume > 0) {
            packageType = "коробка";
            packageDescription = packageDescription || (category === "Пестициды"
                ? `${formatAdminNumber(packageVolume)}x${formatAdminNumber(unitsPerPackage)}`
                : `Коробка ${formatAdminNumber(unitsPerPackage)} × ${formatAdminNumber(packageVolume)} ${unitName}`);
        } else if (packageVolume != null && packageVolume > 0) {
            packageType = "упаковка";
            packageDescription = packageDescription || (category === "Пестициды"
                ? `${formatAdminNumber(packageVolume)}${unitName}`
                : `${formatAdminNumber(packageVolume)} ${unitName}`);
        } else {
            packageDescription = packageDescription || unitName;
        }
    }

    const payload = {
        name: String(formData.get("name") || "").trim(),
        category,
        subcategory,
        itemType: subcategory,
        brand: String(formData.get("brand") || "").trim(),
        description: String(formData.get("description") || "").trim(),
        unitName,
        price: parseOptionalNumber(formData.get("price")),
        stockQuantity: parseOptionalNumber(formData.get("stockQuantity")),
        packageType,
        packageDescription,
        minOrderQuantity,
        orderStep,
        cultures: String(formData.get("cultures") || "").trim(),
        tags: String(formData.get("tags") || "").trim(),
        filterMap: {
            activeIngredient,
            discountPercent: discountPercent == null ? "" : formatAdminNumber(discountPercent),
            oldPrice: "",
        },
        active: String(formData.get("active")) !== "false",
    };
    const url = id ? `/api/admin/products/${id}?maxUserId=${state.maxUserId}` : `/api/admin/products?maxUserId=${state.maxUserId}`;
    const method = id ? "PUT" : "POST";
    state.admin.productEditor = { open: false, productId: null, categoryDraft: "" };
    showNotice(id ? "Сохранение товара запущено..." : "Добавление товара запущено...");
    render();
    await fetchJson(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
    await refreshCatalogData();
    showNotice(id ? "Товар сохранен." : "Товар добавлен.");
    render();
}

function formatAdminNumber(value) {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) {
        return "";
    }
    return Number.isInteger(numeric) ? String(numeric) : numeric.toLocaleString("ru-RU", { maximumFractionDigits: 3 });
}

function buildAdminProductPayload(product, overrides = {}) {
    return {
        externalId: product.externalId || "",
        sourceFile: product.sourceFile || "",
        sku: product.sku || "",
        name: product.name || "",
        description: product.description || "",
        brand: product.brand || "",
        category: product.category || "",
        subcategory: product.subcategory || "",
        itemType: product.itemType || "",
        unitName: product.unitName || "шт",
        price: product.price ?? null,
        stockQuantity: product.stockQuantity ?? null,
        packageType: product.packageType || "",
        packageDescription: product.packageDescription || "",
        minOrderQuantity: product.minOrderQuantity ?? 1,
        orderStep: product.orderStep ?? 1,
        cultures: (product.cultures || []).join(", "),
        purposes: (product.purposes || []).join(", "),
        tags: (product.tags || []).join(", "),
        filterMap: {
            ...(product.filterMap || {}),
            activeIngredient: product.activeIngredient || product.filterMap?.activeIngredient || "",
            oldPrice: product.oldPrice ?? product.filterMap?.oldPrice ?? "",
        },
        rawData: product.rawData || {},
        active: Boolean(product.active),
        ...overrides,
    };
}

async function deleteAdminProduct(productId) {
    const wasEditingCurrent = state.admin.productEditor.open && state.admin.productEditor.productId === productId;
    if (wasEditingCurrent) {
        state.admin.productEditor = { open: false, productId: null, categoryDraft: "" };
    }
    showNotice("Удаление товара запущено...");
    render();
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, { method: "DELETE" });
    await refreshCatalogData();
    showNotice("Товар удален.");
    render();
}

async function toggleAdminProductVisibility(productId) {
    const product = state.admin.products.find(item => item.id === productId);
    if (!product) {
        return;
    }
    const nextActive = !product.active;
    const wasEditingCurrent = state.admin.productEditor.open && state.admin.productEditor.productId === productId;
    if (wasEditingCurrent) {
        state.admin.productEditor = { open: false, productId: null, categoryDraft: "" };
    }
    showNotice(nextActive ? "Публикация товара обновляется..." : "Скрытие товара запущено...");
    render();
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(buildAdminProductPayload(product, { active: nextActive })),
    });
    await refreshCatalogData();
    showNotice(nextActive ? "Товар снова показан." : "Товар скрыт.");
    render();
}

async function saveManufacturer(formData) {
    const id = Number(formData.get("id")) || null;
    const payload = { name: String(formData.get("name") || "").trim() };
    const url = id ? `/api/admin/manufacturers/${id}?maxUserId=${state.maxUserId}` : `/api/admin/manufacturers?maxUserId=${state.maxUserId}`;
    const method = id ? "PUT" : "POST";
    await fetchJson(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
    state.admin.manufacturerModal = { open: false, id: null, name: "" };
    state.admin.manufacturers = await fetchJson(`/api/admin/manufacturers?maxUserId=${state.maxUserId}`);
    showNotice(id ? "Производитель сохранен." : "Производитель добавлен.");
    render();
}

async function deleteManufacturer(id) {
    await fetchJson(`/api/admin/manufacturers/${id}?maxUserId=${state.maxUserId}`, { method: "DELETE" });
    state.admin.manufacturers = await fetchJson(`/api/admin/manufacturers?maxUserId=${state.maxUserId}`);
    render();
}

async function updateOrderStatus(orderId, status) {
    const updated = await fetchJson(`/api/admin/orders/${orderId}/status?maxUserId=${state.maxUserId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status }),
    });
    state.admin.orders = state.admin.orders.map(item => item.id === updated.id ? updated : item);
    if (state.admin.dashboard?.latestOrders) {
        state.admin.dashboard.latestOrders = state.admin.dashboard.latestOrders.map(item => item.id === updated.id ? updated : item);
    }
    state.admin.orderModal.orderId = updated.id;
    render();
}

async function sendBroadcast() {
    if (!state.admin.broadcastForm.text.trim()) {
        throw new Error("Введите текст рассылки");
    }
    state.admin.broadcastForm.sending = true;
    render();
    await fetchJson(`/api/admin/broadcasts?maxUserId=${state.maxUserId}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
            text: state.admin.broadcastForm.text.trim(),
            imageUrl: state.admin.broadcastForm.imageUrl || "",
        }),
    });
    state.admin.broadcastForm = { text: "", imageUrl: "", imageName: "", sending: false };
    state.admin.broadcasts = await fetchJson(`/api/admin/broadcasts?maxUserId=${state.maxUserId}`);
    render();
}

async function refreshCatalogData() {
    const [products] = await Promise.all([
        fetchJson("/api/catalog/products?sort=name"),
    ]);
    state.products = products;
    state.sections = getCatalogSections();
    if (state.profile?.admin) {
        await loadAdminData();
    }
}

async function loadProfileOrders() {
    if (!state.maxUserId || state.app.profileOrdersReady || state.app.profileOrdersLoading) {
        return;
    }
    state.app.profileOrdersLoading = true;
    if (state.nav === "profile") {
        render();
    }
    try {
        state.profileOrders = await fetchJson(`/api/profile/orders?maxUserId=${state.maxUserId}`);
        state.app.profileOrdersReady = true;
    } finally {
        state.app.profileOrdersLoading = false;
        if (state.nav === "profile") {
            render();
        }
    }
}

async function ensureAdminDataLoaded() {
    if (!state.maxUserId || !state.profile?.admin || state.admin.ready || state.admin.loading) {
        return;
    }
    state.admin.loading = true;
    if (state.nav === "profile") {
        render();
    }
    try {
        await loadAdminData();
    } finally {
        state.admin.loading = false;
        if (state.nav === "profile") {
            render();
        }
    }
}

function validateCheckout() {
    const errors = {};
    if (!state.checkout.form.organization.trim()) errors.organization = true;
    if (!state.checkout.form.phone.trim()) errors.phone = true;
    if (!state.checkout.form.email.trim()) errors.email = true;
    if (!state.checkout.form.address.trim()) errors.address = true;
    return { valid: Object.keys(errors).length === 0, errors };
}

function renderField(key, label, value, placeholder, required) {
    return `
        <div class="field ${state.checkout.errors[key] ? "invalid" : ""}">
            <label>${escapeHtml(label)}${required ? "" : ""}</label>
            <input data-field="checkout-${key}" value="${escapeAttr(value)}" placeholder="${escapeAttr(placeholder)}">
        </div>
    `;
}

function renderSortOptions() {
    return renderOptions([
        ["default", "По умолчанию"],
        ["price_asc", "Сначала дешевле"],
        ["price_desc", "Сначала дороже"],
    ], state.catalog.sort);
}

function renderOptions(options, selected) {
    return options.map(([value, label]) => `
        <option value="${escapeAttr(value)}" ${String(selected) === String(value) ? "selected" : ""}>${escapeHtml(label)}</option>
    `).join("");
}

function getCatalogSections() {
    return FIXED_SECTION_DEFINITIONS.map(section => ({
        name: section.name,
        description: section.description,
        productsCount: state.products.filter(product => getProductSectionName(product) === section.name).length,
    }));
}

function getSectionProducts(sectionName) {
    return state.products.filter(product => getProductSectionName(product) === sectionName);
}

function getSearchResults(query) {
    const normalized = normalize(query);
    return state.products.filter(product => normalize([
        product.name,
        product.description,
        product.brand,
        product.category,
        product.subcategory,
        ...(product.cultures || []),
        ...(product.tags || []),
    ].join(" ")).includes(normalized));
}

function applyCatalogFilters(products) {
    let filtered = [...products];
    const applied = state.catalog.applied;
    if (applied.sections.length) {
        filtered = filtered.filter(product => applied.sections.includes(getProductSectionName(product)));
    }
    if (applied.cultures.length) {
        filtered = filtered.filter(product => (product.cultures || []).some(item => applied.cultures.includes(item)));
    }
    if (applied.manufacturers.length) {
        filtered = filtered.filter(product => applied.manufacturers.includes(product.brand));
    }
    if (applied.activeIngredients.length) {
        filtered = filtered.filter(product => {
            const value = String(product.activeIngredient || product.filterMap?.activeIngredient || "").trim();
            return value && applied.activeIngredients.includes(value);
        });
    }
    if (applied.subcategories.length) {
        filtered = filtered.filter(product => {
            const values = getFilterSubcategoryValues(product, state.catalog.section);
            return values.some(value => applied.subcategories.includes(value));
        });
    }
    if (applied.seedFaoRanges.length) {
        filtered = filtered.filter(product => {
            const value = getSeedFaoRangeLabel(product);
            return value && applied.seedFaoRanges.includes(value);
        });
    }
    if (applied.seedMaturityGroups.length) {
        filtered = filtered.filter(product => {
            const value = getSeedMaturityGroup(product);
            return value && applied.seedMaturityGroups.includes(value);
        });
    }
    if (applied.seedTreatmentTechnologies.length) {
        filtered = filtered.filter(product => {
            const value = getSeedTreatmentTechnology(product);
            return value && applied.seedTreatmentTechnologies.includes(value);
        });
    }
    if (applied.priceMin) {
        filtered = filtered.filter(product => product.price != null && Number(product.price) >= Number(applied.priceMin));
    }
    if (applied.priceMax) {
        filtered = filtered.filter(product => product.price != null && Number(product.price) <= Number(applied.priceMax));
    }
    if (state.catalog.query.trim()) {
        const normalized = normalize(state.catalog.query);
        filtered = filtered.filter(product => normalize([product.name, product.description, product.brand].join(" ")).includes(normalized));
    }
    return sortProducts(filtered, state.catalog.sort);
}

function sortProducts(products, sort) {
    return [...products].sort((left, right) => {
        const leftPrice = left.price == null ? null : Number(left.price);
        const rightPrice = right.price == null ? null : Number(right.price);
        if (sort === "price_asc") {
            if (leftPrice == null && rightPrice == null) return compareNames(left, right);
            if (leftPrice == null) return 1;
            if (rightPrice == null) return -1;
            return leftPrice - rightPrice || compareNames(left, right);
        }
        if (sort === "price_desc") {
            if (leftPrice == null && rightPrice == null) return compareNames(left, right);
            if (leftPrice == null) return -1;
            if (rightPrice == null) return 1;
            return rightPrice - leftPrice || compareNames(left, right);
        }
        return compareNames(left, right);
    });
}

function compareNames(left, right) {
    return String(left.name || "").localeCompare(String(right.name || ""), "ru", { sensitivity: "base" });
}

function getProductVisual(product) {
    return getSectionVisual(getProductSectionName(product));
}

function getSectionVisual(name) {
    const definition = getSectionDefinition(name);
    if (definition) {
        return definition;
    }
    return {
        key: "other",
        icon: "./assets/category-other.svg",
        palette: ["#e1f1de", "#d7e9f6"],
        description: getSectionDescription(name),
    };
}

function getVisualImageClass(visual) {
    return "visual-icon";
}

function getSectionDisplayName(name) {
    const definition = getSectionDefinition(name);
    if (definition) {
        return definition.name;
    }
    return name || FIXED_SECTION_DEFINITIONS[0]?.name || "";
}

function getSectionDescription(name) {
    return getSectionDefinition(name)?.description || (name ? `Товары раздела ${name}` : "Товары каталога");
}

function isSpecificSectionName(name) {
    return Boolean(resolveSectionSubcategory(name, "Пестициды"))
        || Boolean(resolveSectionSubcategory(name, "Агрохимикаты"))
        || Boolean(resolveSectionSubcategory(name, "Спецпрепараты"))
        || Boolean(resolveSectionSubcategory(name, "ПАВы"))
        || Boolean(resolveSectionSubcategory(name, "Пеногасители"))
        || Boolean(resolveSectionSubcategory(name, "Хим Мелиоранты"))
        || Boolean(resolveSectionSubcategory(name, "Препараты для закрытого грунта"));
}

function isSzrSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("пестиц")
        || normalized.includes("сзр")
        || normalized.includes("гербиц")
        || normalized.includes("фунгиц")
        || normalized.includes("инсекти")
        || normalized.includes("десикант")
        || normalized.includes("протрав")
        || normalized.includes("регулятор рост");
}

function isSeedsSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("семен")
        || normalized.includes("озим")
        || normalized.includes("яров")
        || normalized.includes("пшениц")
        || normalized.includes("рож")
        || normalized.includes("тритикал")
        || normalized.includes("кукуруз")
        || normalized.includes("подсолнеч")
        || normalized.includes("соя")
        || normalized.includes("рапс")
        || normalized.includes("гречих")
        || normalized.includes("горох")
        || normalized.includes("бобов")
        || normalized.includes("зернов")
        || normalized.includes("маслич")
        || normalized.includes("травосм")
        || normalized.includes("злаков")
        || normalized.includes("люцерн")
        || normalized.includes("ячмен")
        || normalized.includes("посевн");
}

function isPavsSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("пав")
        || normalized.includes("адъюв")
        || normalized.includes("адьюв")
        || normalized.includes("прилип")
        || normalized.includes("смачив")
        || normalized.includes("сурфакт");
}

function isAgrochemicalsSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("агрохим")
        || normalized.includes("удобр")
        || normalized.includes("агропитан")
        || normalized.includes("питан")
        || normalized.includes("микроудобр")
        || normalized.includes("микроэлемент")
        || normalized.includes("биостим")
        || normalized.includes("инокулянт")
        || normalized.includes("аминокислот")
        || normalized.includes("гумат")
        || normalized.includes("листов")
        || normalized.includes("подкорм");
}

function isMeliorantsSectionName(name) {
    return normalize(name).includes("мелиор");
}

function isClosedGroundSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("закрыт") || normalized.includes("теплиц");
}

function isDefoamersSectionName(name) {
    return normalize(name).includes("пеногас");
}

function isSpecialSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("спец")
        || normalized.includes("специальн")
        || normalized.includes("роденти")
        || normalized.includes("репелент")
        || normalized.includes("красител")
        || normalized.includes("амбарн")
        || normalized.includes("склад");
}

function getProductLeafSectionName(product) {
    return getAdminCatalogChildName(product);
}

function getProductSectionName(product) {
    const category = String(product?.category || "").trim();
    const directSection = getSectionDefinition(category);
    if (directSection) {
        return directSection.name;
    }
    const subcategory = String(product?.subcategory || product?.itemType || "").trim();
    const context = [category, subcategory, product?.name, product?.activeIngredient].join(" ");
    if (isSeedsSectionName(context)) {
        return "Семена";
    }
    if (isMeliorantsSectionName(context)) {
        return "Хим Мелиоранты";
    }
    if (isClosedGroundSectionName(context)) {
        return "Препараты для закрытого грунта";
    }
    if (isPavsSectionName(context)) {
        return "ПАВы";
    }
    if (isDefoamersSectionName(context)) {
        return "Пеногасители";
    }
    if (isSpecialSectionName(context)) {
        return "Спецпрепараты";
    }
    if (isAgrochemicalsSectionName(context)) {
        return "Агрохимикаты";
    }
    if (isSzrSectionName(context) || !normalize(context)) {
        return "Пестициды";
    }
    return "Пестициды";
}

function buildCategoriesTree(products) {
    const tree = {};
    FIXED_SECTION_DEFINITIONS.forEach(section => {
        tree[section.name] = [];
    });
    products.forEach(product => {
        const parent = getProductSectionName(product);
        const child = getAdminCatalogChildName(product);
        if (!child || child === "Без категории") {
            return;
        }
        if (!tree[parent]) {
            tree[parent] = [];
        }
        if (!tree[parent].includes(child)) {
            tree[parent].push(child);
        }
    });
    Object.keys(tree).forEach(key => {
        tree[key] = sortValuesByConfiguredOrder(key, tree[key]);
    });
    return tree;
}

function getAdminSectionTree() {
    const grouped = new Map(FIXED_SECTION_DEFINITIONS.map(section => [section.name, {
        name: section.name,
        count: 0,
        visual: getSectionVisual(section.name),
        childrenMap: {},
    }]));
    state.admin.products.forEach(product => {
        const sectionName = getProductSectionName(product);
        const child = getAdminCatalogChildName(product);
        const bucket = grouped.get(sectionName) || {
            name: sectionName,
            count: 0,
            visual: getSectionVisual(sectionName),
            childrenMap: {},
        };
        bucket.count += 1;
        if (child && child !== "Без категории") {
            bucket.childrenMap[child] = (bucket.childrenMap[child] || 0) + 1;
        }
        grouped.set(sectionName, bucket);
    });
    return [...grouped.values()].map(section => ({
        name: section.name,
        count: section.count,
        visual: section.visual,
        children: sortValuesByConfiguredOrder(section.name, Object.keys(section.childrenMap))
            .map(name => ({ name, count: section.childrenMap[name] })),
    }));
}

function getAdminFilteredProducts() {
    const search = normalize(state.admin.catalogSearch);
    const effectiveSection = state.admin.catalogSection || getAdminSectionTree()[0]?.name || "";
    return state.admin.products.filter(product => {
        if (state.admin.catalogStatus === "ACTIVE" && !product.active) return false;
        if (state.admin.catalogStatus === "HIDDEN" && product.active) return false;
        if (effectiveSection && getProductSectionName(product) !== effectiveSection) return false;
        if (state.admin.catalogCategory && getAdminCatalogChildName(product) !== state.admin.catalogCategory) return false;
        if (search && !normalize([product.name, product.brand, getProductSectionName(product), product.category, product.subcategory].join(" ")).includes(search)) return false;
        return true;
    });
}

function getFilteredAdminOrders() {
    const { search, status, from, to } = state.admin.orderFilters;
    const normalizedSearch = normalize(search);
    return state.admin.orders.filter(order => {
        if (status !== "ALL" && order.status !== status) return false;
        if (from && String(order.createdAt).slice(0, 10) < from) return false;
        if (to && String(order.createdAt).slice(0, 10) > to) return false;
        if (normalizedSearch && !normalize([order.customerName, order.customerPhone].join(" ")).includes(normalizedSearch)) return false;
        return true;
    });
}

function getFilteredAdminCustomers() {
    const search = normalize(state.admin.customerSearch);
    return state.admin.customers.filter(customer => {
        if (!search) {
            return true;
        }
        return normalize([
            customer.displayName,
            customer.username,
            customer.contactPhone,
            customer.contactEmail,
            customer.organization,
            customer.inn,
            customer.maxUserId,
        ].join(" ")).includes(search);
    });
}

function getPendingOrdersCount() {
    return (state.admin.orders || []).filter(order => order.status === "NEW").length || null;
}

function filterProfileOrders() {
    if (state.profileOrdersFilter === "ALL") return state.profileOrders;
    return state.profileOrders.filter(order => order.status === state.profileOrdersFilter);
}

function profileStatusLabel(status) {
    return {
        ALL: "Все",
        NEW: "В ожидании",
        IN_PROGRESS: "В работе",
        COMPLETED: "Оплачен",
        CANCELLED: "Отменён",
    }[status] || status;
}

function renderStepper(productId, quantity, variant) {
    return `
        <div class="qty-stepper">
            <button type="button" data-action="cart-minus" data-product-id="${productId}">−</button>
            <span>${formatQuantity(quantity)}</span>
            <button type="button" data-action="cart-plus" data-product-id="${productId}">+</button>
        </div>
    `;
}

function addToCart(productId, quantity) {
    const product = getProductById(productId);
    if (!product || product.price == null) return;
    const existing = getCartItem(productId);
    const base = existing ? existing.quantity : 0;
    const next = getSanitizedQuantityForProduct(product, base + quantity);
    setCartQuantity(productId, next);
    saveCart();
}

function setCartQuantity(productId, quantity) {
    const nextQuantity = Math.max(0, Number(quantity) || 0);
    const existing = state.cart.find(item => item.productId === productId);
    if (nextQuantity <= 0) {
        state.cart = state.cart.filter(item => item.productId !== productId);
        return;
    }
    if (existing) {
        existing.quantity = nextQuantity;
    } else {
        state.cart.push({ productId, quantity: nextQuantity });
    }
}

function adjustCartQuantity(productId, direction) {
    const product = getProductById(productId);
    if (!product) return;
    const current = getCartItem(productId)?.quantity || 0;
    const step = Number(product.orderStep || 1);
    const min = Number(product.minOrderQuantity || 1);
    const next = direction > 0
        ? (current > 0 ? current + step : min)
        : current - step;
    if (next < min) {
        removeFromCart(productId);
    } else {
        setCartQuantity(productId, getSanitizedQuantityForProduct(product, next));
    }
    saveCart();
}

function getSanitizedQuantityForProduct(product, quantity) {
    const step = Math.max(0.001, Number(product.orderStep || 1));
    const min = Math.max(0.001, Number(product.minOrderQuantity || 1));
    const safeQuantity = Math.max(min, Number(quantity) || min);
    if (safeQuantity <= min) return roundQuantity(min);
    return roundQuantity(min + Math.round((safeQuantity - min) / step) * step);
}

function getInitialQuantity(product) {
    return roundQuantity(Math.max(0.001, Number(product.minOrderQuantity || 1)));
}

function removeFromCart(productId) {
    state.cart = state.cart.filter(item => item.productId !== productId);
    saveCart();
}

function getCartItem(productId) {
    return state.cart.find(item => item.productId === productId) || null;
}

function getCartProducts() {
    return state.cart
        .map(item => ({ ...item, product: getProductById(item.productId) }))
        .filter(item => item.product);
}

function sumCartUnits() {
    return state.cart.reduce((sum, item) => sum + Number(item.quantity || 0), 0);
}

function formatApproximateTotal(items) {
    const numeric = items.filter(item => item.product.price != null);
    const hasRequest = items.some(item => item.product.price == null);
    const total = numeric.reduce((sum, item) => sum + Number(item.product.price) * Number(item.quantity), 0);
    if (!numeric.length && hasRequest) return "По запросу";
    return `${hasRequest ? "~" : ""}${formatPrice(total)}`;
}

function toggleFavorite(productId) {
    if (state.favorites.includes(productId)) {
        state.favorites = state.favorites.filter(item => item !== productId);
    } else {
        state.favorites = [...state.favorites, productId];
    }
    saveStorage("alga-favorites", state.favorites);
}

function isFavorite(productId) {
    return state.favorites.includes(productId);
}

function closeProductModal() {
    state.productModal = { open: false, productId: null, quantity: 1, imageIndex: 0 };
}

function hydrateCheckoutFromProfile() {
    if (!state.profile) return;
    const draft = state.profile.draftCheckout || {};
    state.checkout.form.phone = draft.phone || state.profile.phone || "";
    state.checkout.form.email = draft.email || state.profile.email || "";
    state.checkout.form.organization = draft.organization || "";
    state.checkout.form.farmName = draft.farmName || state.profile.farmName || "";
    state.checkout.form.inn = draft.inn || state.profile.inn || "";
    state.checkout.form.address = draft.address || "";
    state.checkout.form.comment = draft.comment || "";
    state.checkout.uploaded = Array.isArray(draft.attachments) ? draft.attachments : [];
    state.checkout.touched = hasCheckoutDraftContent(draft);
}

function emptyFilters() {
    return {
        sections: [],
        cultures: [],
        manufacturers: [],
        activeIngredients: [],
        subcategories: [],
        seedFaoRanges: [],
        seedMaturityGroups: [],
        seedTreatmentTechnologies: [],
        priceMin: "",
        priceMax: "",
    };
}

function emptyFilterPanels() {
    return { sections: false, culture: false, manufacturer: false, activeIngredient: false, subcategory: false, fao: false, maturity: false, technology: false, more: false };
}

function defaultFilterPanels() {
    return { sections: true, culture: true, manufacturer: true, activeIngredient: true, subcategory: true, fao: false, maturity: false, technology: false, more: true };
}

function cloneFilters(filters) {
    return {
        sections: [...(filters.sections || [])],
        cultures: [...(filters.cultures || [])],
        manufacturers: [...(filters.manufacturers || [])],
        activeIngredients: [...(filters.activeIngredients || [])],
        subcategories: [...(filters.subcategories || [])],
        seedFaoRanges: [...(filters.seedFaoRanges || [])],
        seedMaturityGroups: [...(filters.seedMaturityGroups || [])],
        seedTreatmentTechnologies: [...(filters.seedTreatmentTechnologies || [])],
        priceMin: filters.priceMin || "",
        priceMax: filters.priceMax || "",
    };
}

function getDraftSectionProducts(filters) {
    const selectedSections = filters?.sections || [];
    if (selectedSections.length) {
        return state.products.filter(product => selectedSections.includes(getProductSectionName(product)));
    }
    if (state.catalog.section) {
        return getSectionProducts(state.catalog.section);
    }
    return state.products;
}

function trimDraftFiltersToAvailable() {
    const draft = state.catalog.draft;
    if (!draft) {
        return;
    }
    const sectionProducts = getDraftSectionProducts(draft);
    const manufacturers = new Set(uniqueValues(sectionProducts.map(item => item.brand).filter(Boolean)));
    const cultures = new Set(uniqueValues(sectionProducts.flatMap(item => item.cultures || []).filter(Boolean)));
    const activeIngredients = new Set(uniqueValues(sectionProducts.map(item => item.activeIngredient || item.filterMap?.activeIngredient).filter(Boolean)));
    const cultureKeys = new Set([...cultures].map(normalize));
    const effectiveSection = draft.sections[0] || state.catalog.section || "";
    const subcategories = new Set(
        uniqueValues(sectionProducts.flatMap(item => getFilterSubcategoryValues(item, effectiveSection)).filter(Boolean))
            .filter(name => !cultureKeys.has(normalize(name)))
    );
    const isSeedsSection = normalize(effectiveSection) === normalize("Семена");
    draft.manufacturers = draft.manufacturers.filter(item => manufacturers.has(item));
    draft.cultures = draft.cultures.filter(item => cultures.has(item));
    draft.activeIngredients = draft.activeIngredients.filter(item => activeIngredients.has(item));
    draft.subcategories = draft.subcategories.filter(item => subcategories.has(item));
    if (isSeedsSection) {
        const faoRanges = new Set(SEED_FAO_RANGES.map(item => item.label));
        const maturityGroups = new Set(SEED_MATURITY_GROUPS);
        const technologies = new Set(SEED_TREATMENT_TECHNOLOGIES);
        draft.seedFaoRanges = (draft.seedFaoRanges || []).filter(item => faoRanges.has(item));
        draft.seedMaturityGroups = (draft.seedMaturityGroups || []).filter(item => maturityGroups.has(item));
        draft.seedTreatmentTechnologies = (draft.seedTreatmentTechnologies || []).filter(item => technologies.has(item));
        return;
    }
    draft.seedFaoRanges = [];
    draft.seedMaturityGroups = [];
    draft.seedTreatmentTechnologies = [];
}

function ensureCatalogDraft() {
    if (!state.catalog.draft) {
        state.catalog.draft = cloneFilters(state.catalog.applied || emptyFilters());
    }
}

function syncAppliedFiltersFromDraft() {
    trimDraftFiltersToAvailable();
    state.catalog.applied = cloneFilters(state.catalog.draft || emptyFilters());
    renderPreservingFocus();
}

function toggleDraftFilterArray(key, value, checked) {
    const list = state.catalog.draft[key] || [];
    state.catalog.draft[key] = checked ? [...list, value] : list.filter(item => item !== value);
}

function getProductFilterRawValue(product, keys = []) {
    const filterMap = product?.filterMap || {};
    const rawData = product?.rawData || {};
    for (const key of keys) {
        const direct = [filterMap[key], rawData[key]];
        for (const value of direct) {
            const text = String(value || "").trim();
            if (text) {
                return text;
            }
        }
        const normalizedKey = normalize(key);
        for (const [mapKey, mapValue] of Object.entries(filterMap)) {
            if (normalize(mapKey) === normalizedKey && String(mapValue || "").trim()) {
                return String(mapValue).trim();
            }
        }
        for (const [mapKey, mapValue] of Object.entries(rawData)) {
            if (normalize(mapKey) === normalizedKey && String(mapValue || "").trim()) {
                return String(mapValue).trim();
            }
        }
    }
    return "";
}

function getProductSeedSearchText(product) {
    return normalize([
        product?.name,
        product?.description,
        ...Object.entries(product?.filterMap || {}).flatMap(([key, value]) => [key, value]),
        ...Object.entries(product?.rawData || {}).flatMap(([key, value]) => [key, value]),
        ...(product?.tags || []),
        ...(product?.cultures || []),
    ].join(" "));
}

function getSeedFaoNumber(product) {
    const explicit = getProductFilterRawValue(product, ["FAO", "ФАО", "fao"]);
    const textCandidates = [explicit, product?.name, product?.description, getProductSeedSearchText(product)]
        .map(value => String(value || ""))
        .filter(Boolean);
    for (const text of textCandidates) {
        const faoMatch = text.match(/(?:фао|fao)\D{0,6}(\d{2,3})/i);
        if (faoMatch) {
            return Number(faoMatch[1]);
        }
    }
    return null;
}

function getSeedFaoRangeLabel(product) {
    const fao = getSeedFaoNumber(product);
    if (!Number.isFinite(fao)) {
        return "";
    }
    const range = SEED_FAO_RANGES.find(item => (item.min == null || fao >= item.min) && (item.max == null || fao <= item.max));
    return range?.label || "";
}

function getSeedMaturityGroup(product) {
    const explicit = getProductFilterRawValue(product, ["Группа спелости", "группа спелости", "maturityGroup", "maturity_group", "groupMaturity"]);
    const haystack = normalize([explicit, product?.description, product?.name, getProductSeedSearchText(product)].join(" "));
    const matched = SEED_MATURITY_GROUPS.find(label => haystack.includes(normalize(label)));
    return matched || "";
}

function getSeedTreatmentTechnology(product) {
    const explicit = getProductFilterRawValue(product, ["Технология обработки", "технология обработки", "technology", "seedTechnology", "treatmentTechnology"]);
    const haystack = normalize([explicit, product?.description, product?.name, getProductSeedSearchText(product)].join(" "));
    if (haystack.includes("clearfield") || haystack.includes("чистое поле")) {
        return "Технология Clearfield («чистое поле»)";
    }
    if (haystack.includes("expresssun") || haystack.includes("express san") || haystack.includes("expresssun") || haystack.includes("экспресс")) {
        return "Технология экспресс (ExpressSun)";
    }
    if (haystack.includes("классическ")) {
        return "Классическая технология";
    }
    return "";
}

function getProductById(productId) {
    return state.products.find(product => product.id === productId) || null;
}

function normalize(value) {
    return String(value || "").toLowerCase().replace(/\s+/g, " ").trim();
}

function uniqueValues(values) {
    const deduped = new Map();
    values.forEach(value => {
        const raw = String(value || "").replace(/\s+/g, " ").trim();
        if (!raw) return;
        const key = normalize(raw);
        const current = deduped.get(key);
        if (!current || shouldPreferDisplayValue(raw, current)) {
            deduped.set(key, raw);
        }
    });
    return [...deduped.values()].sort((a, b) => String(a).localeCompare(String(b), "ru", { sensitivity: "base" }));
}

function shouldPreferDisplayValue(candidate, current) {
    const candidateStartsUpper = /^[A-ZА-ЯЁ]/.test(candidate);
    const currentStartsUpper = /^[A-ZА-ЯЁ]/.test(current);
    if (candidateStartsUpper !== currentStartsUpper) {
        return candidateStartsUpper;
    }
    return candidate.length < current.length;
}

function getSubcategoryFilterTitle(sectionName) {
    const normalized = normalize(sectionName);
    if (normalized.includes("семен")) {
        return "Культура";
    }
    if (normalized.includes("пестиц")) {
        return "Тип пестицида";
    }
    if (normalized === normalize("ПАВы")) {
        return "Тип ПАВ";
    }
    return "Подкатегория";
}

function applyPendingFilterFocus() {
    if (!state.catalog.filtersOpen || !state.catalog.filterFocus) {
        return;
    }
    const anchor = root.querySelector(`[data-filter-anchor="${CSS.escape(state.catalog.filterFocus)}"]`);
    if (!anchor) {
        return;
    }
    requestAnimationFrame(() => {
        anchor.scrollIntoView({ behavior: "smooth", block: "start" });
    });
}

function getFilterSubcategoryValues(product, sectionName) {
    const effectiveSection = sectionName || getProductSectionName(product);
    return uniqueValues([mapProductToSectionSubcategory(product, effectiveSection)].filter(Boolean));
}

function normalizeFilterLabel(value) {
    const raw = String(value || "").replace(/\s+/g, " ").trim();
    if (!raw) {
        return "";
    }
    const normalized = normalize(raw);
    if (normalized.includes("фунгиц")) return "Фунгициды";
    if (normalized.includes("гербиц")) return "Гербициды";
    if (normalized.includes("инсекти")) return "Инсектициды";
    if (normalized.includes("десикант")) return "Десиканты";
    if (normalized.includes("нематоцид")) return "Нематоциды";
    if (normalized.includes("бактерицид")) return "Бактерициды";
    if (normalized.includes("акарицид")) return "Акарициды";
    if (normalized.includes("моллюскоцид")) return "Моллюскоциды";
    if (normalized.includes("зооцид")) return "Зооциды";
    if (normalized.includes("протрав")) return "Протравители";
    if (normalized.includes("роденти")) return "Зооциды";
    if (normalized.includes("репелент")) return "Зооциды";
    if (normalized.includes("регулятор рост")) return "Регуляторы роста растений";
    if (normalized.includes("инокулянт")) return "Инокулянты";
    if (normalized.includes("прилипател")) return "Прилипатели";
    if (normalized.includes("биостимулятор")) return "Биостимуляторы";
    if (normalized.includes("красител")) return "Протравители";
    if (normalized.includes("адъюв") || normalized.includes("адьюв") || normalized.includes("пав")) return "ПАВы";
    if (normalized.includes("микроудобр")) return "Микроудобрения";
    if (normalized.includes("удобр")) return "Удобрения";
    if (normalized.includes("пеногас")) return "Пеногасители";
    return raw;
}

function formatSeedGroupLabel(value) {
    const normalized = normalize(value);
    if (normalized === "зерновые") return "Озимая пшеница";
    if (normalized === "бобовые") return "Горох";
    if (normalized === "масличные") return "Подсолнечник";
    if (normalized === "технические") return "Рапс";
    if (normalized === "травосмеси") return "Травосмеси";
    if (normalized === "злаковые травы") return "Злаковые травы";
    if (normalized === "бобовые травы") return "Бобовые травы";
    return String(value || "").replace(/\s+/g, " ").trim();
}

function toStringArray(value) {
    if (Array.isArray(value)) {
        return value
            .map(item => String(item || "").trim())
            .filter(Boolean);
    }
    const raw = String(value || "").trim();
    return raw ? [raw] : [];
}

function parseOptionalNumber(value) {
    if (value == null || String(value).trim() === "") return null;
    const parsed = Number(String(value).replace(",", "."));
    return Number.isFinite(parsed) ? parsed : null;
}

function normalizePrice(value) {
    const parsed = parseOptionalNumber(value);
    return parsed == null || parsed <= 0 ? null : parsed;
}

function formatPrice(value) {
    const amount = Number(value || 0);
    return `${amount.toLocaleString("ru-RU", { maximumFractionDigits: 2 })} ₽`;
}

function formatQuantity(value) {
    const amount = Number(value || 0);
    return Number.isInteger(amount) ? String(amount) : amount.toLocaleString("ru-RU", { maximumFractionDigits: 3 });
}

function roundQuantity(value) {
    return Math.round(Number(value || 0) * 1000) / 1000;
}

function formatDate(value) {
    if (!value) return "—";
    return new Date(value).toLocaleString("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
    });
}

async function fetchJson(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        let message = response.status === 413
            ? "Файл слишком большой. Допустимый размер вложения: 25 МБ."
            : `Ошибка ${response.status}`;
        try {
            const data = await response.json();
            message = data.message || data.error || message;
        } catch (error) {
            // ignore json parse errors
        }
        throw new Error(message);
    }
    return response.json();
}

function handleActionError(error) {
    console.error(error);
    showNotice(error.message || "Что-то пошло не так", "error");
    if (state.admin.broadcastForm.sending) {
        state.admin.broadcastForm.sending = false;
        render();
    }
    if (state.checkout.submitting) {
        state.checkout.submitting = false;
        render();
    }
}

function showNotice(message, type = "success") {
    state.notice = {
        open: true,
        message: String(message || "").trim(),
        type,
    };
    if (noticeTimer) {
        window.clearTimeout(noticeTimer);
    }
    noticeTimer = window.setTimeout(() => {
        state.notice = { open: false, message: "", type: "success" };
        noticeTimer = null;
        render();
    }, 2200);
}

function loadStorage(key, fallback) {
    try {
        const raw = window.localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
    } catch (error) {
        return fallback;
    }
}

function saveStorage(key, value) {
    try {
        window.localStorage.setItem(key, JSON.stringify(value));
    } catch (error) {
        console.warn("Storage save failed", error);
    }
}

function saveCart() {
    saveStorage("alga-cart", state.cart);
    scheduleClientStateSync();
}

function scheduleClientStateSync(delay = 700) {
    if (!state.maxUserId) {
        return;
    }
    window.clearTimeout(clientStateSyncTimer);
    clientStateSyncTimer = window.setTimeout(() => {
        syncClientState().catch(error => console.warn("Client state sync failed", error));
    }, delay);
}

async function syncClientState() {
    if (!state.maxUserId) {
        return;
    }
    await fetchJson("/api/profile/state", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(buildClientStatePayload()),
    });
    if (state.profile?.admin) {
        state.admin.customers = await fetchJson(`/api/admin/customers?maxUserId=${state.maxUserId}`);
        if (state.admin.menu === "customers" || state.admin.customerModal.open) {
            render();
        }
    }
}

function buildClientStatePayload() {
    return {
        maxUserId: state.maxUserId,
        displayName: resolveClientDisplayName(),
        username: resolveClientUsername(),
        cartItems: state.cart.map(item => ({
            productId: item.productId,
            quantity: item.quantity,
        })),
        checkoutDraft: state.checkout.touched
            ? {
                organization: state.checkout.form.organization.trim(),
                farmName: state.checkout.form.farmName.trim(),
                inn: state.checkout.form.inn.trim(),
                phone: state.checkout.form.phone.trim(),
                email: state.checkout.form.email.trim(),
                address: state.checkout.form.address.trim(),
                comment: state.checkout.form.comment.trim(),
                attachments: state.checkout.uploaded || [],
            }
            : {},
    };
}

function resolveClientDisplayName() {
    return [
        initDataUnsafe?.user?.name,
        [initDataUnsafe?.user?.first_name, initDataUnsafe?.user?.last_name].filter(Boolean).join(" ").trim(),
        state.profile?.displayName,
    ].find(value => String(value || "").trim()) || "Пользователь MAX";
}

function resolveClientUsername() {
    return [
        initDataUnsafe?.user?.username,
        state.profile?.username,
    ].find(value => String(value || "").trim()) || "";
}

function hasCheckoutDraftContent(draft) {
    if (!draft) {
        return false;
    }
    return Boolean(
        draft.organization ||
        draft.farmName ||
        draft.inn ||
        draft.phone ||
        draft.email ||
        draft.address ||
        draft.comment ||
        (Array.isArray(draft.attachments) && draft.attachments.length)
    );
}

function resolveMaxUserId() {
    const candidates = [
        initDataUnsafe?.user?.id,
        initDataUnsafe?.user?.user_id,
        bridgeUserId,
        queryUserId,
        window.localStorage?.getItem("algaAgroMaxUserId"),
    ];
    for (const candidate of candidates) {
        const parsed = Number(candidate);
        if (Number.isFinite(parsed) && parsed > 0) {
            return parsed;
        }
    }
    return null;
}

function parseMaxUserIdFromBridgeInitData() {
    const raw = maxBridge?.initData;
    if (!raw || typeof raw !== "string") {
        return null;
    }
    try {
        const params = new URLSearchParams(raw);
        const directCandidate = Number(params.get("user_id") || params.get("userId") || "");
        if (Number.isFinite(directCandidate) && directCandidate > 0) {
            return directCandidate;
        }
        const userJson = params.get("user");
        if (!userJson) {
            return null;
        }
        const parsedUser = JSON.parse(userJson);
        const nestedCandidate = Number(parsedUser?.id || parsedUser?.user_id || 0);
        return Number.isFinite(nestedCandidate) && nestedCandidate > 0 ? nestedCandidate : null;
    } catch (error) {
        console.warn("Failed to parse MAX initData", error);
        return null;
    }
}

function rememberMaxUserId(userId) {
    if (!userId) return;
    try {
        window.localStorage.setItem("algaAgroMaxUserId", String(userId));
    } catch (error) {
        console.warn("Failed to remember maxUserId", error);
    }
}

function shouldShowBackButton() {
    return Boolean(state.catalog.section || state.checkout.open || state.productModal.open || state.admin.orderModal.open || state.admin.productEditor.open || state.admin.manufacturerModal.open);
}

function openManagerLink() {
    const primary = state.meta?.managerMaxLink || state.profile?.managerMaxLink || "";
    const fallback = state.meta?.managerExternalLink || state.profile?.managerExternalLink || "";
    if (maxBridge?.openLink && primary) {
        maxBridge.openLink(primary);
        return;
    }
    const target = primary && !primary.startsWith("max://") ? primary : fallback || primary || "#";
    window.open(target, "_blank");
}

function escapeHtml(value) {
    return String(value || "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function escapeAttr(value) {
    return escapeHtml(value);
}
