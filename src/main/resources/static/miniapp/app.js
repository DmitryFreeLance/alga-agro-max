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
    filters: { cultures: [], categories: [], tags: [] },
    products: [],
    selection: { culture: "", category: "", search: "", sort: "name", group: "" },
    cart: [],
    currentPage: "home",
    maxUserId: null,
    catalogMode: "catalog",
};

const nodes = {
    homePage: document.getElementById("homePage"),
    catalogPage: document.getElementById("catalogPage"),
    resultsPage: document.getElementById("resultsPage"),
    profilePage: document.getElementById("profilePage"),
    checkoutPage: document.getElementById("checkoutPage"),
    homeNavButton: document.getElementById("homeNavButton"),
    catalogBottomButton: document.getElementById("catalogBottomButton"),
    profileNavButton: document.getElementById("profileNavButton"),
    pageTitle: document.getElementById("pageTitle"),
    pageCaption: document.getElementById("pageCaption"),
    openCatalogFromHome: document.getElementById("openCatalogFromHome"),
    openCartFromHome: document.getElementById("openCartFromHome"),
    homeGroupGrid: document.getElementById("homeGroupGrid"),
    catalogModeButton: document.getElementById("catalogModeButton"),
    cartModeButton: document.getElementById("cartModeButton"),
    catalogModePanel: document.getElementById("catalogModePanel"),
    cartModePanel: document.getElementById("cartModePanel"),
    searchInput: document.getElementById("searchInput"),
    clearSearchButton: document.getElementById("clearSearchButton"),
    sortSelect: document.getElementById("sortSelect"),
    cultureChips: document.getElementById("cultureChips"),
    categoryPills: document.getElementById("categoryPills"),
    groupGrid: document.getElementById("groupGrid"),
    productGrid: document.getElementById("productGrid"),
    emptyState: document.getElementById("emptyState"),
    catalogTitle: document.getElementById("catalogTitle"),
    catalogCount: document.getElementById("catalogCount"),
    cartCount: document.getElementById("cartCount"),
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
    adminSection: document.getElementById("adminSection"),
    adminProducts: document.getElementById("adminProducts"),
    adminOrders: document.getElementById("adminOrders"),
    addProductButton: document.getElementById("addProductButton"),
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
    productModalAddButton: document.getElementById("productModalAddButton"),
};

const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
const queryUserId = new URLSearchParams(window.location.search).get("maxUserId");
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
    showPage("home");
}

function bindEvents() {
    nodes.homeNavButton.addEventListener("click", () => showPage("home"));
    nodes.catalogBottomButton.addEventListener("click", () => showPage("catalog"));
    nodes.profileNavButton.addEventListener("click", () => showPage("profile"));
    nodes.openCatalogFromHome.addEventListener("click", () => showPage("catalog"));
    nodes.openCartFromHome.addEventListener("click", () => {
        showPage("catalog");
        setCatalogMode("cart");
    });
    nodes.catalogModeButton.addEventListener("click", () => setCatalogMode("catalog"));
    nodes.cartModeButton.addEventListener("click", () => setCatalogMode("cart"));
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
        showPage("checkout");
    });
    nodes.backToCart.addEventListener("click", () => {
        showPage("catalog");
        setCatalogMode("cart");
    });
    nodes.backToCatalog.addEventListener("click", () => {
        showPage("catalog");
        setCatalogMode("catalog");
    });
    nodes.checkoutForm.addEventListener("submit", submitOrder);
    nodes.addProductButton?.addEventListener("click", () => renderProductEditor(null));
    nodes.headerBackButton.addEventListener("click", async () => {
        if (state.currentPage === "checkout") {
            await resetCatalogSelection();
            showPage("catalog");
            setCatalogMode("cart");
        } else if (state.currentPage === "results") {
            await resetCatalogSelection();
            showPage("catalog");
            setCatalogMode("catalog");
        } else if (state.currentPage === "catalog") {
            await resetCatalogSelection();
            setCatalogMode("catalog");
            showPage("home");
        } else {
            await resetCatalogSelection();
            showPage("home");
        }
    });
    nodes.productModalBackdrop.addEventListener("click", closeProductModal);
    nodes.productModalClose.addEventListener("click", closeProductModal);
    nodes.productModalAddButton.addEventListener("click", () => {
        const productId = Number(nodes.productModalAddButton.dataset.productId);
        const product = state.products.find(item => item.id === productId);
        if (product) {
            addToCart(product);
            closeProductModal();
        }
    });
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !nodes.productModal.classList.contains("hidden")) {
            closeProductModal();
        }
    });
}

async function loadMeta() {
    state.meta = await fetchJson("/api/meta");
}

async function loadFilters() {
    state.filters = await fetchJson("/api/catalog/filters");
    renderFilterPills();
}

