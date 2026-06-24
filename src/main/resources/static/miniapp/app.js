const root = document.getElementById("appRoot");
const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
const bridgeUserId = parseMaxUserIdFromBridgeInitData();
const queryUserId = new URLSearchParams(window.location.search).get("maxUserId");

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
        uploaded: [],
        errors: {},
        form: {
            organization: "",
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
        broadcasts: { stats: null, history: [] },
        catalogSearch: "",
        catalogStatus: "ALL",
        catalogSection: "",
        productEditor: { open: false, productId: null },
        orderModal: { open: false, orderId: null },
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
    state.products = products;
    state.sections = sections;
    state.profileOrders = profileOrders;
    hydrateCheckoutFromProfile();
    rememberMaxUserId(state.maxUserId);
    if (state.profile.admin && state.maxUserId) {
        await loadAdminData();
    }
    render();
}

async function loadAdminData() {
    const [dashboard, products, manufacturers, orders, broadcasts] = await Promise.all([
        fetchJson(`/api/admin/dashboard?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/products?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/manufacturers?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/orders?maxUserId=${state.maxUserId}`),
        fetchJson(`/api/admin/broadcasts?maxUserId=${state.maxUserId}`),
    ]);
    state.admin.ready = true;
    state.admin.dashboard = dashboard;
    state.admin.products = products;
    state.admin.manufacturers = manufacturers;
    state.admin.orders = orders;
    state.admin.broadcasts = broadcasts;
}

function render() {
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
        return { title: state.catalog.section, subtitle: `${getSectionProducts(state.catalog.section).length} товаров` };
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
                <img src="${visual.icon}" alt="${escapeAttr(section.name)}">
            </div>
            <div class="section-card-body">
                <p class="section-card-title">${escapeHtml(section.name)}</p>
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
                <h2 class="page-title">${escapeHtml(state.catalog.section)}</h2>
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
    return `
        <article class="product-card" data-action="open-product" data-product-id="${product.id}">
            <div class="product-card-visual" style="background:linear-gradient(135deg, ${visual.palette[0]}, ${visual.palette[1]});">
                <img src="${visual.icon}" alt="${escapeAttr(product.name)}">
                <button type="button" class="favorite-fab ${favorite ? "active" : ""}" data-action="toggle-favorite" data-product-id="${product.id}">${favorite ? "♥" : "♡"}</button>
            </div>
            <div class="product-card-body">
                <p class="product-card-title">${escapeHtml(product.name)}</p>
                <p class="product-card-subtitle">${escapeHtml(product.brand || product.category || "")}</p>
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
                    ${renderField("phone", "Телефон", state.checkout.form.phone, "+7 900 000-00-00", true)}
                    ${renderField("email", "Email", state.checkout.form.email, "mail@company.ru", true)}
                    ${renderField("address", "Адрес доставки", state.checkout.form.address, "Регион, район, населённый пункт", true)}
                    <div class="field">
                        <label>Реквизиты</label>
                        <label class="upload-box">
                            <input type="file" data-field="checkout-attachment" multiple>
                            <div style="font-size:2rem;">📎</div>
                            <div>Прикрепить реквизиты</div>
                            <div class="upload-hint">Фото, PDF, Word — любой формат</div>
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
    return `
        <section class="page stack">
            <div class="admin-layout">
                <aside class="admin-menu-wrap">
                    <div class="admin-menu">
                        ${renderAdminMenuButton("dashboard", "Дашборд", null)}
                        ${renderAdminMenuButton("catalog", "Каталог", null)}
                        ${renderAdminMenuButton("manufacturers", "Производители", null)}
                        ${renderAdminMenuButton("orders", "Заявки", getPendingOrdersCount())}
                        ${renderAdminMenuButton("broadcasts", "Рассылка", null)}
                    </div>
                </aside>
                <div class="admin-content">
                    ${renderAdminContent()}
                </div>
            </div>
        </section>
    `;
}

function renderAdminMenuButton(key, label, badge) {
    return `
        <button class="admin-menu-btn ${state.admin.menu === key ? "active" : ""}" data-action="admin-menu" data-menu="${key}">
            ${escapeHtml(label)}
            ${badge ? `<span class="pending-badge">${badge}</span>` : ""}
        </button>
    `;
}

function renderAdminContent() {
    switch (state.admin.menu) {
        case "catalog":
            return renderAdminCatalog();
        case "manufacturers":
            return renderAdminManufacturers();
        case "orders":
            return renderAdminOrders();
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
        <div class="admin-card">
            <h2>Дашборд</h2>
            <div class="stats-grid" style="margin-top:12px;">
                <div class="stat-tile"><span>Заявок всего</span><strong>${dashboard.ordersTotal || 0}</strong></div>
                <div class="stat-tile"><span>В ожидании</span><strong>${dashboard.ordersPending || 0}</strong></div>
                <div class="stat-tile"><span>Товаров в каталоге</span><strong>${dashboard.productsTotal || 0}</strong></div>
                <div class="stat-tile"><span>Пользователей</span><strong>${dashboard.usersTotal || 0}</strong><span>+${dashboard.usersAddedThisMonth || 0} за месяц</span></div>
            </div>
        </div>
        <div class="admin-card">
            <div class="summary-row">
                <h3>Последние заявки</h3>
                <button class="table-action" data-action="admin-menu" data-menu="orders">Все заявки</button>
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
                                <td data-label="Товары">${escapeHtml(order.items.map(item => item.productName).join(", "))}</td>
                                <td data-label="Дата">${formatDate(order.createdAt)}</td>
                                <td data-label="Статус">${escapeHtml(order.statusLabel)}</td>
                                <td data-label="Действие"><button class="table-action" data-action="open-admin-order" data-order-id="${order.id}">Открыть</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        </div>
    `;
}

