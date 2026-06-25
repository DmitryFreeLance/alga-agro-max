const root = document.getElementById("appRoot");
const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
const bridgeUserId = parseMaxUserIdFromBridgeInitData();
const queryUserId = new URLSearchParams(window.location.search).get("maxUserId");
let clientStateSyncTimer = null;

const SECTION_VISUALS = [
    {
        match: ["гербиц"],
        icon: "./assets/category-herbicides-ref.png",
        palette: ["#bfeec2", "#e7f6d3"],
        description: "Защита от сорняков",
    },
    {
        match: ["фунгиц"],
        icon: "./assets/category-fungicides-ref.png",
        palette: ["#bfeee2", "#def7de"],
        description: "Защита от болезней",
    },
    {
        match: ["инсекти"],
        icon: "./assets/category-insecticides-ref.png",
        palette: ["#ffeeb9", "#ffd7ab"],
        description: "Защита от вредителей",
    },
    {
        match: ["адъюв", "адьюв"],
        icon: "./assets/category-adjuvants-ref.png",
        palette: ["#d9edf0", "#d3f4e1"],
        description: "Прилипатели и вспомогательные компоненты",
    },
    {
        match: ["семен", "озим", "яров"],
        icon: "./assets/category-seeds-flaticon.png",
        palette: ["#fff2b7", "#ffd681"],
        description: "Зерновые, масличные, бобовые",
    },
    {
        match: ["пестиц", "сзр"],
        icon: "./assets/category-pesticides-ref.png",
        palette: ["#dff3d8", "#eaf7d8"],
        description: "Комплексная защита растений",
    },
    {
        match: ["десикант", "протрав", "роденти", "репелент", "регулятор рост", "красител", "специальн"],
        icon: "./assets/category-pesticides-ref.png",
        palette: ["#dff3d8", "#eaf7d8"],
        description: "Специализированные препараты защиты растений",
    },
    {
        match: ["агрохим", "удобр", "агропитан", "питан", "микро", "биостим"],
        icon: "./assets/category-nutrition-flaticon.png",
        palette: ["#c8eff6", "#a8e0ee"],
        description: "Удобрения и стимуляторы",
    },
    {
        match: ["мелиор"],
        icon: "./assets/category-other.svg",
        palette: ["#d9d3cf", "#bfb0aa"],
        description: "Известкование почв",
    },
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
    catalog: {
        query: "",
        section: "",
        sort: "default",
        filtersOpen: false,
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
        productEditor: { open: false, productId: null },
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

bootstrap().catch(error => {
    console.error(error);
    root.innerHTML = `<div class="page"><div class="empty-box">Не удалось загрузить каталог. Попробуйте обновить мини-приложение.</div></div>`;
});

root.addEventListener("click", handleClick);
root.addEventListener("input", handleInput);
root.addEventListener("change", handleChange);
root.addEventListener("submit", handleSubmit);

async function bootstrap() {
    const [meta, profile, products, sections, profileOrders] = await Promise.all([
        fetchJson("/api/meta"),
        state.maxUserId ? fetchJson(`/api/profile?maxUserId=${state.maxUserId}`) : Promise.resolve({ admin: false, displayName: "Пользователь MAX" }),
        fetchJson("/api/catalog/products?sort=name"),
        fetchJson("/api/catalog/sections"),
        state.maxUserId ? fetchJson(`/api/profile/orders?maxUserId=${state.maxUserId}`) : Promise.resolve([]),
    ]);
    state.meta = meta;
    state.profile = profile;
    if (!state.cart.length && Array.isArray(profile.savedCart) && profile.savedCart.length) {
        state.cart = profile.savedCart;
        saveStorage("alga-cart", state.cart);
    }
    state.products = products;
    state.sections = sections;
    state.profileOrders = profileOrders;
    hydrateCheckoutFromProfile();
    rememberMaxUserId(state.maxUserId);
    scheduleClientStateSync(150);
    if (state.profile.admin && state.maxUserId) {
        await loadAdminData();
    }
    render();
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
        </div>
    `;
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
                <div class="topbar-logo"><img src="./assets/logo.png" alt="АЛГА АГРО"></div>
                <div class="topbar-title">
                    <h1>${escapeHtml(state.meta?.company || "АЛГА АГРО ГРУПП")}</h1>
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
                <img src="${visual.icon}" alt="${escapeAttr(getSectionDisplayName(section.name))}">
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
            <button class="search-muted" data-action="back">‹ Назад</button>
            <div class="page-head">
                <h2 class="page-title">${escapeHtml(getSectionDisplayName(state.catalog.section))}</h2>
                <p>${filtered.length} товаров</p>
            </div>
            <div class="toolbar-row">
                <button type="button" class="toolbar-button" data-action="open-filters">⚙️ Фильтры</button>
                <select class="toolbar-select" data-field="catalog-sort">
                    ${renderSortOptions()}
                </select>
            </div>
            ${renderProductsGrid(filtered)}
        </div>
    `;
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
    const categoryLabel = getProductLeafSectionName(product);
    return `
        <article class="product-card" data-action="open-product" data-product-id="${product.id}">
            <div class="product-card-visual" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                <img src="${visual.icon}" alt="${escapeAttr(product.name)}">
                <button type="button" class="favorite-fab ${favorite ? "active" : ""}" data-action="toggle-favorite" data-product-id="${product.id}">${favorite ? "♥" : "♡"}</button>
            </div>
            <div class="product-card-body">
                <p class="product-card-title">${escapeHtml(product.name)}</p>
                <p class="product-card-subtitle">${escapeHtml(product.brand || categoryLabel || "")}</p>
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

function renderProductPrice(product) {
    const oldPrice = normalizePrice(product.oldPrice);
    if (product.price == null) {
        return `<div class="price-block request"><div class="old-price">&nbsp;</div><strong>По запросу</strong></div>`;
    }
    return `
        <div class="price-block">
            <div class="old-price">${oldPrice ? `${formatPrice(oldPrice)}` : "&nbsp;"}</div>
            <strong>${formatPrice(product.price)}</strong>
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
                            ${getCartProducts().map(item => `<div class="summary-row"><span>${escapeHtml(item.product.name)}</span><span>${formatQuantity(item.quantity)} ед.</span></div>`).join("")}
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
                    ${orders.length ? orders.map(renderOrderCard).join("") : `<div class="empty-box">Заявок по этому фильтру пока нет.</div>`}
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
            <div class="search-muted">${order.items.map(item => `${escapeHtml(item.productName)} × ${formatQuantity(item.quantity)}`).join(", ")}</div>
        </article>
    `;
}

function renderAdminPage() {
    const meta = getAdminPageMeta();
    return `
        <section class="admin-page">
            <div class="admin-shell">
                <aside class="admin-sidebar">
                    <div class="admin-brand">
                        <img src="./assets/logo-green-bg.png" alt="Алга Агро">
                        <div class="admin-brand-text">
                            <strong>${escapeHtml(state.meta?.company || "АЛГА АГРО ГРУПП")}</strong>
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
                    <div class="admin-sidebar-footer">АЛГА-АГРО · 2026</div>
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
    const sectionVisual = activeSection?.visual || getSectionVisual(activeSectionName || "Прочее");
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
                    <div class="admin-card admin-card-spacious">
                        <div class="admin-section-head">
                            <div>
                                <h3>Подкатегории</h3>
                                <p>${activeSection.children.length} подкатегорий</p>
                            </div>
                        </div>
                        <div class="admin-subcategory-list">
                            ${activeSection.children.map(child => `
                                <div class="admin-subcategory-row">
                                    <div>
                                        <strong>${escapeHtml(child.name)}</strong>
                                        <p>${child.count} тов.</p>
                                    </div>
                                    <button class="admin-table-btn" data-action="admin-section" data-section="${escapeAttr(activeSection.name)}" data-category="${escapeAttr(child.name)}">Открыть</button>
                                </div>
                            `).join("")}
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
                                        <td data-label="Категория">${escapeHtml(product.subcategory || product.itemType || product.category || "—")}</td>
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
                        <div class="modal-subtitle">${escapeHtml(product.brand || "")}</div>
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
                        <div class="eyebrow">${escapeHtml(product.category || "Каталог")}</div>
                        <h3>${escapeHtml(product.name)}</h3>
                        <div class="product-brand">${escapeHtml(product.brand || "")}</div>
                    </div>
                    <div class="detail-price">
                        ${product.oldPrice ? `<div class="old-price">${formatPrice(product.oldPrice)}</div>` : ""}
                        <strong>${product.price == null ? "По запросу" : formatPrice(product.price)}</strong>
                    </div>
                    <div class="packaging-chips">
                        <span class="packaging-chip">${escapeHtml(product.packageDescription || product.packageType || product.unitName || "Упаковка не указана")}</span>
                    </div>
                    ${product.description ? `<p class="detail-paragraph">${escapeHtml(product.description)}</p>` : ""}
                    ${product.activeIngredient ? `<div><div class="eyebrow">Действующее вещество</div><p class="detail-paragraph">${escapeHtml(product.activeIngredient)}</p></div>` : ""}
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
    const draft = state.catalog.draft || cloneFilters(state.catalog.applied);
    const sectionProducts = getSectionProducts(state.catalog.section);
    const manufacturers = uniqueValues(sectionProducts.map(item => item.brand).filter(Boolean));
    const cultures = uniqueValues(sectionProducts.flatMap(item => item.cultures || []).filter(Boolean));
    const cultureKeys = new Set(cultures.map(normalize));
    const subcategories = uniqueValues(sectionProducts.flatMap(item => getFilterSubcategoryValues(item, state.catalog.section)).filter(Boolean))
        .filter(name => !cultureKeys.has(normalize(name)));
    const subcategoryTitle = getSubcategoryFilterTitle(state.catalog.section);
    return `
        <div class="drawer">
            <div class="drawer-backdrop" data-action="close-filters"></div>
            <div class="drawer-sheet">
                <div class="drawer-top">
                    <div class="modal-title">Фильтры</div>
                    <button class="secondary-btn" data-action="reset-filters">Сбросить всё</button>
                </div>
                <div class="drawer-section">
                    <h4>Раздел</h4>
                    <div class="filter-chips-row">
                        ${getCatalogSections().map(section => `
                            <button class="filter-chip ${draft.sections.includes(section.name) ? "active" : ""}" data-action="toggle-filter-section" data-section="${escapeAttr(section.name)}">${escapeHtml(getSectionDisplayName(section.name))}</button>
                        `).join("")}
                    </div>
                </div>
                <div class="drawer-section">
                    <h4>Культура</h4>
                    <div class="checkbox-list">
                        ${cultures.map(name => `
                            <label class="checkbox-row">
                                <input type="checkbox" data-field="filter-culture" value="${escapeAttr(name)}" ${draft.cultures.includes(name) ? "checked" : ""}>
                                <span>${escapeHtml(name)}</span>
                            </label>
                        `).join("")}
                    </div>
                </div>
                <div class="drawer-section">
                    <h4>Производитель</h4>
                    <div class="checkbox-list">
                        ${manufacturers.map(name => `
                            <label class="checkbox-row">
                                <input type="checkbox" data-field="filter-manufacturer" value="${escapeAttr(name)}" ${draft.manufacturers.includes(name) ? "checked" : ""}>
                                <span>${escapeHtml(name)}</span>
                            </label>
                        `).join("")}
                    </div>
                </div>
                <div class="drawer-section">
                    <h4>${escapeHtml(subcategoryTitle)}</h4>
                    <div class="checkbox-list">
                        ${subcategories.map(name => `
                            <label class="checkbox-row">
                                <input type="checkbox" data-field="filter-subcategory" value="${escapeAttr(name)}" ${draft.subcategories.includes(name) ? "checked" : ""}>
                                <span>${escapeHtml(name)}</span>
                            </label>
                        `).join("")}
                    </div>
                </div>
                <div class="drawer-section">
                    <h4>Цена, ₽</h4>
                    <div class="price-range">
                        <input type="number" data-field="filter-price-min" value="${escapeAttr(draft.priceMin)}" placeholder="от">
                        <span>—</span>
                        <input type="number" data-field="filter-price-max" value="${escapeAttr(draft.priceMax)}" placeholder="до">
                    </div>
                </div>
                <div class="drawer-actions">
                    <button class="ghost-btn" data-action="close-filters">Сбросить</button>
                    <button class="primary-btn" data-action="apply-filters">Применить</button>
                </div>
            </div>
        </div>
    `;
}

function renderAdminProductModal() {
    if (!state.admin.productEditor.open) return "";
    const product = state.admin.productEditor.productId ? state.admin.products.find(item => item.id === state.admin.productEditor.productId) : null;
    const categories = getCatalogSections().map(item => item.name);
    const visual = getProductVisual(product || { category: state.admin.catalogSection || "Прочее" });
    const selectedCategory = product ? getProductSectionName(product) : (state.admin.catalogSection || "");
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-admin-product"></div>
            <div class="modal-sheet modal-sheet-wide">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">${product ? "Редактирование товара" : "Добавить товар"}</div>
                        <div class="modal-subtitle">${product ? escapeHtml(product.name) : "Новая позиция каталога"}</div>
                    </div>
                    <button class="modal-close" data-action="close-admin-product">×</button>
                </div>
                <form class="admin-form-grid admin-product-form" data-form="admin-product">
                    <input type="hidden" name="productId" value="${product?.id || ""}">
                    <div class="admin-form-section">
                        <div class="admin-form-section-title">Основное</div>
                        <div class="admin-form-row">
                            <div class="admin-field"><label>Раздел</label><select name="category">${renderOptions(categories.map(item => [item, item]), selectedCategory)}</select></div>
                            <div class="admin-field"><label>Подкатегория</label><input name="subcategory" value="${escapeAttr(product?.subcategory || "")}"></div>
                        </div>
                        <div class="admin-field"><label>Название</label><input name="name" required value="${escapeAttr(product?.name || "")}"></div>
                        <div class="admin-form-row">
                            <div class="admin-field"><label>Производитель</label><input name="brand" required value="${escapeAttr(product?.brand || "")}"></div>
                            <div class="admin-field"><label>Ед. измерения</label><input name="unitName" value="${escapeAttr(product?.unitName || "шт")}"></div>
                        </div>
                    </div>
                    <div class="admin-form-section">
                        <div class="admin-form-section-title">Описание</div>
                        <div class="admin-field"><label>Описание</label><textarea name="description">${escapeHtml(product?.description || "")}</textarea></div>
                        <div class="admin-field"><label>Действующее вещество</label><input name="activeIngredient" value="${escapeAttr(product?.activeIngredient || "")}"></div>
                    </div>
                    <div class="admin-form-section">
                        <div class="admin-form-section-title">Визуал карточки</div>
                        <div class="admin-visual-note">
                            <div class="admin-visual-drop">
                                <div class="admin-upload-icon">▣</div>
                                <div>Иконка раздела применяется автоматически</div>
                                <small>Визуал формируется по категории товара</small>
                            </div>
                            <div class="admin-visual-thumbs">
                                <span class="admin-visual-thumb" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});"><img src="${visual.icon}" alt=""></span>
                            </div>
                        </div>
                    </div>
                    <div class="admin-form-section">
                        <div class="admin-form-section-title">Упаковка и цены</div>
                        <div class="admin-price-grid admin-price-grid-head">
                            <span>Объём / упаковка</span>
                            <span>Ед.</span>
                            <span>Цена</span>
                            <span>Старая цена</span>
                            <span>Мин. шаг</span>
                        </div>
                        <div class="admin-price-grid">
                            <input name="packageDescription" value="${escapeAttr(product?.packageDescription || "")}" placeholder="4×5 л">
                            <select name="packageType">${renderOptions([["", "—"], ["канистра", "Канистра"], ["коробка", "Коробка"], ["мешок", "Мешок"], ["п.е.", "П.е."], ["тонна", "Тонна"]], product?.packageType || "")}</select>
                            <input name="price" type="number" min="0" step="0.01" value="${escapeAttr(product?.price ?? "")}" placeholder="2239">
                            <input name="oldPrice" type="number" min="0" step="0.01" value="${escapeAttr(product?.oldPrice ?? "")}" placeholder="2890">
                            <input name="orderStep" type="number" min="1" step="1" value="${escapeAttr(product?.orderStep ?? 1)}" placeholder="1">
                        </div>
                        <div class="admin-form-row">
                            <div class="admin-field"><label>Минимум</label><input name="minOrderQuantity" type="number" min="1" step="1" value="${escapeAttr(product?.minOrderQuantity ?? 1)}"></div>
                            <div class="admin-field"><label>Остаток</label><input name="stockQuantity" type="number" min="0" step="1" value="${escapeAttr(product?.stockQuantity ?? "")}"></div>
                        </div>
                    </div>
                    <div class="admin-form-section">
                        <div class="admin-form-section-title">Дополнительно</div>
                        <div class="admin-form-row">
                            <div class="admin-field"><label>Культуры</label><input name="cultures" value="${escapeAttr((product?.cultures || []).join(", "))}"></div>
                            <div class="admin-field"><label>Теги / назначение</label><input name="tags" value="${escapeAttr((product?.tags || []).join(", "))}"></div>
                        </div>
                        <div class="admin-field"><label>Показывать в каталоге</label><select name="active">${renderOptions([["true", "Да"], ["false", "Нет"]], String(product?.active ?? true))}</select></div>
                    </div>
                    <div class="admin-actions">
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
                            ${order.items.map(item => `<div class="summary-row"><span>${escapeHtml(item.productName)} × ${formatQuantity(item.quantity)}</span><strong>${formatPrice((item.unitPrice || 0) * item.quantity)}</strong></div>`).join("")}
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
    const preview = safeItems.slice(0, 2).map(item => `${item.productName} × ${formatQuantity(item.quantity)}`);
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
    if (action === "nav") {
        state.nav = button.dataset.nav;
        state.catalog.query = "";
        render();
        return;
    }
    if (action === "open-section") {
        state.catalog.section = button.dataset.section;
        state.catalog.applied = emptyFilters();
        state.catalog.draft = cloneFilters(state.catalog.applied);
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
        render();
        return;
    }
    if (action === "open-manager") {
        openManagerLink();
        return;
    }
    if (action === "open-filters") {
        state.catalog.filtersOpen = true;
        state.catalog.draft = cloneFilters(state.catalog.applied);
        render();
        return;
    }
    if (action === "close-filters") {
        state.catalog.filtersOpen = false;
        render();
        return;
    }
    if (action === "reset-filters") {
        state.catalog.draft = emptyFilters();
        render();
        return;
    }
    if (action === "apply-filters") {
        state.catalog.applied = cloneFilters(state.catalog.draft || emptyFilters());
        state.catalog.filtersOpen = false;
        render();
        return;
    }
    if (action === "toggle-filter-section") {
        const section = button.dataset.section;
        const current = state.catalog.draft.sections;
        state.catalog.draft.sections = current.includes(section)
            ? current.filter(item => item !== section)
            : [...current, section];
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
        state.admin.productEditor = { open: true, productId: button.dataset.productId ? Number(button.dataset.productId) : null };
        render();
        return;
    }
    if (action === "close-admin-product") {
        state.admin.productEditor = { open: false, productId: null };
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
        state.catalog.draft.priceMin = event.target.value;
        return;
    }
    if (field === "filter-price-max") {
        state.catalog.draft.priceMax = event.target.value;
        return;
    }
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
        toggleDraftFilterArray("manufacturers", event.target.value, event.target.checked);
        return;
    }
    if (field === "filter-culture") {
        toggleDraftFilterArray("cultures", event.target.value, event.target.checked);
        return;
    }
    if (field === "filter-subcategory") {
        toggleDraftFilterArray("subcategories", event.target.value, event.target.checked);
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
    const oldPrice = String(formData.get("oldPrice") || "").trim();
    const payload = {
        name: String(formData.get("name") || "").trim(),
        category: String(formData.get("category") || "").trim(),
        subcategory: String(formData.get("subcategory") || "").trim(),
        brand: String(formData.get("brand") || "").trim(),
        description: String(formData.get("description") || "").trim(),
        unitName: String(formData.get("unitName") || "шт").trim(),
        price: parseOptionalNumber(formData.get("price")),
        stockQuantity: parseOptionalNumber(formData.get("stockQuantity")),
        packageType: String(formData.get("packageType") || "").trim(),
        packageDescription: String(formData.get("packageDescription") || "").trim(),
        minOrderQuantity: parseOptionalNumber(formData.get("minOrderQuantity")) || 1,
        orderStep: parseOptionalNumber(formData.get("orderStep")) || 1,
        cultures: String(formData.get("cultures") || "").trim(),
        tags: String(formData.get("tags") || "").trim(),
        filterMap: {
            activeIngredient,
            oldPrice,
        },
        active: String(formData.get("active")) !== "false",
    };
    const url = id ? `/api/admin/products/${id}?maxUserId=${state.maxUserId}` : `/api/admin/products?maxUserId=${state.maxUserId}`;
    const method = id ? "PUT" : "POST";
    await fetchJson(url, {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });
    await refreshCatalogData();
    state.admin.productEditor = { open: false, productId: null };
    render();
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
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, { method: "DELETE" });
    await refreshCatalogData();
    state.admin.productEditor = { open: false, productId: null };
    render();
}

