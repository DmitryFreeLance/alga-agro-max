const state = {
    meta: null,
    filters: { cultures: [], categories: [], tags: [] },
    products: [],
    selection: {
        culture: "",
        category: "",
        tag: "",
        search: "",
        sort: "name",
    },
    cart: [],
    currentPage: "catalog",
    maxUserId: null,
};

const nodes = {
    catalogPage: document.getElementById("catalogPage"),
    cartPage: document.getElementById("cartPage"),
    checkoutPage: document.getElementById("checkoutPage"),
    catalogNavButton: document.getElementById("catalogNavButton"),
    cartNavButton: document.getElementById("cartNavButton"),
    cultureChips: document.getElementById("cultureChips"),
    categoryPills: document.getElementById("categoryPills"),
    tagPills: document.getElementById("tagPills"),
    searchInput: document.getElementById("searchInput"),
    sortSelect: document.getElementById("sortSelect"),
    resetFilters: document.getElementById("resetFilters"),
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
    toast: document.getElementById("toast"),
};

const maxBridge = window.WebApp || window.Telegram?.WebApp || null;
const initDataUnsafe = maxBridge?.initDataUnsafe || {};
state.maxUserId = initDataUnsafe?.user?.user_id || null;

bootstrap();

async function bootstrap() {
    bindEvents();
    await Promise.all([loadMeta(), loadFilters()]);
    await loadProducts();
    renderCart();
    showPage("catalog");
}

function bindEvents() {
    nodes.catalogNavButton.addEventListener("click", () => showPage("catalog"));
    nodes.cartNavButton.addEventListener("click", () => showPage("cart"));

    nodes.resetFilters.addEventListener("click", async () => {
        state.selection = { culture: "", category: "", tag: "", search: "", sort: "name" };
        nodes.searchInput.value = "";
        nodes.sortSelect.value = "name";
        renderFilterPills();
        await loadProducts();
    });

    nodes.searchInput.addEventListener("input", debounce(async event => {
        state.selection.search = event.target.value.trim();
        await loadProducts();
    }, 260));

    nodes.sortSelect.addEventListener("change", async event => {
        state.selection.sort = event.target.value;
        await loadProducts();
    });

    nodes.checkoutButton.addEventListener("click", () => {
        if (!state.cart.length) {
            showToast("Сначала добавьте товары в корзину");
            return;
        }
        showPage("checkout");
    });

    nodes.backToCart.addEventListener("click", () => showPage("cart"));
    nodes.backToCatalog.addEventListener("click", () => showPage("catalog"));
    nodes.checkoutForm.addEventListener("submit", submitOrder);
}

async function loadMeta() {
    const response = await fetch("/api/meta");
    state.meta = await response.json();
}

async function loadFilters() {
    const response = await fetch("/api/catalog/filters");
    state.filters = await response.json();
    renderFilterPills();
}

async function loadProducts() {
    const query = new URLSearchParams();
    if (state.selection.culture) query.set("culture", state.selection.culture);
    if (state.selection.category) query.set("category", state.selection.category);
    if (state.selection.tag) query.set("tag", state.selection.tag);
    if (state.selection.search) query.set("search", state.selection.search);
    if (state.selection.sort) query.set("sort", state.selection.sort);

    const response = await fetch(`/api/catalog/products?${query.toString()}`);
    state.products = await response.json();
    renderProducts();
}

function showPage(page) {
    state.currentPage = page;
    nodes.catalogPage.classList.toggle("page-active", page === "catalog");
    nodes.cartPage.classList.toggle("page-active", page === "cart");
    nodes.checkoutPage.classList.toggle("page-active", page === "checkout");
    nodes.catalogNavButton.classList.toggle("active", page === "catalog");
    nodes.cartNavButton.classList.toggle("active", page === "cart");
}

function renderFilterPills() {
    renderSelectableGroup(nodes.cultureChips, ["Все культуры", ...state.filters.cultures], state.selection.culture, value => {
        state.selection.culture = value === "Все культуры" ? "" : value;
        renderFilterPills();
        loadProducts();
    }, true);

    renderSelectableGroup(nodes.categoryPills, ["Все категории", ...state.filters.categories], state.selection.category, value => {
        state.selection.category = value === "Все категории" ? "" : value;
        renderFilterPills();
        loadProducts();
    });

    renderSelectableGroup(nodes.tagPills, ["Все теги", ...state.filters.tags], state.selection.tag, value => {
        state.selection.tag = value === "Все теги" ? "" : value;
        renderFilterPills();
        loadProducts();
    });
}

function renderSelectableGroup(container, items, selected, onClick, isCulture = false) {
    container.innerHTML = "";
    items.forEach(item => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = isCulture ? "chip" : "pill";
        button.textContent = item;
        const active = (selected || "") === item || (!selected && item.startsWith("Все "));
        if (active) button.classList.add("active");
        button.addEventListener("click", () => onClick(item));
        container.appendChild(button);
    });
}