function renderAdminCatalog() {
    const products = getAdminFilteredProducts();
    return `
        <div class="admin-split">
            <div class="admin-card">
                <div class="summary-row">
                    <h3>Структура</h3>
                    <button class="table-action" data-action="open-admin-product">+ Добавить товар</button>
                </div>
                <div class="admin-tree">
                    ${getAdminSectionTree().map(section => `
                        <div class="admin-tree-group">
                            <div class="admin-tree-title">
                                <button data-action="admin-section" data-section="${escapeAttr(section.name)}">${escapeHtml(section.name)}</button>
                                <span class="pill-count">${section.count}</span>
                            </div>
                            <div class="admin-tree-list">
                                ${section.children.map(child => `<button class="admin-tree-item" data-action="admin-section" data-section="${escapeAttr(section.name)}" data-category="${escapeAttr(child)}">${escapeHtml(child)}</button>`).join("")}
                            </div>
                        </div>
                    `).join("")}
                </div>
            </div>
            <div class="admin-card">
                <div class="summary-row">
                    <h3>Товары</h3>
                    <button class="table-action" data-action="open-admin-product">+ Добавить товар</button>
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
                        <thead><tr><th>Товар</th><th>Раздел</th><th>Производитель</th><th>Цена</th><th>Остаток</th><th>Статус</th><th></th></tr></thead>
                        <tbody>
                            ${products.map(product => `
                                <tr>
                                    <td data-label="Товар">${escapeHtml(product.name)}</td>
                                    <td data-label="Раздел">${escapeHtml(product.category || "Прочее")}${product.subcategory ? `<div class="search-muted">${escapeHtml(product.subcategory)}</div>` : ""}</td>
                                    <td data-label="Производитель">${escapeHtml(product.brand || "—")}</td>
                                    <td data-label="Цена">${product.price == null ? "По запросу" : formatPrice(product.price)}</td>
                                    <td data-label="Остаток">${product.stockQuantity ?? "—"}</td>
                                    <td data-label="Статус">${product.active ? "Активен" : "Скрыт"}</td>
                                    <td data-label="Действие"><button class="table-action" data-action="open-admin-product" data-product-id="${product.id}">Открыть</button></td>
                                </tr>
                            `).join("")}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `;
}

