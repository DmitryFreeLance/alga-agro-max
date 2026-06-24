const BASE_GROUPS = [
    { key: "seeds", title: "Семена", iconPath: "./assets/category-seeds.svg", visualLabel: "Семена", description: "Посевной материал", matchers: ["сем", "озим", "яров", "гибрид", "сорт"], fallbackCategories: ["Семена"] },
    { key: "pesticides", title: "Пестициды", iconPath: "./assets/category-pesticides.svg", visualLabel: "Защита", description: "Защита растений", matchers: ["пестиц", "гербиц", "фунгиц", "инсекти", "протрав", "десикан", "обработка семян", "зср"], fallbackCategories: ["Пестициды"] },
    { key: "nutrition", title: "Агропитание", iconPath: "./assets/category-nutrition.svg", visualLabel: "Питание", description: "Питание и стимуляция", matchers: ["удоб", "микро", "стим", "адъюв", "питан", "биостим", "листов", "изагри"], fallbackCategories: ["Агропитание"] },
];

const state = {
    meta: null,
    profile: null,
    profileOrders: [],
    adminOrders: [],
    adminProducts: [],
    adminUi: { search: "", category: "all" },
    filters: { cultures: [], categories: [], tags: [], seasons: [] },
    products: [],
    selection: { culture: "", season: "", category: "", search: "", sort: "name", group: "" },
    cart: [],
    currentPage: "catalog",
    maxUserId: null,
    catalogMode: "catalog",
};

const nodes = {
    homePage: document.getElementById("homePage"),
    catalogPage: document.getElementById("catalogPage"),
    resultsPage: document.getElementById("resultsPage"),
    catalogResultsCard: document.getElementById("catalogResultsCard"),
    profilePage: document.getElementById("profilePage"),
    checkoutPage: document.getElementById("checkoutPage"),
    catalogBottomButton: document.getElementById("catalogBottomButton"),
    cartBottomButton: document.getElementById("cartBottomButton"),
    cartBottomBadge: document.getElementById("cartBottomBadge"),
    profileNavButton: document.getElementById("profileNavButton"),
    pageTitle: document.getElementById("pageTitle"),
    pageCaption: document.getElementById("pageCaption"),
    openCatalogFromHome: document.getElementById("openCatalogFromHome"),
    openCartFromHome: document.getElementById("openCartFromHome"),
    homeGroupGrid: document.getElementById("homeGroupGrid"),
    catalogModePanel: document.getElementById("catalogModePanel"),
    cartModePanel: document.getElementById("cartModePanel"),
    searchInput: document.getElementById("searchInput"),
    clearSearchButton: document.getElementById("clearSearchButton"),
    sortSelect: document.getElementById("sortSelect"),
    cultureChips: document.getElementById("cultureChips"),
    catalogSeasonFilterBlock: document.getElementById("catalogSeasonFilterBlock"),
    catalogSeasonChips: document.getElementById("catalogSeasonChips"),
    seasonFilterBlock: document.getElementById("seasonFilterBlock"),
    seasonChips: document.getElementById("seasonChips"),
    categoryPills: document.getElementById("categoryPills"),
    groupGrid: document.getElementById("groupGrid"),
    productGrid: document.getElementById("productGrid"),
    emptyState: document.getElementById("emptyState"),
    catalogTitle: document.getElementById("catalogTitle"),
    catalogCount: document.getElementById("catalogCount"),
    cartSummaryCount: document.getElementById("cartSummaryCount"),
    cartItems: document.getElementById("cartItems"),
    cartTotal: document.getElementById("cartTotal"),
    checkoutButton: document.getElementById("checkoutButton"),
    checkoutForm: document.getElementById("checkoutForm"),
    backToCart: document.getElementById("backToCart"),
    backToCatalog: document.getElementById("backToCatalog"),
    profileAvatar: document.getElementById("profileAvatar"),
    profileName: document.getElementById("profileName"),
    profileRole: document.getElementById("profileRole"),
    profileOrdersCount: document.getElementById("profileOrdersCount"),
    profileStatus: document.getElementById("profileStatus"),
    profileOrders: document.getElementById("profileOrders"),
    profileOrdersSection: document.getElementById("profileOrdersSection"),
    adminSection: document.getElementById("adminSection"),
    adminOrdersPreviewButton: document.getElementById("adminOrdersPreviewButton"),
    adminOrdersPreview: document.getElementById("adminOrdersPreview"),
    adminOrdersHint: document.getElementById("adminOrdersHint"),
    adminProducts: document.getElementById("adminProducts"),
    adminOrders: document.getElementById("adminOrders"),
    addProductButton: document.getElementById("addProductButton"),
    adminProductSearch: document.getElementById("adminProductSearch"),
    adminProductClearSearch: document.getElementById("adminProductClearSearch"),
    adminCategoryFilter: document.getElementById("adminCategoryFilter"),
    headerBackButton: document.getElementById("headerBackButton"),
    toast: document.getElementById("toast"),
    productModal: document.getElementById("productModal"),
    productModalBackdrop: document.getElementById("productModalBackdrop"),
    productModalClose: document.getElementById("productModalClose"),
    productModalBadges: document.getElementById("productModalBadges"),
    productModalType: document.getElementById("productModalType"),
    productModalTitle: document.getElementById("productModalTitle"),
    productModalDescription: document.getElementById("productModalDescription"),
    productModalTags: document.getElementById("productModalTags"),
    productModalStock: document.getElementById("productModalStock"),
    productModalPrice: document.getElementById("productModalPrice"),
    productModalQuantity: document.getElementById("productModalQuantity"),
    productModalUnit: document.getElementById("productModalUnit"),
    productModalTotal: document.getElementById("productModalTotal"),
    productModalAddButton: document.getElementById("productModalAddButton"),
    checkoutModal: document.getElementById("checkoutModal"),
    checkoutModalBackdrop: document.getElementById("checkoutModalBackdrop"),
    checkoutModalClose: document.getElementById("checkoutModalClose"),
    checkoutModalCancel: document.getElementById("checkoutModalCancel"),
    checkoutModalForm: document.getElementById("checkoutModalForm"),
    checkoutModalTotal: document.getElementById("checkoutModalTotal"),
    adminProductModal: document.getElementById("adminProductModal"),
    adminProductModalBackdrop: document.getElementById("adminProductModalBackdrop"),
    adminProductModalClose: document.getElementById("adminProductModalClose"),
    adminProductForm: document.getElementById("adminProductForm"),
    adminProductCancelButton: document.getElementById("adminProductCancelButton"),
    adminProductDeleteButton: document.getElementById("adminProductDeleteButton"),
    adminOrdersModal: document.getElementById("adminOrdersModal"),
    adminOrdersModalBackdrop: document.getElementById("adminOrdersModalBackdrop"),
    adminOrdersModalClose: document.getElementById("adminOrdersModalClose"),
};

