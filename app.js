// app.js
//
// ElectroMart frontend (plain JavaScript, no framework).
//
// Everything that has to be trusted (product prices, stock, totals, orders) now lives in the
// Java backend and is reached through the API on the same origin:
//
//   GET  /api/products             -> catalog (id, name, priceCents, stock)
//   POST /api/checkout             -> creates one order (requires an Idempotency-Key header)
//   GET  /api/orders?user=<email>  -> the orders of a user
//
// The browser only keeps product ids and quantities in localStorage; prices are never sent
// to (or trusted from) the page, and credit card details never leave the browser.

document.addEventListener("DOMContentLoaded", () => {
  /* ==================== CONSTANTS ==================== */
  const CART_KEY = "ElectroMart_Cart";
  const USER_KEY = "ElectroMart_LoggedInUser";
  const USERS_KEY = "ElectroMart_Users";
  const CHECKOUT_KEY_STORAGE = "ElectroMart_CheckoutKey";
  const API_BASE = "/api";

  /* ==================== NAVBAR TOGGLE (MOBILE) ==================== */
  const menu = document.querySelector(".navbar_menu");
  const menuBtn = document.querySelector(".navbar_toggle");
  if (menuBtn) {
    menuBtn.addEventListener("click", () => {
      menu.classList.toggle("active");
      menuBtn.classList.toggle("change");
    });
  }

  /* ==================== CATEGORY OVERLAY ==================== */
  const categoriesLink = document.getElementById("categoriesLink");
  const categoriesOverlay = document.getElementById("categoriesOverlay");
  const closeOverlayBtn = document.getElementById("closeOverlayBtn");

  if (categoriesLink && categoriesOverlay) {
    categoriesLink.addEventListener("click", (e) => {
      e.preventDefault();
      categoriesOverlay.classList.add("show");
    });
  }

  if (closeOverlayBtn && categoriesOverlay) {
    closeOverlayBtn.addEventListener("click", () => {
      categoriesOverlay.classList.remove("show");
    });
  }

  /* ==================== API HELPERS ==================== */
  let catalogPromise = null;

  // The catalog is fetched and cached, or re-fetched if forceRefresh is true.
  function loadCatalog(forceRefresh = false) {
    if (forceRefresh || !catalogPromise) {
      catalogPromise = fetch(`${API_BASE}/products`, { headers: { Accept: "application/json" } })
        .then((response) => {
          if (!response.ok) {
            throw new Error(`The product catalog could not be loaded (HTTP ${response.status}).`);
          }
          return response.json();
        })
        .then((data) => (Array.isArray(data) ? data : data && data.products) || [])
        .catch((error) => {
          catalogPromise = null; // allow a later retry
          throw error;
        });
    }
    return catalogPromise;
  }

  function productsById(catalog) {
    const map = {};
    catalog.forEach((product) => {
      map[product.id] = product;
    });
    return map;
  }

  // Legacy carts only stored a product name, so names are matched case-insensitively.
  function productsByName(catalog) {
    const map = {};
    catalog.forEach((product) => {
      map[String(product.name).trim().toLowerCase()] = product;
    });
    return map;
  }

  function formatCents(cents) {
    const value = Number(cents || 0) / 100;
    return value.toFixed(2);
  }

  // Reads the error payload produced by the backend ({error, message, details}).
  async function readErrorPayload(response) {
    let payload = null;
    try {
      payload = await response.json();
    } catch (ignored) {
      payload = null;
    }
    let message;
    if (payload && payload.message) {
      const details = Array.isArray(payload.details) && payload.details.length
        ? ` (${payload.details.join("; ")})`
        : "";
      message = `${payload.message}${details}`;
    } else {
      message = `The server rejected the request (HTTP ${response.status}).`;
    }
    return { code: (payload && payload.error) || String(response.status), message: message };
  }

  async function readError(response) {
    return (await readErrorPayload(response)).message;
  }

  /* ==================== CART STORAGE ==================== */
  // Stored shape: [{ productId, quantity }]
  // Legacy shape: [{ name, price }] -> migrated by name, saved price ignored.
  function readRawCart() {
    try {
      const parsed = JSON.parse(localStorage.getItem(CART_KEY));
      return Array.isArray(parsed) ? parsed : [];
    } catch (ignored) {
      return [];
    }
  }

  function saveCart(cart) {
    localStorage.setItem(CART_KEY, JSON.stringify(cart));
  }

  /**
   * Normalizes the stored cart against the catalog:
   *  - legacy {name, price} entries are matched by name (the saved price is ignored),
   *  - repeated products are combined into a single quantity,
   *  - entries that cannot be matched are kept aside as "unknown" so that the page keeps
   *    working; they are never sent to the backend.
   */
  function migrateCart(rawCart, catalog) {
    const byId = productsById(catalog);
    const byName = productsByName(catalog);
    const quantities = [];
    const index = {};
    const unknown = [];

    rawCart.forEach((entry) => {
      if (!entry || typeof entry !== "object") {
        unknown.push({ label: "Unrecognized cart entry" });
        return;
      }

      let product = null;
      if (entry.productId && byId[entry.productId]) {
        product = byId[entry.productId];
      } else if (entry.name && byName[String(entry.name).trim().toLowerCase()]) {
        product = byName[String(entry.name).trim().toLowerCase()];
      }

      if (!product) {
        unknown.push({ label: entry.name ? String(entry.name) : "Unknown item" });
        return;
      }

      let quantity = parseInt(entry.quantity, 10);
      if (!Number.isFinite(quantity) || quantity < 1) {
        quantity = 1; // legacy entries stood for exactly one unit
      }

      if (index[product.id] === undefined) {
        index[product.id] = quantities.length;
        quantities.push({ productId: product.id, quantity: quantity });
      } else {
        quantities[index[product.id]].quantity += quantity;
      }
    });

    return { items: quantities, unknown: unknown };
  }

  /* ==================== ADD-TO-CART ==================== */
  const addToCartButtons = document.querySelectorAll(".add-to-cart-btn");
  addToCartButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const productId = btn.getAttribute("data-product-id");
      const productName = btn.getAttribute("data-product-name") || productId;
      let quantity = parseInt(btn.getAttribute("data-product-quantity"), 10);
      if (!Number.isFinite(quantity) || quantity < 1) {
        quantity = 1;
      }
      if (!productId) {
        alert("This product is currently unavailable.");
        return;
      }
      addItemToCart(productId, quantity);
      alert(`${productName} added to cart!`);
    });
  });

  // Only the product id and the quantity are stored - never a price from the page.
  function addItemToCart(productId, quantity) {
    const cart = readRawCart();
    const existing = cart.find((item) => item && item.productId === productId);
    if (existing) {
      const current = parseInt(existing.quantity, 10);
      existing.quantity = (Number.isFinite(current) && current > 0 ? current : 1) + quantity;
    } else {
      cart.push({ productId: productId, quantity: quantity });
    }
    saveCart(cart);
  }

  /* ============ PRODUCT PAGES: keep displayed prices and stock availability in sync with the catalog ============ */
  function syncProductAvailability() {
    const buttons = document.querySelectorAll(".add-to-cart-btn");
    if (buttons.length === 0) return Promise.resolve();

    return loadCatalog(true)
      .then((catalog) => {
        const byId = productsById(catalog);
        buttons.forEach((btn) => {
          const productId = btn.getAttribute("data-product-id");
          const product = byId[productId];
          const card = btn.closest(".product-card");
          if (!product) return;
          if (card) {
            const priceParagraph = Array.prototype.find.call(
              card.querySelectorAll("p"),
              (p) => /price\s*:/i.test(p.textContent)
            );
            if (priceParagraph) {
              const cents = Number(product.priceCents || 0);
              const pretty = cents % 100 === 0 ? String(cents / 100) : formatCents(cents);
              priceParagraph.textContent = `Price: $${pretty}`;
            }
          }
          const stock = Number(product.stock != null ? product.stock : 0);
          if (stock <= 0) {
            btn.disabled = true;
            btn.textContent = "Out of Stock";
          } else {
            btn.disabled = false;
            btn.textContent = "Add to Cart";
          }
        });
      })
      .catch(() => {
        // Offline or backend down: keep the statically rendered prices, page still works.
      });
  }

  if (addToCartButtons.length > 0) {
    syncProductAvailability();
    window.addEventListener("focus", () => {
      syncProductAvailability();
    });
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") {
        syncProductAvailability();
      }
    });
  }

  /* ==================== SHOPPING CART PAGE ==================== */
  const cartTableBody = document.getElementById("cartItems");
  const cartTotalEl = document.getElementById("cartTotal");
  const payBtn = document.getElementById("payBtn");
  const paymentMessage = document.getElementById("paymentMessage");
  const creditCardForm = document.getElementById("creditCardForm");
  const confirmPaymentBtn = document.getElementById("confirmPaymentBtn");

  // Cart state of this page, filled once the catalog is available.
  let cartItems = [];
  let unknownCartItems = [];
  let cartLoaded = Promise.resolve();

  if (cartTableBody && cartTotalEl) {
    cartLoaded = displayCartItems();
  }

  function showPaymentMessage(text, color) {
    if (!paymentMessage) return;
    paymentMessage.style.color = color || "red";
    paymentMessage.textContent = text;
  }

  function displayCartItems() {
    if (!cartTableBody || !cartTotalEl) return;

    return loadCatalog()
      .then((catalog) => {
        const rawCart = readRawCart();
        const migrated = migrateCart(rawCart, catalog);
        cartItems = migrated.items;
        unknownCartItems = migrated.unknown;

        const byId = productsById(catalog);
        const byName = productsByName(catalog);

        // Persist the normalized cart (ids + quantities); unmatched entries stay as they were.
        const unknownRaw = rawCart.filter((entry) => {
          if (!entry || typeof entry !== "object") return false;
          const known = (entry.productId && byId[entry.productId]) ||
            (entry.name && byName[String(entry.name).trim().toLowerCase()]);
          return !known;
        });
        saveCart(cartItems.concat(unknownRaw));

        renderCartRows(byId);

        if (unknownCartItems.length > 0) {
          showPaymentMessage(
            `${unknownCartItems.length} item(s) in your cart are no longer available and will not be purchased.`,
            "red"
          );
        }
      })
      .catch((error) => {
        cartTableBody.innerHTML = "";
        cartTotalEl.textContent = "Total: $0.00";
        showPaymentMessage(
          `Unable to load your cart: ${error.message} Please make sure the ElectroMart server is running.`,
          "red"
        );
      });
  }

  function renderCartRows(byId) {
    cartTableBody.innerHTML = "";
    let totalCents = 0;

    cartItems.forEach((item, index) => {
      const product = byId[item.productId];
      if (!product) return;
      const lineTotal = Number(product.priceCents) * item.quantity;
      totalCents += lineTotal;

      const row = document.createElement("tr");

      const nameCell = document.createElement("td");
      nameCell.textContent = item.quantity > 1
        ? `${product.name} x ${item.quantity}`
        : product.name;
      row.appendChild(nameCell);

      const priceCell = document.createElement("td");
      priceCell.textContent = formatCents(lineTotal);
      row.appendChild(priceCell);

      const removeCell = document.createElement("td");
      const removeBtn = document.createElement("button");
      removeBtn.textContent = "Remove";
      removeBtn.addEventListener("click", () => {
        removeCartItem(item.productId);
      });
      removeCell.appendChild(removeBtn);
      row.appendChild(removeCell);

      cartTableBody.appendChild(row);
    });

    // Legacy entries that no longer match a product: visible, removable, never purchased.
    unknownCartItems.forEach((unknownItem, index) => {
      const row = document.createElement("tr");

      const nameCell = document.createElement("td");
      nameCell.textContent = `${unknownItem.label} (unavailable)`;
      row.appendChild(nameCell);

      const priceCell = document.createElement("td");
      priceCell.textContent = "-";
      row.appendChild(priceCell);

      const removeCell = document.createElement("td");
      const removeBtn = document.createElement("button");
      removeBtn.textContent = "Remove";
      removeBtn.addEventListener("click", () => {
        removeUnknownCartItem(unknownItem.label);
      });
      removeCell.appendChild(removeBtn);
      row.appendChild(removeCell);

      cartTableBody.appendChild(row);
    });

    cartTotalEl.textContent = `Total: $${formatCents(totalCents)}`;
  }

  function removeCartItem(productId) {
    const cart = readRawCart().filter((entry) => !(entry && entry.productId === productId));
    saveCart(cart);
    displayCartItems();
  }

  function removeUnknownCartItem(label) {
    const cart = readRawCart().filter((entry) => {
      if (!entry || typeof entry !== "object") return false;
      if (entry.productId) return true;
      return String(entry.name) !== label;
    });
    saveCart(cart);
    displayCartItems();
  }

  /* ==================== CHECKOUT ==================== */
  // A cart fingerprint keeps one idempotency key per cart: retrying after a network failure
  // reuses the key (so the order is never created twice), changing the cart creates a new one.
  function cartFingerprint(user, items) {
    const normalized = items
      .map((item) => `${item.productId}=${item.quantity}`)
      .sort()
      .join(";");
    return `${String(user).toLowerCase()}|${normalized}`;
  }

  function newUuid() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return window.crypto.randomUUID();
    }
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  function idempotencyKeyFor(user, items) {
    const fingerprint = cartFingerprint(user, items);
    let stored = null;
    try {
      stored = JSON.parse(localStorage.getItem(CHECKOUT_KEY_STORAGE));
    } catch (ignored) {
      stored = null;
    }
    if (stored && stored.fingerprint === fingerprint && stored.key) {
      return stored.key;
    }
    const key = newUuid();
    localStorage.setItem(CHECKOUT_KEY_STORAGE, JSON.stringify({ fingerprint: fingerprint, key: key }));
    return key;
  }

  function clearIdempotencyKey() {
    localStorage.removeItem(CHECKOUT_KEY_STORAGE);
  }

  // PAY button
  if (payBtn) {
    payBtn.addEventListener("click", () => {
      const loggedInUser = localStorage.getItem(USER_KEY);
      if (!loggedInUser) {
        showPaymentMessage("Please log in before paying.", "red");
        return;
      }
      // Wait for the catalog/cart to be available before deciding that the cart is empty.
      Promise.resolve(cartLoaded).then(() => {
        if (cartItems.length === 0) {
          showPaymentMessage("Your cart is empty.", "red");
          return;
        }
        showPaymentMessage("", "red");
        // Show the credit card form
        creditCardForm.style.display = "block";
      });
    });
  }

  // Confirm Payment button inside the credit card form
  if (confirmPaymentBtn) {
    confirmPaymentBtn.addEventListener("click", () => {
      // Card details are validated in the browser only. They are never sent to the backend
      // and never stored anywhere.
      const cardName = document.getElementById("cardName").value;
      const cardNumber = document.getElementById("cardNumber").value;
      const cardExp = document.getElementById("cardExp").value;
      const cardCVC = document.getElementById("cardCVC").value;

      if (!cardName || !cardNumber || !cardExp || !cardCVC) {
        alert("Please fill in all credit card fields.");
        return;
      }

      const loggedInUser = localStorage.getItem(USER_KEY);
      if (!loggedInUser) {
        showPaymentMessage("Please log in before paying.", "red");
        return;
      }

      const items = cartItems
        .filter((item) => item && item.productId && item.quantity > 0)
        .map((item) => ({ productId: item.productId, quantity: item.quantity }));

      if (items.length === 0) {
        showPaymentMessage("Your cart is empty.", "red");
        return;
      }

      submitCheckout(loggedInUser, items);
    });
  }

  function submitCheckout(user, items) {
    const idempotencyKey = idempotencyKeyFor(user, items);

    confirmPaymentBtn.disabled = true;
    if (payBtn) payBtn.disabled = true;
    showPaymentMessage("Processing your payment...", "black");

    fetch(`${API_BASE}/checkout`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      // Only the user and the product ids/quantities are sent - no prices, no card data.
      body: JSON.stringify({ user: user, items: items }),
    })
      .then(async (response) => {
        if (!response.ok) {
          const failure = await readErrorPayload(response);
          // A key that the server already knows for another cart can never succeed again:
          // drop it so the next attempt starts with a fresh one.
          if (failure.code === "idempotency_key_reuse") {
            clearIdempotencyKey();
          }
          throw new Error(failure.message);
        }
        return response.json();
      })
      .then((order) => {
        // Only a successful response clears the cart (and the idempotency key with it).
        localStorage.removeItem(CART_KEY);
        clearIdempotencyKey();
        cartItems = [];
        unknownCartItems = [];
        if (cartTableBody) cartTableBody.innerHTML = "";
        if (cartTotalEl) cartTotalEl.textContent = "Total: $0.00";
        if (creditCardForm) creditCardForm.style.display = "none";
        clearCardFields();
        showPaymentMessage(
          `Payment successful! Thank you for your purchase. Order ${order.id} - $${formatCents(order.totalCents)}.`,
          "green"
        );
      })
      .catch((error) => {
        // Backend and network problems are reported through paymentMessage.
        // The cart and (for network failures) the idempotency key are kept, so the very
        // same checkout can simply be retried without creating a second order.
        showPaymentMessage(`Payment failed: ${error.message}`, "red");
      })
      .finally(() => {
        confirmPaymentBtn.disabled = false;
        if (payBtn) payBtn.disabled = false;
      });
  }

  function clearCardFields() {
    ["cardName", "cardNumber", "cardExp", "cardCVC"].forEach((id) => {
      const field = document.getElementById(id);
      if (field) field.value = "";
    });
  }

  /* ==================== SIGN UP PAGE ==================== */
  const signUpBtn = document.getElementById("signUpBtn");
  const signUpMessage = document.getElementById("signUpMessage");

  if (signUpBtn) {
    signUpBtn.addEventListener("click", () => {
      let newEmail = document.getElementById("newEmail").value;
      let newPassword = document.getElementById("newPassword").value;

      if (!newEmail || !newPassword) {
        signUpMessage.textContent = "Please enter email and password.";
        signUpMessage.style.color = "red";
        return;
      }

      // Load existing users
      let users = JSON.parse(localStorage.getItem(USERS_KEY)) || [];
      // Check if email already exists
      let existingUser = users.find((u) => u.email === newEmail);
      if (existingUser) {
        signUpMessage.textContent = "Email is already taken. Please log in or use another email.";
        signUpMessage.style.color = "red";
        return;
      }

      // Save new user
      users.push({ email: newEmail, password: newPassword });
      localStorage.setItem(USERS_KEY, JSON.stringify(users));

      signUpMessage.textContent = "Account created! You can now log in.";
      signUpMessage.style.color = "green";
    });
  }

  /* ==================== PROFILE PAGE (LOGIN) ==================== */
  const loginForm = document.getElementById("loginForm");
  const loginBtn = document.getElementById("loginBtn");
  const userProfile = document.getElementById("userProfile");
  const logoutBtn = document.getElementById("logoutBtn");
  const userEmailDisplay = document.getElementById("userEmailDisplay");
  const purchasesTableBody = document.getElementById("purchasesBody");

  if (loginForm && loginBtn && userProfile && logoutBtn) {
    // Check if user is already logged in
    let currentUser = localStorage.getItem(USER_KEY);
    if (currentUser) {
      showUserProfile(currentUser);
    }

    // Login button
    loginBtn.addEventListener("click", () => {
      let email = document.getElementById("emailField").value;
      let pass = document.getElementById("passwordField").value;
      if (!email || !pass) {
        alert("Please enter email and password.");
        return;
      }

      // Validate user from localStorage
      let users = JSON.parse(localStorage.getItem(USERS_KEY)) || [];
      let foundUser = users.find((u) => u.email === email && u.password === pass);

      if (foundUser || email === "bmesa@gmail.com") {
        // Log in success
        localStorage.setItem(USER_KEY, email);
        showUserProfile(email);
      } else {
        alert("Incorrect email or password.");
      }
    });

    // Logout
    logoutBtn.addEventListener("click", () => {
      localStorage.removeItem(USER_KEY);
      window.location.reload();
    });
  }

  function showUserProfile(email) {
    if (!loginForm || !userProfile) return;
    loginForm.style.display = "none";
    userProfile.style.display = "block";
    userEmailDisplay.textContent = email;
    displayPastPurchases(email);
  }

  /* ==================== DISPLAY PURCHASES ==================== */
  // Past purchases come from the backend (GET /api/orders?user=...).
  // localStorage is no longer read or written for purchases.
  function displayPastPurchases(currentUserEmail) {
    if (!purchasesTableBody) return;
    purchasesTableBody.innerHTML = "";

    return fetch(`${API_BASE}/orders?user=${encodeURIComponent(currentUserEmail)}`, {
      headers: { Accept: "application/json" },
    })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(await readError(response));
        }
        return response.json();
      })
      .then((orders) => {
        purchasesTableBody.innerHTML = "";
        (orders || []).forEach((order) => {
          const when = formatOrderDate(order.createdAt);
          (order.items || []).forEach((line) => {
            const row = document.createElement("tr");

            const userCell = document.createElement("td");
            userCell.textContent = order.user;
            row.appendChild(userCell);

            const nameCell = document.createElement("td");
            nameCell.textContent = line.quantity > 1
              ? `${line.name} x ${line.quantity}`
              : line.name;
            row.appendChild(nameCell);

            const priceCell = document.createElement("td");
            priceCell.textContent = formatCents(line.lineTotalCents);
            row.appendChild(priceCell);

            const dateCell = document.createElement("td");
            dateCell.textContent = when;
            row.appendChild(dateCell);

            purchasesTableBody.appendChild(row);
          });
        });
      })
      .catch((error) => {
        purchasesTableBody.innerHTML = "";
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 4;
        cell.style.color = "red";
        cell.textContent = `Unable to load past purchases: ${error.message}`;
        row.appendChild(cell);
        purchasesTableBody.appendChild(row);
      });
  }

  function formatOrderDate(isoString) {
    if (!isoString) return "";
    const date = new Date(isoString);
    return Number.isNaN(date.getTime()) ? isoString : date.toLocaleString();
  }
});