function renderAdminManufacturers() {
    return `
        <div class="admin-card">
            <div class="summary-row">
                <h3>Производители</h3>
                <button class="table-action" data-action="open-manufacturer-modal">+ Добавить производителя</button>
            </div>
            <div class="admin-table-wrap">
                <table class="admin-table">
                    <thead><tr><th>Название</th><th>Товаров</th><th></th></tr></thead>
                    <tbody>
                        ${state.admin.manufacturers.map(item => `
                            <tr>
                                <td data-label="Название">${escapeHtml(item.name)}</td>
                                <td data-label="Товаров">${item.productsCount}</td>
                                <td data-label="Действие">
                                    <div style="display:flex;gap:8px;">
                                        <button class="table-action" data-action="edit-manufacturer" data-id="${item.id || ""}" data-name="${escapeAttr(item.name)}">Редактировать</button>
                                        ${item.id ? `<button class="table-action" data-action="delete-manufacturer" data-id="${item.id}">Удалить</button>` : ""}
                                    </div>
                                </td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        </div>
    `;
}

function renderAdminOrders() {
    const orders = getFilteredAdminOrders();
    return `
        <div class="admin-card">
            <h3>Заявки</h3>
            <div class="admin-toolbar" style="margin-top:12px;">
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
                <div class="admin-form-row">
                    <input type="date" data-field="admin-order-from" value="${escapeAttr(state.admin.orderFilters.from)}">
                    <input type="date" data-field="admin-order-to" value="${escapeAttr(state.admin.orderFilters.to)}">
                </div>
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
                                <td data-label="Статус">${escapeHtml(order.statusLabel)}</td>
                                <td data-label="Действие"><button class="table-action" data-action="open-admin-order" data-order-id="${order.id}">Открыть</button></td>
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
        <div class="admin-split">
            <div class="admin-card">
                <h3>Рассылка</h3>
                <div class="form-grid" style="margin-top:12px;">
                    <div class="field">
                        <label>Фото</label>
                        <label class="upload-box">
                            <input type="file" accept="image/*" data-field="broadcast-image">
                            <div style="font-size:2rem;">🖼️</div>
                            <div>${state.admin.broadcastForm.imageName ? escapeHtml(state.admin.broadcastForm.imageName) : "Загрузить изображение"}</div>
                        </label>
                    </div>
                    <div class="field">
                        <label>Текст сообщения</label>
                        <textarea data-field="broadcast-text" placeholder="Текст сообщения для всех пользователей">${escapeHtml(state.admin.broadcastForm.text)}</textarea>
                    </div>
                    <div class="summary-card">
                        <strong>Предпросмотр</strong>
                        ${state.admin.broadcastForm.imageUrl ? `<img src="${escapeAttr(state.admin.broadcastForm.imageUrl)}" alt="" style="border-radius:14px;max-height:180px;object-fit:cover;">` : ""}
                        <div>${escapeHtml(state.admin.broadcastForm.text || "Сообщение появится здесь")}</div>
                    </div>
                    <button class="primary-btn" data-action="send-broadcast" ${state.admin.broadcastForm.sending ? "disabled" : ""}>Отправить всем (${stats.subscribersCount || 0} чел.)</button>
                </div>
            </div>
            <div class="admin-stack">
                <div class="admin-card">
                    <h3>Статистика</h3>
                    <div class="stats-grid" style="margin-top:12px;">
                        <div class="stat-tile"><span>Подписчики</span><strong>${stats.subscribersCount || 0}</strong></div>
                        <div class="stat-tile"><span>Рассылок</span><strong>${stats.broadcastsCount || 0}</strong></div>
                        <div class="stat-tile"><span>Новых за месяц</span><strong>${stats.newUsersThisMonth || 0}</strong></div>
                        <div class="stat-tile"><span>Последняя</span><strong>${stats.lastBroadcastAt ? formatDate(stats.lastBroadcastAt) : "—"}</strong></div>
                    </div>
                </div>
                <div class="admin-card">
                    <h3>История рассылок</h3>
                    <div class="history-list" style="margin-top:12px;">
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
    const categoriesTree = buildCategoriesTree(sectionProducts);
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
                            <button class="filter-chip ${draft.sections.includes(section.name) ? "active" : ""}" data-action="toggle-filter-section" data-section="${escapeAttr(section.name)}">${escapeHtml(section.name)}</button>
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
                    <h4>Категория</h4>
                    <div class="tree-list">
                        ${Object.entries(categoriesTree).map(([parent, children]) => `
                            <div>
                                <label class="tree-row">
                                    <input type="checkbox" data-field="filter-category" value="${escapeAttr(parent)}" ${draft.categories.includes(parent) ? "checked" : ""}>
                                    <span>${escapeHtml(parent)}</span>
                                </label>
                                ${children.length ? `<div class="tree-children">${children.map(child => `
                                    <label class="tree-row">
                                        <input type="checkbox" data-field="filter-category" value="${escapeAttr(`${parent}::${child}`)}" ${draft.categories.includes(`${parent}::${child}`) ? "checked" : ""}>
                                        <span>${escapeHtml(child)}</span>
                                    </label>`).join("")}</div>` : ""}
                            </div>
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
    return `
        <div class="modal">
            <div class="modal-backdrop" data-action="close-admin-product"></div>
            <div class="modal-sheet">
                <div class="modal-head">
                    <div class="modal-title-wrap"><div class="modal-title">${product ? "Редактирование товара" : "Добавить товар"}</div></div>
                    <button class="modal-close" data-action="close-admin-product">×</button>
                </div>
                <form class="admin-form-grid" data-form="admin-product">
                    <input type="hidden" name="productId" value="${product?.id || ""}">
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Раздел</label><select name="category">${renderOptions(categories.map(item => [item, item]), product?.category || "")}</select></div>
                        <div class="admin-field"><label>Категория</label><input name="subcategory" value="${escapeAttr(product?.subcategory || "")}"></div>
                    </div>
                    <div class="admin-field"><label>Название</label><input name="name" required value="${escapeAttr(product?.name || "")}"></div>
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Производитель</label><input name="brand" required value="${escapeAttr(product?.brand || "")}"></div>
                        <div class="admin-field"><label>Ед. измерения</label><input name="unitName" value="${escapeAttr(product?.unitName || "шт")}"></div>
                    </div>
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Цена</label><input name="price" type="number" min="0" step="0.01" value="${escapeAttr(product?.price ?? "")}"></div>
                        <div class="admin-field"><label>Старая цена</label><input name="oldPrice" type="number" min="0" step="0.01" value="${escapeAttr(product?.oldPrice ?? "")}"></div>
                    </div>
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Остаток</label><input name="stockQuantity" type="number" min="0" step="1" value="${escapeAttr(product?.stockQuantity ?? "")}"></div>
                        <div class="admin-field"><label>Показывать</label><select name="active">${renderOptions([["true", "Да"], ["false", "Нет"]], String(product?.active ?? true))}</select></div>
                    </div>
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Тип упаковки</label><select name="packageType">${renderOptions([["", "Не выбрано"], ["канистра", "Канистра"], ["коробка", "Коробка"], ["мешок", "Мешок"], ["п.е.", "П.е."], ["тонна", "Тонна"]], product?.packageType || "")}</select></div>
                        <div class="admin-field"><label>Упаковка / объём</label><input name="packageDescription" value="${escapeAttr(product?.packageDescription || "")}"></div>
                    </div>
                    <div class="admin-form-row">
                        <div class="admin-field"><label>Минимальный шаг</label><input name="orderStep" type="number" min="1" step="1" value="${escapeAttr(product?.orderStep ?? 1)}"></div>
                        <div class="admin-field"><label>Минимум</label><input name="minOrderQuantity" type="number" min="1" step="1" value="${escapeAttr(product?.minOrderQuantity ?? 1)}"></div>
                    </div>
                    <div class="admin-field"><label>Описание</label><textarea name="description">${escapeHtml(product?.description || "")}</textarea></div>
                    <div class="admin-field"><label>Действующее вещество</label><input name="activeIngredient" value="${escapeAttr(product?.activeIngredient || "")}"></div>
                    <div class="admin-field"><label>Культуры</label><input name="cultures" value="${escapeAttr((product?.cultures || []).join(", "))}"></div>
                    <div class="admin-field"><label>Назначение</label><input name="tags" value="${escapeAttr((product?.tags || []).join(", "))}"></div>
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
            <div class="modal-sheet">
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
            <div class="modal-sheet">
                <div class="modal-head">
                    <div class="modal-title-wrap">
                        <div class="modal-title">${escapeHtml(order.publicCode)}</div>
                        <div class="modal-subtitle">${escapeHtml(order.statusLabel)}</div>
                    </div>
                    <button class="modal-close" data-action="close-admin-order">×</button>
                </div>
                <div class="product-detail-body">
                    <div class="summary-card">
                        <div class="summary-row"><span>Клиент</span><strong>${escapeHtml(order.customerName || "")}</strong></div>
                        <div class="summary-row"><span>Телефон</span><span>${escapeHtml(order.customerPhone || "")}</span></div>
                        <div class="summary-row"><span>Email</span><span>${escapeHtml(order.customerEmail || "—")}</span></div>
                        <div class="summary-row"><span>Адрес</span><span>${escapeHtml(order.deliveryAddress || "—")}</span></div>
                        <div class="summary-row"><span>Комментарий</span><span>${escapeHtml(order.comment || "—")}</span></div>
                    </div>
                    <div class="summary-card">
                        ${order.items.map(item => `<div class="summary-row"><span>${escapeHtml(item.productName)} × ${formatQuantity(item.quantity)}</span><strong>${formatPrice((item.unitPrice || 0) * item.quantity)}</strong></div>`).join("")}
                        <div class="summary-row"><strong>Итого</strong><strong>${formatPrice(order.totalPrice || 0)}</strong></div>
                    </div>
                    ${(order.attachments || []).length ? `
                        <div class="summary-card">
                            <strong>Прикреплённые реквизиты</strong>
                            ${(order.attachments || []).map(file => `<a class="link-tile" href="${escapeAttr(file.downloadUrl)}" target="_blank"><div class="link-tile-text"><strong>${escapeHtml(file.originalName || file.storedName)}</strong></div><span class="cell-arrow">›</span></a>`).join("")}
                        </div>
                    ` : ""}
                    <div class="admin-actions">
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
            <button class="ghost-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="CANCELLED">Отменить заявку</button>
            <button class="primary-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="IN_PROGRESS">Принять → В работе</button>
        `;
    }
    if (order.status === "IN_PROGRESS") {
        return `
            <button class="ghost-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="CANCELLED">Отменить заявку</button>
            <button class="primary-btn" data-action="set-order-status" data-order-id="${order.id}" data-status="COMPLETED">Отметить как оплачен</button>
        `;
    }
    return `<button class="secondary-btn" disabled>Заявка завершена</button>`;
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
    if (action === "admin-menu") {
        state.admin.menu = button.dataset.menu;
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
    if (action === "close-admin-order") {
        state.admin.orderModal = { open: false, orderId: null };
        render();
        return;
    }
    if (action === "set-order-status") {
        updateOrderStatus(Number(button.dataset.orderId), button.dataset.status).catch(handleActionError);
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
        if (state.checkout.errors[key]) {
            delete state.checkout.errors[key];
        }
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
    if (field === "filter-category") {
        toggleDraftFilterArray("categories", event.target.value, event.target.checked);
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
        const formData = new FormData();
        formData.append("maxUserId", String(state.maxUserId || 0));
        formData.append("file", file);
        const uploaded = await fetchJson("/api/uploads/order-attachment", {
            method: "POST",
            body: formData,
        });
        state.checkout.uploaded.push(uploaded);
    }
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

async function deleteAdminProduct(productId) {
    await fetchJson(`/api/admin/products/${productId}?maxUserId=${state.maxUserId}`, { method: "DELETE" });
    await refreshCatalogData();
    state.admin.productEditor = { open: false, productId: null };
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
    if (state.sections?.length) return state.sections;
    const grouped = new Map();
    state.products.forEach(product => {
        const name = product.category || "Прочее";
        if (!grouped.has(name)) {
            grouped.set(name, { name, description: getSectionVisual(name).description, productsCount: 0 });
        }
        grouped.get(name).productsCount += 1;
    });
    return [...grouped.values()];
}

function getSectionProducts(sectionName) {
    return state.products.filter(product => (product.category || "Прочее") === sectionName);
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
        filtered = filtered.filter(product => applied.sections.includes(product.category || "Прочее"));
    }
    if (applied.manufacturers.length) {
        filtered = filtered.filter(product => applied.manufacturers.includes(product.brand));
    }
    if (applied.categories.length) {
        filtered = filtered.filter(product => {
            const direct = product.subcategory || product.itemType || "Без категории";
            const parent = product.category || "Прочее";
            return applied.categories.includes(parent) || applied.categories.includes(`${parent}::${direct}`);
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
    return getSectionVisual(product.category || "Прочее");
}

function getSectionVisual(name) {
    const normalized = normalize(name);
    const matched = SECTION_VISUALS.find(item => item.match.some(match => normalized.includes(match)));
    return matched || {
        icon: "./assets/category-other.svg",
        palette: ["#e1f1de", "#d7e9f6"],
        description: "Актуальные позиции каталога",
    };
}

function buildCategoriesTree(products) {
    const tree = {};
    products.forEach(product => {
        const parent = product.category || "Прочее";
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
        const section = product.category || "Прочее";
        const child = product.subcategory || product.itemType || "Без категории";
        if (!grouped[section]) grouped[section] = { name: section, count: 0, children: [] };
        grouped[section].count += 1;
        if (!grouped[section].children.includes(child)) grouped[section].children.push(child);
    });
    return Object.values(grouped).sort((a, b) => a.name.localeCompare(b.name, "ru", { sensitivity: "base" }));
}

function getAdminFilteredProducts() {
    const search = normalize(state.admin.catalogSearch);
    return state.admin.products.filter(product => {
        if (state.admin.catalogStatus === "ACTIVE" && !product.active) return false;
        if (state.admin.catalogStatus === "HIDDEN" && product.active) return false;
        if (state.admin.catalogSection && (product.category || "Прочее") !== state.admin.catalogSection) return false;
        if (state.admin.catalogCategory && (product.subcategory || product.itemType || "Без категории") !== state.admin.catalogCategory) return false;
        if (search && !normalize([product.name, product.brand, product.category, product.subcategory].join(" ")).includes(search)) return false;
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
    state.checkout.form.phone = state.profile.phone || "";
    state.checkout.form.email = state.profile.email || "";
    state.checkout.form.organization = state.profile.displayName || "";
}

function emptyFilters() {
    return { sections: [], manufacturers: [], categories: [], priceMin: "", priceMax: "" };
}

function cloneFilters(filters) {
    return {
        sections: [...(filters.sections || [])],
        manufacturers: [...(filters.manufacturers || [])],
        categories: [...(filters.categories || [])],
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
    return [...new Set(values)].sort((a, b) => String(a).localeCompare(String(b), "ru", { sensitivity: "base" }));
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
        let message = `Ошибка ${response.status}`;
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