const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
const queryUserId = new URLSearchParams(window.location.search).get("maxUserId");
const MIN_SEARCH_LENGTH = 3;
let liveSearchTimer = null;
state.maxUserId = resolveMaxUserId();

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

bootstrap();

async function bootstrap() {
    bindEvents();
    await Promise.all([loadMeta(), loadFilters()]);
    const initialLoads = await Promise.allSettled([loadProfile(), loadProducts()]);
    initialLoads.forEach(result => {
        if (result.status === "rejected") {
            showToast(result.reason?.message || "Часть данных пока недоступна");
        }
    });
    renderHomeGroups();
    renderCatalogGroups();
    renderCart();
    showPage("catalog");
}

function bindEvents() {
    nodes.catalogBottomButton.addEventListener("click", () => showPage("catalog"));
    nodes.cartBottomButton.addEventListener("click", () => showPage("cart"));
    nodes.profileNavButton.addEventListener("click", () => showPage("profile"));
    nodes.openCatalogFromHome?.addEventListener("click", () => showPage("catalog"));
    nodes.openCartFromHome?.addEventListener("click", () => {
        showPage("cart");
    });
    nodes.searchInput.addEventListener("input", event => {
        state.selection.search = event.target.value.trim();
        syncSearchUi();
        scheduleLiveCatalogUpdate();
    });
    nodes.searchInput.addEventListener("keydown", event => {
        if (event.key === "Enter") {
            event.preventDefault();
            runSearchAndOpenResults();
        }
    });
    nodes.clearSearchButton.addEventListener("click", async () => {
        state.selection.search = "";
        nodes.searchInput.value = "";
        syncSearchUi();
        await refreshCatalogPresentation();
    });
    nodes.sortSelect.addEventListener("change", async event => {
        state.selection.sort = event.target.value;
        if (hasActiveCatalogQuery()) {
            await refreshCatalogPresentation();
        }
    });
    nodes.checkoutButton.addEventListener("click", () => {
        if (!state.cart.length) {
            showToast("Сначала добавьте товары в корзину");
            return;
        }
        openCheckoutModal();
    });
    nodes.backToCart.addEventListener("click", () => {
        showPage("cart");
    });
    nodes.backToCatalog.addEventListener("click", () => {
        showPage("catalog");
        setCatalogMode("catalog");
    });
    nodes.checkoutForm.addEventListener("submit", submitOrder);
    nodes.checkoutModalForm.addEventListener("submit", submitOrder);
    nodes.addProductButton?.addEventListener("click", () => renderProductEditor(null));
    nodes.adminOrdersPreviewButton?.addEventListener("click", openAdminOrdersModal);
    nodes.adminProductSearch?.addEventListener("input", () => {
        state.adminUi.search = nodes.adminProductSearch.value.trim();
        syncAdminSearchUi();
        renderAdminProducts();
    });
    nodes.adminProductClearSearch?.addEventListener("click", () => {
        state.adminUi.search = "";
        nodes.adminProductSearch.value = "";
        syncAdminSearchUi();
        renderAdminProducts();
    });
    nodes.adminCategoryFilter?.addEventListener("change", () => {
        state.adminUi.category = nodes.adminCategoryFilter.value;
        renderAdminProducts();
    });
    nodes.headerBackButton.addEventListener("click", async () => {
        if (state.currentPage === "checkout") {
            await resetCatalogSelection();
            showPage("cart");
        } else if (state.currentPage === "results") {
            await resetCatalogSelection();
            showPage("catalog");
            setCatalogMode("catalog");
        } else if (state.currentPage === "catalog") {
            await resetCatalogSelection();
            setCatalogMode("catalog");
            showPage("catalog");
        } else if (state.currentPage === "cart") {
            showPage("catalog");
            setCatalogMode("catalog");
        } else {
            showPage("catalog");
        }
    });
    nodes.productModalBackdrop.addEventListener("click", closeProductModal);
    nodes.productModalClose.addEventListener("click", closeProductModal);
    nodes.productModalQuantity.addEventListener("input", syncProductModalTotal);
    nodes.productModalAddButton.addEventListener("click", () => {
        const productId = Number(nodes.productModalAddButton.dataset.productId);
        const product = state.products.find(item => item.id === productId);
        if (product) {
            addToCart(product, parseQuantity(nodes.productModalQuantity.value));
            closeProductModal();
        }
    });
    nodes.checkoutModalBackdrop.addEventListener("click", closeCheckoutModal);
    nodes.checkoutModalClose.addEventListener("click", closeCheckoutModal);
    nodes.checkoutModalCancel.addEventListener("click", closeCheckoutModal);
    nodes.adminProductModalBackdrop?.addEventListener("click", closeAdminProductModal);
    nodes.adminProductModalClose?.addEventListener("click", closeAdminProductModal);
    nodes.adminProductCancelButton?.addEventListener("click", closeAdminProductModal);
    nodes.adminProductForm?.addEventListener("submit", submitAdminProductForm);
    nodes.adminProductDeleteButton?.addEventListener("click", handleAdminDeleteFromModal);
    nodes.adminOrdersModalBackdrop?.addEventListener("click", closeAdminOrdersModal);
    nodes.adminOrdersModalClose?.addEventListener("click", closeAdminOrdersModal);
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !nodes.productModal.classList.contains("hidden")) {
            closeProductModal();
        }
        if (event.key === "Escape" && !nodes.checkoutModal.classList.contains("hidden")) {
            closeCheckoutModal();
        }
        if (event.key === "Escape" && !nodes.adminProductModal.classList.contains("hidden")) {
            closeAdminProductModal();
        }
        if (event.key === "Escape" && !nodes.adminOrdersModal.classList.contains("hidden")) {
            closeAdminOrdersModal();
        }
    });
}