function renderProducts() {
    nodes.productGrid.innerHTML = "";
    nodes.catalogCount.textContent = `${state.products.length} позиций`;
    nodes.catalogTitle.textContent = state.selection.culture
        ? state.selection.culture
        : "Все товары";

    if (!state.products.length) {
        nodes.emptyState.classList.remove("hidden");
        return;
    }
    nodes.emptyState.classList.add("hidden");

    state.products.forEach(product => {
        const card = document.createElement("article");
        card.className = "product-card";
        card.innerHTML = `
            <div class="product-visual">
                <div class="product-badges">
                    <span class="badge">${product.category || "Товар"}</span>
                    ${product.subcategory ? `<span class="badge">${product.subcategory}</span>` : ""}
                </div>
                <strong>${escapeHtml(product.itemType || product.category || "АЛГА")}</strong>
            </div>
            <div>
                <h3 class="product-name">${escapeHtml(product.name)}</h3>
                <p class="product-description">${escapeHtml(product.description || "Товар доступен для заказа.")}</p>
            </div>
            <div class="meta-badges">
                ${(product.cultures || []).slice(0, 3).map(culture => `<span class="badge">${escapeHtml(culture)}</span>`).join("")}
                ${(product.tags || []).slice(0, 2).map(tag => `<span class="badge">${escapeHtml(tag)}</span>`).join("")}
            </div>
            <div class="product-bottom">
                <div class="price">
                    <span class="stock">${product.stockQuantity == null ? "Наличие уточняется" : `Остаток: ${product.stockQuantity} ${product.unitName || ""}`}</span>
                    <strong>${formatPrice(product.price)}</strong>
                </div>
                <button class="primary-button" type="button">Добавить в корзину</button>
            </div>
        `;

        card.querySelector(".primary-button").addEventListener("click", () => addToCart(product));
        nodes.productGrid.appendChild(card);
    });
}

function addToCart(product) {
    const existing = state.cart.find(item => item.id === product.id);
    if (existing) {
        existing.quantity += 1;
    } else {
        state.cart.push({
            id: product.id,
            name: product.name,
            price: Number(product.price || 0),
            quantity: 1,
            unitName: product.unitName || "шт",
        });
    }
    renderCart();
    showToast(`Добавили: ${product.name}`);
}

function renderCart() {
    const totalCount = state.cart.reduce((sum, item) => sum + item.quantity, 0);
    const totalPrice = state.cart.reduce((sum, item) => sum + item.price * item.quantity, 0);
    nodes.cartCount.textContent = totalCount;
    nodes.cartSummaryCount.textContent = `${totalCount} ${pluralize(totalCount, ["товар", "товара", "товаров"])}`;
    nodes.cartTotal.textContent = formatPrice(totalPrice);
    nodes.cartItems.innerHTML = "";

    if (!state.cart.length) {
        nodes.cartItems.innerHTML = `
            <div class="empty-state">
                <h3>Корзина пуста</h3>
                <p>Добавьте нужные товары из каталога.</p>
            </div>
        `;
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

function updateQuantity(productId, delta) {
    const item = state.cart.find(entry => entry.id === productId);
    if (!item) return;
    item.quantity += delta;
    if (item.quantity <= 0) {
        state.cart = state.cart.filter(entry => entry.id !== productId);
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

    const formData = new FormData(nodes.checkoutForm);
    const payload = {
        maxUserId: state.maxUserId,
        name: formData.get("name"),
        phone: formData.get("phone"),
        company: formData.get("company"),
        comment: formData.get("comment"),
        culture: state.selection.culture || null,
        deliveryNote: formData.get("deliveryNote"),
        items: state.cart.map(item => ({
            productId: item.id,
            quantity: item.quantity,
        })),
    };

    const submitButton = nodes.checkoutForm.querySelector('button[type="submit"]');
    submitButton.disabled = true;
    submitButton.textContent = "Отправляем...";

    try {
        const response = await fetch("/api/orders", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        });
        if (!response.ok) {
            throw new Error("Не удалось отправить заказ");
        }
        const data = await response.json();
        state.cart = [];
        renderCart();
        nodes.checkoutForm.reset();
        showPage("catalog");
        showToast(`Заказ ${data.orderCode} отправлен`);
    } catch (error) {
        showToast(error.message || "Ошибка при отправке заказа");
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Отправить заказ";
    }
}

function showToast(message) {
    nodes.toast.textContent = message;
    nodes.toast.classList.remove("hidden");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => nodes.toast.classList.add("hidden"), 2500);
}

function formatPrice(value) {
    const number = Number(value || 0);
    if (!Number.isFinite(number) || number <= 0) {
        return "По запросу";
    }
    return `${new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(number)} ₽`;
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

function debounce(fn, timeout = 250) {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), timeout);
    };
}