async function loadProducts() {
    const query = new URLSearchParams();
    if (state.selection.culture) query.set("culture", state.selection.culture);
    if (state.selection.category) query.set("category", state.selection.category);
    if (state.selection.search) query.set("search", state.selection.search);
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
    nodes.homePage.classList.toggle("page-active", page === "home");
    nodes.catalogPage.classList.toggle("page-active", page === "catalog");
    nodes.resultsPage.classList.toggle("page-active", page === "results");
    nodes.profilePage.classList.toggle("page-active", page === "profile");
    nodes.checkoutPage.classList.toggle("page-active", page === "checkout");
    nodes.homeNavButton.classList.toggle("active", page === "home");
    nodes.catalogBottomButton.classList.toggle("active", page === "catalog" || page === "results");
    nodes.profileNavButton.classList.toggle("active", page === "profile");
    const meta = {
        home: ["Главная", ""],
        catalog: ["Каталог", "Подбор товаров по культуре, группе и фильтрам."],
        results: ["Товары", "Подходящие позиции по выбранным параметрам."],
        profile: ["Профиль", "Ваши заказы и управление данными."],
        checkout: ["Оформление", "Отправка заявки администратору."],
    }[page];
    nodes.pageTitle.textContent = meta[0];
    nodes.pageCaption.textContent = meta[1];
    nodes.pageCaption.classList.toggle("hidden", !meta[1]);
}

function hasActiveCatalogQuery() {
    return Boolean(
        (state.selection.search || "").trim()
        || state.selection.culture
        || state.selection.category
        || state.selection.group
    );
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
    if (hasActiveCatalogQuery()) {
        showPage("results");
    } else if (state.currentPage === "results") {
        showPage("catalog");
    }
}

function setCatalogMode(mode) {
    state.catalogMode = mode;
    nodes.catalogModeButton.classList.toggle("active", mode === "catalog");
    nodes.cartModeButton.classList.toggle("active", mode === "cart");
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
        renderFilterPills();
        await refreshCatalogPresentation();
    });
    renderSelectableGroup(nodes.categoryPills, ["Все категории", ...state.filters.categories], state.selection.category, async value => {
        state.selection.category = value === "Все категории" ? "" : value;
        state.selection.group = "";
        renderFilterPills();
        renderCatalogGroups();
        await refreshCatalogPresentation();
    });
}