async function loadMeta() {
    state.meta = await fetchJson("/api/meta");
}

async function loadFilters() {
    const query = new URLSearchParams();
    if (state.selection.culture) query.set("culture", state.selection.culture);
    state.filters = await fetchJson(`/api/catalog/filters${query.toString() ? `?${query.toString()}` : ""}`);
    renderFilterPills();
}

async function loadProducts() {
    const query = new URLSearchParams();
    if (state.selection.culture) query.set("culture", state.selection.culture);
    if (state.selection.season) query.set("season", state.selection.season);
    if (state.selection.category) query.set("category", state.selection.category);
    const effectiveSearchQuery = getEffectiveSearchQuery();
    if (effectiveSearchQuery) query.set("search", effectiveSearchQuery);
    if (state.selection.sort) query.set("sort", state.selection.sort);
    state.products = await fetchJson(`/api/catalog/products?${query.toString()}`);
    renderProducts();
    renderCatalogGroups();
}

async function loadProfile() {
    if (!state.maxUserId) {
        state.profile = {
            displayName: "Пользователь MAX",
            admin: false,
            ordersCount: 0,
        };
        state.profileOrders = [];
        renderProfile();
        return;
    }
    state.profile = await fetchJson(`/api/profile?maxUserId=${state.maxUserId}`);
    state.profileOrders = await fetchJson(`/api/profile/orders?maxUserId=${state.maxUserId}`);
    renderProfile();
    if (state.profile.admin) {
        rememberMaxUserId(state.maxUserId);
        const [products, orders] = await Promise.all([
            fetchJson(`/api/admin/products?maxUserId=${state.maxUserId}`),
            fetchJson(`/api/admin/orders?maxUserId=${state.maxUserId}`),
        ]);
        state.adminProducts = products;
        state.adminOrders = orders;
        renderAdminSection();
    } else if (state.maxUserId) {
        rememberMaxUserId(state.maxUserId);
    }
}