async function toggleAdminProductVisibility(productId) {
    const product = state.admin.products.find(item => item.id === productId);
    if (!product) {
        return;
    }
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(buildAdminProductPayload(product, { active: !product.active })),
    });
    await refreshCatalogData();
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
    const [products, sections, profileOrders] = await Promise.all([
        fetchJson("/api/catalog/products?sort=name"),
        fetchJson("/api/catalog/sections"),
        state.maxUserId ? fetchJson(`/api/profile/orders?maxUserId=${state.maxUserId}`) : Promise.resolve([]),
    ]);
    state.products = products;
    state.sections = sections;
    state.profileOrders = profileOrders;
    if (state.profile?.admin) {
        await loadAdminData();
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
    const grouped = new Map();
    state.products.forEach(product => {
        const name = getProductSectionName(product);
        if (!grouped.has(name)) {
            grouped.set(name, { name, description: getSectionVisual(name).description, productsCount: 0 });
        }
        grouped.get(name).productsCount += 1;
    });
    return [...grouped.values()];
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
    if (applied.subcategories.length) {
        filtered = filtered.filter(product => {
            const values = getFilterSubcategoryValues(product, state.catalog.section);
            return values.some(value => applied.subcategories.includes(value));
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
    return getSectionVisual(getProductLeafSectionName(product));
}

function getSectionVisual(name) {
    const normalized = normalize(name);
    const matched = SECTION_VISUALS.find(item => item.match.some(match => normalized.includes(match)));
    return matched || {
        icon: "./assets/category-other.svg",
        palette: ["#e1f1de", "#d7e9f6"],
        description: getSectionDescription(name),
    };
}

function getSectionDisplayName(name) {
    const normalized = normalize(name);
    if (normalized.includes("пестиц")) {
        return "СЗР";
    }
    if (normalized.includes("мелиор")) {
        return "Мелиоранты";
    }
    return name || "Прочее";
}

function getSectionDescription(name) {
    const normalized = normalize(name);
    const matched = SECTION_VISUALS.find(item => item.match.some(match => normalized.includes(match)));
    if (matched?.description) {
        return matched.description;
    }
    if (normalized.includes("адъюв") || normalized.includes("адьюв")) {
        return "Прилипатели и вспомогательные компоненты";
    }
    if (normalized.includes("сзр") || normalized.includes("пестиц")) {
        return "Комплексная защита растений";
    }
    if (normalized.includes("десикант")) {
        return "Препараты для десикации культур";
    }
    if (normalized.includes("протрав")) {
        return "Защита семян перед посевом";
    }
    if (normalized.includes("роденти")) {
        return "Средства против грызунов";
    }
    if (normalized.includes("репелент")) {
        return "Средства отпугивания вредителей";
    }
    if (normalized.includes("регулятор рост")) {
        return "Контроль роста и развития растений";
    }
    if (normalized.includes("красител")) {
        return "Красители и специальные добавки для семян";
    }
    if (normalized.includes("проч")) {
        return "Дополнительные товары каталога";
    }
    return name ? `Товары раздела ${name}` : "Товары каталога";
}

function isSpecificSectionName(name) {
    const normalized = normalize(name);
    if (!normalized) {
        return false;
    }
    return SECTION_VISUALS.some(item => item.match.some(match => normalized.includes(match)))
        || normalized.includes("гербиц")
        || normalized.includes("фунгиц")
        || normalized.includes("инсекти")
        || normalized.includes("десикант")
        || normalized.includes("протрав")
        || normalized.includes("роденти")
        || normalized.includes("репелент")
        || normalized.includes("регулятор рост")
        || normalized.includes("красител")
        || normalized.includes("специальн");
}

function isSzrSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("проч")
        || normalized.includes("пестиц")
        || normalized.includes("сзр")
        || normalized.includes("гербиц")
        || normalized.includes("фунгиц")
        || normalized.includes("инсекти")
        || normalized.includes("десикант")
        || normalized.includes("протрав")
        || normalized.includes("роденти")
        || normalized.includes("репелент")
        || normalized.includes("регулятор рост")
        || normalized.includes("красител")
        || normalized.includes("специальн");
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
        || normalized.includes("посевн");
}

function isAdjuvantsSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("адъюв")
        || normalized.includes("адьюв")
        || normalized.includes("прилип")
        || normalized.includes("смачив")
        || normalized.includes("сурфакт");
}

function isNutritionSectionName(name) {
    const normalized = normalize(name);
    return normalized.includes("агрохим")
        || normalized.includes("удобр")
        || normalized.includes("агропитан")
        || normalized.includes("питан")
        || normalized.includes("микроудобр")
        || normalized.includes("микроэлемент")
        || normalized.includes("биостим")
        || normalized.includes("мелиор")
        || normalized.includes("аминокислот")
        || normalized.includes("гумат")
        || normalized.includes("листов")
        || normalized.includes("подкорм");
}

function getProductLeafSectionName(product) {
    const category = String(product?.category || "").trim();
    const subcategory = String(product?.subcategory || product?.itemType || "").trim();
    const normalizedCategory = normalize(category);
    const genericCategory = !normalizedCategory || normalizedCategory.includes("проч") || normalizedCategory.includes("пестиц") || normalizedCategory.includes("сзр");
    if (subcategory && (genericCategory || isSpecificSectionName(subcategory))) {
        return getSectionDisplayName(subcategory);
    }
    if (category) {
        return getSectionDisplayName(category);
    }
    if (subcategory) {
        return getSectionDisplayName(subcategory);
    }
    return "Прочее";
}

function getProductSectionName(product) {
    const leafSection = getProductLeafSectionName(product);
    const category = String(product?.category || "").trim();
    const subcategory = String(product?.subcategory || product?.itemType || "").trim();
    const context = [leafSection, category, subcategory].join(" ");
    if (isSeedsSectionName(context)) {
        return "Семена";
    }
    if (isAdjuvantsSectionName(context)) {
        return "Адъюванты";
    }
    if (isNutritionSectionName(context)) {
        return "Агропитание";
    }
    if (isSzrSectionName(context) || !normalize(context)) {
        return "СЗР";
    }
    return "СЗР";
}

function buildCategoriesTree(products) {
    const tree = {};
    products.forEach(product => {
        const parent = getProductSectionName(product);
        const child = product.subcategory || product.itemType || "Без категории";
        if (!tree[parent]) tree[parent] = [];
        if (!tree[parent].includes(child)) tree[parent].push(child);
    });
    Object.keys(tree).forEach(key => tree[key].sort((a, b) => a.localeCompare(b, "ru", { sensitivity: "base" })));
    return tree;
}

function getAdminSectionTree() {
    const grouped = {};
    state.admin.products.forEach(product => {
        const section = getProductSectionName(product);
        const child = product.subcategory || product.itemType || "Без категории";
        if (!grouped[section]) {
            grouped[section] = {
                name: section,
                count: 0,
                visual: getSectionVisual(section),
                childrenMap: {},
            };
        }
        grouped[section].count += 1;
        grouped[section].childrenMap[child] = (grouped[section].childrenMap[child] || 0) + 1;
    });
    return Object.values(grouped)
        .map(section => ({
            name: section.name,
            count: section.count,
            visual: section.visual,
            children: Object.entries(section.childrenMap)
                .map(([name, count]) => ({ name, count }))
                .sort((left, right) => left.name.localeCompare(right.name, "ru", { sensitivity: "base" })),
        }))
        .sort((a, b) => a.name.localeCompare(b.name, "ru", { sensitivity: "base" }));
}

function getAdminFilteredProducts() {
    const search = normalize(state.admin.catalogSearch);
    const effectiveSection = state.admin.catalogSection || getAdminSectionTree()[0]?.name || "";
    return state.admin.products.filter(product => {
        if (state.admin.catalogStatus === "ACTIVE" && !product.active) return false;
        if (state.admin.catalogStatus === "HIDDEN" && product.active) return false;
        if (effectiveSection && getProductSectionName(product) !== effectiveSection) return false;
        if (state.admin.catalogCategory && (product.subcategory || product.itemType || "Без категории") !== state.admin.catalogCategory) return false;
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
    const step = Math.max(1, Number(product.orderStep || 1));
    const min = Math.max(1, Number(product.minOrderQuantity || 1));
    const safeQuantity = Math.max(min, Math.round(Number(quantity) || min));
    if (safeQuantity <= min) return min;
    return min + Math.round((safeQuantity - min) / step) * step;
}

function getInitialQuantity(product) {
    return Math.max(1, Number(product.minOrderQuantity || 1));
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
    return { sections: [], cultures: [], manufacturers: [], subcategories: [], priceMin: "", priceMax: "" };
}

function cloneFilters(filters) {
    return {
        sections: [...(filters.sections || [])],
        cultures: [...(filters.cultures || [])],
        manufacturers: [...(filters.manufacturers || [])],
        subcategories: [...(filters.subcategories || [])],
        priceMin: filters.priceMin || "",
        priceMax: filters.priceMax || "",
    };
}

function toggleDraftFilterArray(key, value, checked) {
    const list = state.catalog.draft[key] || [];
    state.catalog.draft[key] = checked ? [...list, value] : list.filter(item => item !== value);
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
        return "Группа культур";
    }
    if (normalized.includes("пестиц") || normalized === "сзр") {
        return "Тип СЗР";
    }
    return "Подкатегория";
}

function getFilterSubcategoryValues(product, sectionName) {
    const normalizedSection = normalize(sectionName || getProductSectionName(product));
    if (normalizedSection.includes("семен")) {
        const cultureGroups = toStringArray(product?.filterMap?.cultureGroup).map(formatSeedGroupLabel);
        if (cultureGroups.length) {
            return uniqueValues(cultureGroups);
        }
    }
    const fallback = [product?.subcategory, product?.itemType]
        .map(value => String(value || "").trim())
        .filter(Boolean);
    return uniqueValues(fallback);
}

function formatSeedGroupLabel(value) {
    const normalized = normalize(value);
    if (normalized === "зерновые") return "Зерновые";
    if (normalized === "бобовые") return "Бобовые";
    if (normalized === "масличные") return "Масличные";
    if (normalized === "технические") return "Технические";
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
    alert(error.message || "Что-то пошло не так");
    if (state.admin.broadcastForm.sending) {
        state.admin.broadcastForm.sending = false;
        render();
    }
    if (state.checkout.submitting) {
        state.checkout.submitting = false;
        render();
    }
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