function renderSelectableGroup(container, items, selected, onClick) {
    container.innerHTML = "";
    items.forEach(item => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "chip";
        button.textContent = item;
        const active = (selected || "") === item || (!selected && ["Все культуры", "Все", "Все категории"].includes(item));
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
                    <button class="ghost-button small-button details-button" type="button">Подробнее</button>
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
        card.querySelector(".details-button").addEventListener("click", event => {
            event.stopPropagation();
            openProductModal(product);
        });
        card.querySelector(".primary-button").addEventListener("click", event => {
            event.stopPropagation();
            addToCart(product);
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
    const modalTags = [
        ...(product.cultures || []).slice(0, 4),
        ...(product.tags || []).slice(0, 4),
    ];
    nodes.productModalTags.innerHTML = modalTags.map(value => `<span class="badge">${escapeHtml(value)}</span>`).join("");
    nodes.productModalTags.classList.toggle("hidden", modalTags.length === 0);
    nodes.productModalStock.textContent = product.stockQuantity == null
        ? "Наличие уточняется"
        : `Остаток: ${product.stockQuantity} ${product.unitName || ""}`;
    nodes.productModalPrice.textContent = `${formatPrice(product.price)}${product.unitName ? `/${product.unitName}` : ""}`;
    nodes.productModalAddButton.dataset.productId = String(product.id);
    nodes.productModal.classList.remove("hidden");
    document.body.style.overflow = "hidden";
}

function closeProductModal() {
    nodes.productModal.classList.add("hidden");
    document.body.style.overflow = "";
}

async function resetCatalogSelection() {
    state.selection.culture = "";
    state.selection.category = "";
    state.selection.search = "";
    state.selection.group = "";
    nodes.searchInput.value = "";
    window.clearTimeout(liveSearchTimer);
    syncSearchUi();
    renderFilterPills();
    renderCatalogGroups();
    await loadProducts();
}

async function runSearchAndOpenResults() {
    state.selection.search = nodes.searchInput.value.trim();
    syncSearchUi();
    await refreshCatalogPresentation();
}

function renderCart() {
    const totalCount = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const totalPrice = state.cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    nodes.cartCount.textContent = totalCount;
    nodes.cartSummaryCount.textContent = `${totalCount} ${pluralize(totalCount, ["товар", "товара", "товаров"])}`;
    nodes.cartTotal.textContent = formatPrice(totalPrice);
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
                <button class="remove-btn" type="button">✕</button>
            </div>
            <div class="cart-row">
                <span>${formatPrice(item.price)} / ${escapeHtml(item.unitName)}</span>
                <div class="cart-qty">
                    <button class="qty-btn minus" type="button">−</button>
                    <strong>${item.quantity}</strong>
                    <button class="qty-btn plus" type="button">+</button>
                </div>
            </div>
        `;
        row.querySelector(".minus").addEventListener("click", () => updateQuantity(item.id, -1));
        row.querySelector(".plus").addEventListener("click", () => updateQuantity(item.id, 1));
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
    renderOrdersList(nodes.profileOrders, state.profileOrders, "У вас пока нет заказов.");
}

function renderAdminSection() {
    nodes.adminSection.classList.remove("hidden");
    renderAdminProducts();
    renderOrdersList(nodes.adminOrders, state.adminOrders, "Заказов пока нет.");
}

function renderAdminProducts() {
    nodes.adminProducts.innerHTML = "";
    if (!state.adminProducts.length) {
        nodes.adminProducts.innerHTML = `<div class="empty-state"><h4>Товаров пока нет</h4><p>Добавьте первую позицию.</p></div>`;
        return;
    }
    state.adminProducts.forEach(product => nodes.adminProducts.appendChild(buildAdminProductCard(product)));
}

function buildAdminProductCard(product) {
    const card = document.createElement("div");
    card.className = "admin-product-card";
    card.innerHTML = `
        <div class="order-head">
            <strong>${escapeHtml(product.name || "Новый товар")}</strong>
            <span>${formatPrice(product.price)}</span>
        </div>
        <form class="admin-product-form">
            <input name="name" value="${escapeAttr(product.name || "")}" placeholder="Название">
            <input name="category" value="${escapeAttr(product.category || "")}" placeholder="Категория">
            <input name="price" value="${escapeAttr(product.price ?? "")}" placeholder="Цена">
            <input name="stockQuantity" value="${escapeAttr(product.stockQuantity ?? "")}" placeholder="Количество">
            <input class="full" name="cultures" value="${escapeAttr((product.cultures || []).join(", "))}" placeholder="Культуры через запятую">
            <input class="full" name="tags" value="${escapeAttr((product.tags || []).join(", "))}" placeholder="Теги через запятую">
            <textarea class="full" name="description" placeholder="Описание">${escapeHtml(product.description || "")}</textarea>
        </form>
        <div class="admin-product-actions">
            <button class="ghost-button small-button" type="button" data-action="save">Сохранить</button>
            <button class="ghost-button small-button" type="button" data-action="delete">Удалить</button>
        </div>
    `;
    card.querySelector('[data-action="save"]').addEventListener("click", () => saveAdminProduct(product.id, card));
    card.querySelector('[data-action="delete"]').addEventListener("click", () => deleteAdminProduct(product.id));
    return card;
}

function renderProductEditor(product) {
    const draft = product || { name: "", category: "", price: "", stockQuantity: "", cultures: [], tags: [], description: "" };
    state.adminProducts = [draft, ...state.adminProducts];
    renderAdminProducts();
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

async function saveAdminProduct(productId, card) {
    const form = card.querySelector(".admin-product-form");
    const payload = {
        name: form.name.value.trim(),
        category: form.category.value.trim(),
        price: form.price.value ? Number(form.price.value) : null,
        stockQuantity: form.stockQuantity.value ? Number(form.stockQuantity.value) : null,
        cultures: form.cultures.value.trim(),
        tags: form.tags.value.trim(),
        description: form.description.value.trim(),
        unitName: "шт",
        subcategory: "",
        itemType: form.category.value.trim() || "Товар",
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
    renderAdminProducts();
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
    renderAdminProducts();
    showToast("Товар удален");
}

function filterProductsByGroup(products, group) {
    if (!group) return products;
    return products.filter(product => categoryMatchesGroup(product.category, group));
}

function getCatalogTitle() {
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

function addToCart(product) {
    const existing = state.cart.find(item => item.id === product.id);
    if (existing) existing.quantity += 1;
    else state.cart.push({ id: product.id, name: product.name, price: Number(product.price || 0), quantity: 1, unitName: product.unitName || "шт" });
    renderCart();
    showToast(`Добавили: ${product.name}`);
}

function syncSearchUi() {
    nodes.clearSearchButton.classList.toggle("hidden", !state.selection.search);
}

function updateQuantity(productId, delta) {
    const item = state.cart.find(entry => entry.id === productId);
    if (!item) return;
    item.quantity += delta;
    if (item.quantity <= 0) state.cart = state.cart.filter(entry => entry.id !== productId);
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
    const formData = new FormData(nodes.checkoutForm);
    const payload = {
        maxUserId: state.maxUserId,
        name: formData.get("name"),
        phone: formData.get("phone"),
        company: formData.get("company"),
        comment: formData.get("comment"),
        culture: state.selection.culture || null,
        deliveryNote: formData.get("deliveryNote"),
        items: state.cart.map(item => ({ productId: item.id, quantity: item.quantity })),
    };
    const submitButton = nodes.checkoutForm.querySelector('button[type="submit"]');
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