function resolveMaxUserId() {
    const candidates = [
        initDataUnsafe?.user?.id,
        initDataUnsafe?.user?.user_id,
        parseUserIdFromInitData(maxBridge?.initData),
        parseUserIdFromInitData(window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash),
        parseUserIdFromInitData(window.location.search.startsWith("?") ? window.location.search.slice(1) : window.location.search),
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

function parseUserIdFromInitData(rawValue) {
    if (!rawValue || typeof rawValue !== "string") {
        return null;
    }
    try {
        const params = new URLSearchParams(rawValue);
        const directUserId = params.get("user_id");
        if (directUserId) {
            return directUserId;
        }
        const userRaw = params.get("user");
        if (!userRaw) {
            return null;
        }
        const parsedUser = JSON.parse(userRaw);
        return parsedUser?.id || parsedUser?.user_id || null;
    } catch (error) {
        console.warn("Failed to parse MAX initData", error);
        return null;
    }
}

function rememberMaxUserId(userId) {
    if (!userId) {
        return;
    }
    try {
        window.localStorage?.setItem("algaAgroMaxUserId", String(userId));
    } catch (error) {
        console.warn("Failed to store MAX user id", error);
    }
}

function showPage(page) {
    state.currentPage = page;
    nodes.homePage?.classList.toggle("page-active", false);
    nodes.catalogPage.classList.toggle("page-active", page === "catalog" || page === "cart" || page === "results");
    nodes.resultsPage.classList.toggle("page-active", false);
    nodes.profilePage.classList.toggle("page-active", page === "profile");
    nodes.checkoutPage?.classList.toggle("page-active", page === "checkout");
    nodes.catalogBottomButton.classList.toggle("active", page === "catalog" || page === "results");
    nodes.cartBottomButton.classList.toggle("active", page === "cart");
    nodes.profileNavButton.classList.toggle("active", page === "profile");
    if (page === "cart") {
        setCatalogMode("cart");
    } else if (page === "catalog") {
        setCatalogMode("catalog");
    }
    const meta = {
        catalog: ["Каталог", "Подбор товаров по культуре, группе и фильтрам."],
        cart: ["Корзина", "Список товаров, объем и итоговая сумма заказа."],
        results: ["Товары", "Подходящие позиции по выбранным параметрам."],
        profile: ["Профиль", "Ваши заказы и управление данными."],
        checkout: ["Оформление", "Отправка заявки администратору."],
    }[page] || ["Каталог", ""];
    nodes.pageTitle.textContent = meta[0];
    nodes.pageCaption.textContent = meta[1];
    nodes.pageCaption.classList.toggle("hidden", !meta[1]);
}

function hasActiveCatalogQuery() {
    return Boolean(
        getEffectiveSearchQuery()
        || state.selection.culture
        || state.selection.season
        || state.selection.category
        || state.selection.group
    );
}

function getEffectiveSearchQuery() {
    const query = (state.selection.search || "").trim();
    return query.length >= MIN_SEARCH_LENGTH ? query : "";
}

function scheduleLiveCatalogUpdate() {
    window.clearTimeout(liveSearchTimer);
    liveSearchTimer = window.setTimeout(() => {
        refreshCatalogPresentation().catch(error => {
            console.error("Live catalog update failed", error);
            showToast("Не удалось обновить каталог");
        });
    }, 220);
}

async function refreshCatalogPresentation() {
    await loadProducts();
    syncCatalogResultsVisibility();
    if (shouldKeepCatalogPageOpen() || hasActiveCatalogQuery()) {
        if (state.currentPage !== "cart" && state.currentPage !== "profile" && state.currentPage !== "checkout") {
            showPage("catalog");
        }
    } else if (state.currentPage === "results") {
        showPage("catalog");
    }
}

function syncCatalogResultsVisibility() {
    const shouldShow = hasActiveCatalogQuery() && !shouldKeepCatalogPageOpen();
    nodes.catalogResultsCard?.classList.toggle("hidden", !shouldShow);
}

function shouldKeepCatalogPageOpen() {
    return Boolean(
        state.selection.culture
        && !state.selection.season
        && !getEffectiveSearchQuery()
        && !state.selection.category
        && !state.selection.group
        && (state.filters.seasons || []).length
    );
}

function setCatalogMode(mode) {
    state.catalogMode = mode;
    nodes.catalogModePanel.classList.toggle("mode-panel-active", mode === "catalog");
    nodes.cartModePanel.classList.toggle("mode-panel-active", mode === "cart");
}

function renderHomeGroups() {
    nodes.homeGroupGrid.innerHTML = "";
    getGroupDefinitions().forEach(group => nodes.homeGroupGrid.appendChild(buildGroupCard(group, false)));
}

function renderCatalogGroups() {
    nodes.groupGrid.innerHTML = "";
    getGroupDefinitions().forEach(group => nodes.groupGrid.appendChild(buildGroupCard(group, true)));
}

function buildGroupCard(group, interactive) {
    const visibleProducts = filterProductsByGroup(state.products, group);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "group-card";
    if (state.selection.group === group.key) button.classList.add("active");
    button.innerHTML = `
        <div class="group-illustration">
            <span class="group-visual-tag">${escapeHtml(group.visualLabel || shortGroupLabel(group.title))}</span>
            <img class="group-icon-asset" src="${escapeAttr(group.iconPath)}" alt="${escapeAttr(group.title)}">
        </div>
        <div class="group-card-footer">
            <p class="group-name">${escapeHtml(group.title)}</p>
            <span class="group-count">${visibleProducts.length}</span>
        </div>
    `;
    if (interactive) {
        button.addEventListener("click", async () => {
            state.selection.group = group.key;
            state.selection.category = group.exactCategory || "";
            renderCatalogGroups();
            renderFilterPills();
            await refreshCatalogPresentation();
        });
    } else {
        button.addEventListener("click", async () => {
            showPage("catalog");
            state.selection.group = group.key;
            state.selection.category = group.exactCategory || "";
            renderCatalogGroups();
            renderFilterPills();
            await refreshCatalogPresentation();
        });
    }
    return button;
}

function renderFilterPills() {
    renderSelectableGroup(nodes.cultureChips, ["Все культуры", ...state.filters.cultures], state.selection.culture, async value => {
        state.selection.culture = value === "Все культуры" ? "" : value;
        state.selection.season = "";
        await loadFilters();
        await refreshCatalogPresentation();
    });
    renderSeasonPills();
    renderSelectableGroup(nodes.categoryPills, ["Все категории", ...state.filters.categories], state.selection.category, async value => {
        state.selection.category = value === "Все категории" ? "" : value;
        state.selection.group = "";
        renderFilterPills();
        renderCatalogGroups();
        await refreshCatalogPresentation();
    });
}

function renderSeasonPills() {
    const seasonOptions = (state.filters.seasons || []).filter(Boolean);
    const hasCulture = Boolean(state.selection.culture);
    const showSeasonBlock = hasCulture && seasonOptions.length > 0;
    nodes.catalogSeasonFilterBlock.classList.toggle("hidden", !showSeasonBlock);
    nodes.seasonFilterBlock.classList.toggle("hidden", !showSeasonBlock);
    if (!showSeasonBlock) {
        state.selection.season = "";
        nodes.catalogSeasonChips.innerHTML = "";
        nodes.seasonChips.innerHTML = "";
        return;
    }
    if (state.selection.season && !seasonOptions.includes(state.selection.season)) {
        state.selection.season = "";
    }
    const items = ["Все сезоны", ...seasonOptions];
    const onSeasonClick = async value => {
        state.selection.season = value === "Все сезоны" ? "" : value;
        renderSeasonPills();
        await refreshCatalogPresentation();
    };
    renderSelectableGroup(nodes.catalogSeasonChips, items, state.selection.season, onSeasonClick);
    renderSelectableGroup(nodes.seasonChips, items, state.selection.season, onSeasonClick);
}

function renderSelectableGroup(container, items, selected, onClick) {
    container.innerHTML = "";
    items.forEach(item => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "chip";
        button.textContent = item;
        const active = (selected || "") === item || (!selected && ["Все культуры", "Все", "Все категории", "Все сезоны"].includes(item));
        if (active) button.classList.add("active");
        button.addEventListener("click", () => onClick(item));
        container.appendChild(button);
    });
}

function renderProducts() {
    nodes.productGrid.innerHTML = "";
    const selectedGroup = getGroupDefinitions().find(group => group.key === state.selection.group);
    const visibleProducts = state.selection.group
        ? state.products.filter(product => filterProductsByGroup([product], selectedGroup).length)
        : state.products;
    nodes.catalogCount.textContent = `${visibleProducts.length} позиций`;
    nodes.catalogTitle.textContent = getCatalogTitle();
    if (!visibleProducts.length) {
        nodes.emptyState.classList.remove("hidden");
        return;
    }
    nodes.emptyState.classList.add("hidden");
    visibleProducts.forEach(product => {
        const card = document.createElement("article");
        card.className = "product-card";
        card.tabIndex = 0;
        card.innerHTML = `
            <div class="product-visual">
                <div class="product-badges">
                    <span class="badge">${product.category || "Товар"}</span>
                    ${product.subcategory ? `<span class="badge">${product.subcategory}</span>` : ""}
                </div>
                <strong>${escapeHtml(product.itemType || product.category || "АЛГА")}</strong>
            </div>
            <div class="product-copy">
                <h4 class="product-name">${escapeHtml(product.name)}</h4>
                <p class="product-description">${escapeHtml(compactDescription(product.description || "Товар доступен для заказа.", 132))}</p>
            </div>
            <div class="product-bottom">
                <div class="price">
                    <span class="stock">${product.stockQuantity == null ? "Наличие уточняется" : `Остаток: ${product.stockQuantity} ${product.unitName || ""}`}</span>
                    <strong>${formatPrice(product.price)}${product.unitName ? `/${escapeHtml(product.unitName)}` : ""}</strong>
                </div>
                <div class="product-actions">
                    <button class="primary-button small-button" type="button">Добавить</button>
                </div>
            </div>
        `;
        card.addEventListener("click", () => openProductModal(product));
        card.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                openProductModal(product);
            }
        });
        card.querySelector(".primary-button").addEventListener("click", event => {
            event.stopPropagation();
            openProductModal(product);
        });
        nodes.productGrid.appendChild(card);
    });
}

function openProductModal(product) {
    nodes.productModalBadges.innerHTML = `
        <span class="badge">${escapeHtml(product.category || "Товар")}</span>
        ${product.subcategory ? `<span class="badge">${escapeHtml(product.subcategory)}</span>` : ""}
    `;
    nodes.productModalType.textContent = product.itemType || product.category || "Товар";
    nodes.productModalTitle.textContent = product.name || "Товар";
    nodes.productModalDescription.textContent = product.description || "Товар доступен для заказа.";
    const modalTags = collectModalTags(product);
    nodes.productModalTags.innerHTML = modalTags.map(value => `<span class="badge">${escapeHtml(value)}</span>`).join("");
    nodes.productModalTags.classList.toggle("hidden", modalTags.length === 0);
    nodes.productModalStock.textContent = product.stockQuantity == null
        ? "Наличие уточняется"
        : `Остаток: ${product.stockQuantity} ${product.unitName || ""}`;
    nodes.productModalPrice.textContent = `${formatPrice(product.price)}${product.unitName ? `/${product.unitName}` : ""}`;
    nodes.productModalUnit.textContent = product.unitName || "шт";
    nodes.productModalQuantity.value = formatQuantityInput(getDefaultQuantity());
    nodes.productModalAddButton.dataset.productId = String(product.id);
    nodes.productModalAddButton.dataset.unitPrice = String(Number(product.price || 0));
    nodes.productModal.classList.remove("hidden");
    document.body.style.overflow = "hidden";
    syncProductModalTotal();
}

function collectModalTags(product) {
    const values = [
        ...(product.cultures || []),
        ...(product.tags || []),
        ...(product.purposes || []),
    ]
        .map(value => String(value || "").trim())
        .filter(Boolean);
    const unique = [...new Set(values)];
    const visibleLimit = 4;
    const visible = unique.slice(0, visibleLimit);
    const hiddenCount = unique.length - visible.length;
    if (hiddenCount > 0) {
        visible.push(`+${hiddenCount}`);
    }
    return visible;
}

function closeProductModal() {
    nodes.productModal.classList.add("hidden");
    document.body.style.overflow = "";
}

function openCheckoutModal() {
    nodes.checkoutModalForm.reset();
    nodes.checkoutModalTotal.textContent = nodes.cartTotal.textContent;
    nodes.checkoutModal.classList.remove("hidden");
    document.body.style.overflow = "hidden";
}

function closeCheckoutModal() {
    nodes.checkoutModal.classList.add("hidden");
    document.body.style.overflow = "";
}

async function resetCatalogSelection() {
    state.selection.culture = "";
    state.selection.season = "";
    state.selection.category = "";
    state.selection.search = "";
    state.selection.group = "";
    nodes.searchInput.value = "";
    window.clearTimeout(liveSearchTimer);
    syncSearchUi();
    await loadFilters();
    await loadProducts();
    renderCatalogGroups();
    syncCatalogResultsVisibility();
}

async function runSearchAndOpenResults() {
    state.selection.search = nodes.searchInput.value.trim();
    syncSearchUi();
    await refreshCatalogPresentation();
    if (hasActiveCatalogQuery()) {
        nodes.catalogResultsCard?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
}

function renderCart() {
    const totalCount = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const totalPrice = state.cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    nodes.cartSummaryCount.textContent = `${totalCount} ${pluralize(totalCount, ["товар", "товара", "товаров"])}`;
    nodes.cartTotal.textContent = formatPrice(totalPrice);
    nodes.checkoutModalTotal.textContent = formatPrice(totalPrice);
    nodes.cartBottomBadge.textContent = String(totalCount);
    nodes.cartBottomBadge.classList.toggle("hidden", totalCount <= 0);
    nodes.cartItems.innerHTML = "";
    if (!state.cart.length) {
        nodes.cartItems.innerHTML = `<div class="empty-state"><h4>Корзина пуста</h4><p>Добавьте товары из каталога.</p></div>`;
        return;
    }
    state.cart.forEach(item => {
        const row = document.createElement("div");
        row.className = "cart-item";
        row.innerHTML = `
            <div class="cart-row">
                <strong>${escapeHtml(item.name)}</strong>
                <span class="cart-line-total">${formatPrice(item.price * item.quantity)}</span>
                <button class="remove-btn" type="button">✕</button>
            </div>
            <div class="cart-row">
                <span>${formatPrice(item.price)} / ${escapeHtml(item.unitName)}</span>
                <div class="cart-qty">
                    <button class="qty-btn minus" type="button">−</button>
                    <input class="qty-input" type="number" min="1" step="1" value="${formatQuantityInput(item.quantity)}">
                    <button class="qty-btn plus" type="button">+</button>
                </div>
            </div>
        `;
        row.querySelector(".minus").addEventListener("click", () => updateQuantity(item.id, -getQuantityStep()));
        row.querySelector(".plus").addEventListener("click", () => updateQuantity(item.id, getQuantityStep()));
        row.querySelector(".qty-input").addEventListener("change", event => setQuantity(item.id, event.target.value));
        row.querySelector(".remove-btn").addEventListener("click", () => removeItem(item.id));
        nodes.cartItems.appendChild(row);
    });
}

function renderProfile() {
    nodes.profileName.textContent = state.profile.displayName || "Пользователь MAX";
    nodes.profileRole.textContent = state.profile.admin ? "Администратор каталога" : "Личный кабинет клиента";
    nodes.profileOrdersCount.textContent = state.profile.ordersCount || 0;
    nodes.profileStatus.textContent = state.profile.admin ? "Админ" : "Клиент";
    nodes.profileAvatar.textContent = initials(state.profile.displayName || "AA");
    nodes.profileOrdersSection.classList.toggle("hidden", Boolean(state.profile.admin));
    renderOrdersList(nodes.profileOrders, state.profileOrders, "У вас пока нет заказов.");
}

function renderAdminSection() {
    nodes.adminSection.classList.remove("hidden");
    renderAdminCategoryFilter();
    syncAdminSearchUi();
    renderAdminProducts();
    renderAdminOrdersPreview();
    renderOrdersList(nodes.adminOrders, state.adminOrders, "Заказов пока нет.");
}

function renderAdminOrdersPreview() {
    const lastOrder = state.adminOrders[0] ? [state.adminOrders[0]] : [];
    renderOrdersList(nodes.adminOrdersPreview, lastOrder, "Заказов пока нет.");
    if (nodes.adminOrdersHint) {
        nodes.adminOrdersHint.textContent = state.adminOrders.length > 1
            ? "Для просмотра всех заказов нажмите на этот блок."
            : state.adminOrders.length === 1
                ? "Показан последний заказ. Для просмотра списка нажмите на этот блок."
                : "Заказов пока нет.";
    }
}

function renderAdminProducts() {
    nodes.adminProducts.innerHTML = "";
    if (!state.adminProducts.length) {
        nodes.adminProducts.innerHTML = `<div class="empty-state"><h4>Товаров пока нет</h4><p>Добавьте первую позицию через кнопку выше.</p></div>`;
        return;
    }
    const filteredProducts = getFilteredAdminProducts();
    if (!filteredProducts.length) {
        nodes.adminProducts.innerHTML = `<div class="empty-state"><h4>Ничего не найдено</h4><p>Измените поиск или фильтр категории.</p></div>`;
        return;
    }
    filteredProducts.forEach(product => nodes.adminProducts.appendChild(buildAdminProductCard(product)));
}

function buildAdminProductCard(product) {
    const card = document.createElement("button");
    card.type = "button";
    card.className = "admin-product-card";
    card.innerHTML = `
        <div class="admin-product-top">
            <span class="admin-product-category">${escapeHtml(product.category || "Без категории")}</span>
            <span class="admin-product-price">${formatPrice(product.price)}</span>
        </div>
        <div class="admin-product-summary">
            <strong>${escapeHtml(product.name || "Новый товар")}</strong>
            <p>${escapeHtml(compactDescription(product.description || "Нажмите, чтобы открыть карточку товара.", 96))}</p>
        </div>
        <div class="admin-product-meta">
            <span>${product.stockQuantity == null ? "Остаток не указан" : `Остаток: ${formatQuantityInput(product.stockQuantity)} ${escapeHtml(product.unitName || "шт")}`}</span>
            <span>Изменить</span>
        </div>
    `;
    card.addEventListener("click", () => openAdminProductEditor(product));
    return card;
}

function renderProductEditor(product) {
    const draft = product || { name: "", category: "", subcategory: "", price: "", stockQuantity: "", cultures: [], tags: [], description: "", unitName: "шт" };
    openAdminProductEditor(draft);
}

function renderOrdersList(container, orders, emptyText) {
    container.innerHTML = "";
    if (!orders.length) {
        container.innerHTML = `<div class="empty-state"><h4>${emptyText}</h4></div>`;
        return;
    }
    orders.forEach(order => {
        const card = document.createElement("div");
        card.className = "order-card";
        card.innerHTML = `
            <div class="order-head">
                <strong>${escapeHtml(order.publicCode)}</strong>
                <span>${formatPrice(order.totalPrice)}</span>
            </div>
            <div>👤 ${escapeHtml(order.customerName || "")}</div>
            <div>📞 ${escapeHtml(order.customerPhone || "")}</div>
            <div>📦 ${order.items.length} позиций</div>
        `;
        container.appendChild(card);
    });
}

function getFilteredAdminProducts() {
    const query = normalizeToken(state.adminUi.search);
    const category = state.adminUi.category;
    return state.adminProducts.filter(product => {
        const categoryMatches = category === "all" || (product.category || "") === category;
        if (!categoryMatches) {
            return false;
        }
        if (!query) {
            return true;
        }
        const haystack = normalizeToken([
            product.name,
            product.category,
            product.subcategory,
            product.description,
            ...(product.cultures || []),
            ...(product.tags || []),
        ].join(" "));
        return haystack.includes(query);
    });
}

function renderAdminCategoryFilter() {
    if (!nodes.adminCategoryFilter) {
        return;
    }
    const categories = [...new Set(state.adminProducts.map(item => item.category).filter(Boolean))].sort((a, b) => a.localeCompare(b, "ru"));
    nodes.adminCategoryFilter.innerHTML = [
        `<option value="all">Все категории</option>`,
        ...categories.map(category => `<option value="${escapeAttr(category)}">${escapeHtml(category)}</option>`)
    ].join("");
    if (!categories.includes(state.adminUi.category) && state.adminUi.category !== "all") {
        state.adminUi.category = "all";
    }
    nodes.adminCategoryFilter.value = state.adminUi.category;
}

function syncAdminSearchUi() {
    nodes.adminProductClearSearch?.classList.toggle("hidden", !state.adminUi.search);
}

function openAdminProductEditor(product) {
    const form = nodes.adminProductForm;
    setFormValue(form, "productId", product.id || "");
    setFormValue(form, "name", product.name || "");
    setFormValue(form, "category", product.category || "");
    setFormValue(form, "subcategory", product.subcategory || "");
    setFormValue(form, "price", product.price ?? "");
    setFormValue(form, "stockQuantity", product.stockQuantity == null ? "" : formatQuantityInput(product.stockQuantity));
    setFormValue(form, "unitName", product.unitName || "шт");
    setFormValue(form, "cultures", (product.cultures || []).join(", "));
    setFormValue(form, "tags", (product.tags || []).join(", "));
    setFormValue(form, "description", product.description || "");
    nodes.adminProductDeleteButton.classList.toggle("hidden", !product.id);
    nodes.adminProductModal.classList.remove("hidden");
    document.body.style.overflow = "hidden";
}

function closeAdminProductModal() {
    nodes.adminProductModal.classList.add("hidden");
    document.body.style.overflow = "";
}

function openAdminOrdersModal() {
    nodes.adminOrdersModal?.classList.remove("hidden");
    document.body.style.overflow = "hidden";
}

function closeAdminOrdersModal() {
    nodes.adminOrdersModal?.classList.add("hidden");
    document.body.style.overflow = "";
}

async function submitAdminProductForm(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const productIdRaw = getFormValue(form, "productId");
    const productId = productIdRaw ? Number(productIdRaw) : null;
    const submitButton = form.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    submitButton.textContent = "Сохраняем...";
    try {
        await saveAdminProduct(productId, form);
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Сохранить";
    }
}

async function handleAdminDeleteFromModal() {
    const productIdRaw = getFormValue(nodes.adminProductForm, "productId");
    const productId = productIdRaw ? Number(productIdRaw) : null;
    if (!productId) {
        closeAdminProductModal();
        return;
    }
    await deleteAdminProduct(productId);
}

async function saveAdminProduct(productId, form) {
    const formData = new FormData(form);
    const payload = {
        name: String(formData.get("name") || "").trim(),
        category: String(formData.get("category") || "").trim(),
        subcategory: String(formData.get("subcategory") || "").trim(),
        price: formData.get("price") ? Number(formData.get("price")) : null,
        stockQuantity: formData.get("stockQuantity") ? parseQuantity(formData.get("stockQuantity")) : null,
        cultures: String(formData.get("cultures") || "").trim(),
        tags: String(formData.get("tags") || "").trim(),
        description: String(formData.get("description") || "").trim(),
        unitName: String(formData.get("unitName") || "").trim() || "шт",
        itemType: String(formData.get("subcategory") || "").trim() || String(formData.get("category") || "").trim() || "Товар",
        brand: "АЛГА АГРО",
        purposes: "",
        active: true,
    };
    const url = productId ? `/api/admin/products/${productId}?maxUserId=${state.maxUserId}` : `/api/admin/products?maxUserId=${state.maxUserId}`;
    const method = productId ? "PUT" : "POST";
    const saved = await fetchJson(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
    if (productId) {
        state.adminProducts = state.adminProducts.map(item => item.id === productId ? saved : item);
    } else {
        state.adminProducts = [saved, ...state.adminProducts.filter(item => item.id)];
    }
    await Promise.all([loadFilters(), loadProducts()]);
    renderAdminCategoryFilter();
    renderAdminProducts();
    closeAdminProductModal();
    showToast("Товар сохранен");
}

async function deleteAdminProduct(productId) {
    if (!productId) {
        state.adminProducts = state.adminProducts.filter(item => item.id);
        renderAdminProducts();
        return;
    }
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, { method: "DELETE" });
    state.adminProducts = state.adminProducts.filter(item => item.id !== productId);
    await Promise.all([loadFilters(), loadProducts()]);
    renderAdminCategoryFilter();
    renderAdminProducts();
    closeAdminProductModal();
    showToast("Товар удален");
}

function filterProductsByGroup(products, group) {
    if (!group) return products;
    return products.filter(product => categoryMatchesGroup(product.category, group));
}

function getCatalogTitle() {
    if (state.selection.culture && state.selection.season) {
        return `${state.selection.culture} · ${state.selection.season}`;
    }
    if (state.selection.group) {
        const group = getGroupDefinitions().find(item => item.key === state.selection.group);
        if (group) return group.title;
    }
    if (state.selection.culture) return state.selection.culture;
    return "Все товары";
}

function getGroupDefinitions() {
    const knownCategories = collectKnownCategories();
    const baseGroups = BASE_GROUPS.map(group => {
        const categories = knownCategories.filter(category => categoryMatchesGroup(category, group));
        return {
            ...group,
            categories: categories.length ? categories : group.fallbackCategories,
            exactCategory: null,
        };
    }).filter(group => group.categories.length > 0);

    const covered = new Set(baseGroups.flatMap(group => group.categories.map(normalizeToken)));
    const dynamicGroups = knownCategories
        .filter(category => !covered.has(normalizeToken(category)))
        .map(category => ({
            key: `dynamic:${slugify(category)}`,
            title: category,
            iconPath: iconForCategory(category),
            visualLabel: shortGroupLabel(category),
            description: "Дополнительная группа",
            categories: [category],
            exactCategory: category,
        }));

    return [...baseGroups, ...dynamicGroups];
}

function collectKnownCategories() {
    const set = new Set();
    (state.filters.categories || []).forEach(category => {
        if (category) {
            set.add(category);
        }
    });
    (state.products || []).forEach(product => {
        if (product.category) {
            set.add(product.category);
        }
    });
    if (!set.size) {
        BASE_GROUPS.forEach(group => group.fallbackCategories.forEach(category => set.add(category)));
    }
    return [...set];
}

function categoryMatchesGroup(category, group) {
    if (!group || !category) {
        return false;
    }
    const normalized = normalizeToken(category);
    if (group.exactCategory) {
        return normalized === normalizeToken(group.exactCategory);
    }
    return (group.matchers || []).some(keyword => normalized.includes(normalizeToken(keyword)));
}

function iconForCategory(category) {
    const normalized = normalizeToken(category);
    if (normalized.includes("сем") || normalized.includes("озим") || normalized.includes("яров")) return "./assets/category-seeds.svg";
    if (normalized.includes("пест") || normalized.includes("герб") || normalized.includes("фунг") || normalized.includes("инсект") || normalized.includes("протрав") || normalized.includes("десик")) return "./assets/category-pesticides.svg";
    if (normalized.includes("удоб") || normalized.includes("стим") || normalized.includes("микро") || normalized.includes("питан") || normalized.includes("азот") || normalized.includes("цинк") || normalized.includes("бор") || normalized.includes("npk")) return "./assets/category-nutrition.svg";
    return "./assets/category-other.svg";
}

function shortGroupLabel(title) {
    const words = String(title || "")
        .trim()
        .split(/\s+/)
        .filter(Boolean)
        .slice(0, 2);
    if (!words.length) {
        return "Категория";
    }
    return words.join(" ");
}

function normalizeToken(value) {
    return String(value || "")
        .toLowerCase()
        .replaceAll("ё", "е")
        .trim();
}

function slugify(value) {
    return normalizeToken(value)
        .replace(/[^a-zа-я0-9]+/gi, "-")
        .replace(/^-+|-+$/g, "") || "group";
}

function addToCart(product, quantity = 1) {
    const safeQuantity = parseQuantity(quantity);
    const existing = state.cart.find(item => item.id === product.id);
    if (existing) existing.quantity = roundQuantity(existing.quantity + safeQuantity);
    else state.cart.push({ id: product.id, name: product.name, price: Number(product.price || 0), quantity: safeQuantity, unitName: product.unitName || "шт" });
    renderCart();
    showToast(`Добавили: ${product.name}`);
}

function syncSearchUi() {
    nodes.clearSearchButton.classList.toggle("hidden", !state.selection.search);
}

function updateQuantity(productId, delta) {
    const item = state.cart.find(entry => entry.id === productId);
    if (!item) return;
    item.quantity = roundQuantity(item.quantity + delta);
    if (item.quantity <= 0) state.cart = state.cart.filter(entry => entry.id !== productId);
    renderCart();
}

function setQuantity(productId, value) {
    const item = state.cart.find(entry => entry.id === productId);
    if (!item) return;
    const quantity = parseQuantity(value);
    if (quantity <= 0) {
        state.cart = state.cart.filter(entry => entry.id !== productId);
    } else {
        item.quantity = quantity;
    }
    renderCart();
}

function removeItem(productId) {
    state.cart = state.cart.filter(item => item.id !== productId);
    renderCart();
}

async function submitOrder(event) {
    event.preventDefault();
    if (!state.cart.length) {
        showToast("Корзина пуста");
        return;
    }
    const form = event.currentTarget;
    const formData = new FormData(form);
    const payload = {
        maxUserId: state.maxUserId,
        name: formData.get("name"),
        phone: formData.get("phone"),
        company: "",
        comment: "",
        culture: state.selection.culture || null,
        deliveryNote: "",
        items: state.cart.map(item => ({ productId: item.id, quantity: item.quantity })),
    };
    const submitButton = form.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    submitButton.textContent = "Отправляем...";
    try {
        const data = await fetchJson("/api/orders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        });
        state.cart = [];
        renderCart();
        nodes.checkoutForm.reset();
        nodes.checkoutModalForm.reset();
        closeCheckoutModal();
        await loadProfile();
        showPage("profile");
        showToast(`Заказ ${data.orderCode} отправлен`);
    } catch (error) {
        showToast(error.message || "Ошибка при отправке заказа");
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Отправить заказ";
    }
}

async function fetchJson(url, options) {
    const response = await fetch(url, options);
    const text = await response.text();
    let data = {};
    try {
        data = text ? JSON.parse(text) : {};
    } catch (error) {
        data = { message: text || "Некорректный ответ сервера" };
    }
    if (!response.ok) throw new Error(data.message || data.error || "Ошибка запроса");
    return data;
}

function showToast(message) {
    nodes.toast.textContent = message;
    nodes.toast.classList.remove("hidden");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => nodes.toast.classList.add("hidden"), 2500);
}

function formatPrice(value) {
    const number = Number(value || 0);
    if (!Number.isFinite(number) || number <= 0) return "По запросу";
    return `${new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(number)} ₽`;
}

function parseQuantity(value) {
    const number = Number(String(value || "").replace(",", "."));
    if (!Number.isFinite(number) || number <= 0) {
        return 1;
    }
    return roundQuantity(number);
}

function roundQuantity(value) {
    return Math.max(1, Math.round(Number(value)));
}

function getQuantityStep() {
    return 1;
}

function getDefaultQuantity() {
    return 1;
}

function formatQuantityInput(value) {
    return String(roundQuantity(value));
}

function syncProductModalTotal() {
    const quantity = parseQuantity(nodes.productModalQuantity.value);
    const price = Number(nodes.productModalAddButton.dataset.unitPrice || 0);
    nodes.productModalTotal.textContent = formatPrice(price * quantity);
}

function compactDescription(value, maxLength) {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    if (!text) {
        return "Товар доступен для заказа.";
    }
    if (text.length <= maxLength) {
        return text;
    }
    return `${text.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}

function initials(value) {
    return String(value).split(" ").filter(Boolean).slice(0, 2).map(part => part[0]).join("").toUpperCase() || "AA";
}

function pluralize(value, forms) {
    const mod10 = value % 10;
    const mod100 = value % 100;
    if (mod10 === 1 && mod100 !== 11) return forms[0];
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return forms[1];
    return forms[2];
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
}

function escapeAttr(value) {
    return escapeHtml(value).replaceAll("'", "&#39;");
}

function getFormValue(form, name) {
    const field = form.elements.namedItem(name);
    return field ? field.value : "";
}

function setFormValue(form, name, value) {
    const field = form.elements.namedItem(name);
    if (field) {
        field.value = value;
    }
}
