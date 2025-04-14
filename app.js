// app.js

document.addEventListener("DOMContentLoaded", () => {
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
  
    /* ==================== ADD-TO-CART ==================== */
    const addToCartButtons = document.querySelectorAll(".add-to-cart-btn");
    addToCartButtons.forEach((btn) => {
      btn.addEventListener("click", () => {
        const productName = btn.getAttribute("data-product-name");
        const productPrice = btn.getAttribute("data-product-price");
        addItemToCart(productName, productPrice);
        alert(`${productName} added to cart!`);
      });
    });
  
    function addItemToCart(name, price) {
      let cart = JSON.parse(localStorage.getItem("ElectroMart_Cart")) || [];
      cart.push({ name, price });
      localStorage.setItem("ElectroMart_Cart", JSON.stringify(cart));
    }
  
    /* ==================== SHOPPING CART PAGE ==================== */
    const cartTableBody = document.getElementById("cartItems");
    const cartTotalEl = document.getElementById("cartTotal");
    const payBtn = document.getElementById("payBtn");
    const paymentMessage = document.getElementById("paymentMessage");
    const creditCardForm = document.getElementById("creditCardForm");
    const confirmPaymentBtn = document.getElementById("confirmPaymentBtn");
  
    if (cartTableBody && cartTotalEl) {
      displayCartItems();
    }
  
    function displayCartItems() {
      let cart = JSON.parse(localStorage.getItem("ElectroMart_Cart")) || [];
      cartTableBody.innerHTML = "";
      let total = 0;
  
      cart.forEach((item, index) => {
        const { name, price } = item;
        total += parseFloat(price);
  
        const row = document.createElement("tr");
  
        // name
        const nameCell = document.createElement("td");
        nameCell.textContent = name;
        row.appendChild(nameCell);
  
        // price
        const priceCell = document.createElement("td");
        priceCell.textContent = price;
        row.appendChild(priceCell);
  
        // remove
        const removeCell = document.createElement("td");
        const removeBtn = document.createElement("button");
        removeBtn.textContent = "Remove";
        removeBtn.addEventListener("click", () => {
          removeCartItem(index);
        });
        removeCell.appendChild(removeBtn);
        row.appendChild(removeCell);
  
        cartTableBody.appendChild(row);
      });
  
      cartTotalEl.textContent = `Total: $${total.toFixed(2)}`;
    }
  
    function removeCartItem(index) {
      let cart = JSON.parse(localStorage.getItem("ElectroMart_Cart")) || [];
      cart.splice(index, 1);
      localStorage.setItem("ElectroMart_Cart", JSON.stringify(cart));
      displayCartItems();
    }
  
    // PAY button
    if (payBtn) {
      payBtn.addEventListener("click", () => {
        let loggedInUser = localStorage.getItem("ElectroMart_LoggedInUser");
        if (!loggedInUser) {
          paymentMessage.textContent = "Please log in before paying.";
          return;
        }
        let cart = JSON.parse(localStorage.getItem("ElectroMart_Cart")) || [];
        if (cart.length === 0) {
          paymentMessage.textContent = "Your cart is empty.";
          return;
        }
        // Show the credit card form
        creditCardForm.style.display = "block";
      });
    }
  
    // Confirm Payment button inside the credit card form
    if (confirmPaymentBtn) {
      confirmPaymentBtn.addEventListener("click", () => {
        let cardName = document.getElementById("cardName").value;
        let cardNumber = document.getElementById("cardNumber").value;
        let cardExp = document.getElementById("cardExp").value;
        let cardCVC = document.getElementById("cardCVC").value;
  
        // Basic validation
        if (!cardName || !cardNumber || !cardExp || !cardCVC) {
          alert("Please fill in all credit card fields.");
          return;
        }
  
        // Finalize purchase
        let loggedInUser = localStorage.getItem("ElectroMart_LoggedInUser");
        let cart = JSON.parse(localStorage.getItem("ElectroMart_Cart")) || [];
        let allPurchases = JSON.parse(localStorage.getItem("ElectroMart_Purchases")) || [];
        let purchaseDate = new Date().toLocaleString();
  
        cart.forEach((item) => {
          allPurchases.push({
            user: loggedInUser,
            name: item.name,
            price: item.price,
            date: purchaseDate,
          });
        });
        localStorage.setItem("ElectroMart_Purchases", JSON.stringify(allPurchases));
        localStorage.removeItem("ElectroMart_Cart");
  
        // Clear cart display
        displayCartItems();
        cartTotalEl.textContent = "Total: $0.00";
        paymentMessage.style.color = "green";
        paymentMessage.textContent = "Payment successful! Thank you for your purchase.";
        creditCardForm.style.display = "none";
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
        let users = JSON.parse(localStorage.getItem("ElectroMart_Users")) || [];
        // Check if email already exists
        let existingUser = users.find(u => u.email === newEmail);
        if (existingUser) {
          signUpMessage.textContent = "Email is already taken. Please log in or use another email.";
          signUpMessage.style.color = "red";
          return;
        }
  
        // Save new user
        users.push({ email: newEmail, password: newPassword });
        localStorage.setItem("ElectroMart_Users", JSON.stringify(users));
  
        signUpMessage.textContent = "Account created! You can now log in.";
        signUpMessage.style.color = "green";
  
        // Optionally auto-login
        // localStorage.setItem("ElectroMart_LoggedInUser", newEmail);
        // window.location.href = "profile.html";
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
      let currentUser = localStorage.getItem("ElectroMart_LoggedInUser");
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
        let users = JSON.parse(localStorage.getItem("ElectroMart_Users")) || [];
        let foundUser = users.find(u => u.email === email && u.password === pass);
  
        // Alternatively, hardcode admin:
        // if (email === "bmesa@gmail.com") { foundUser = {email: "bmesa@gmail.com", password: pass}; }
  
        if (foundUser || email === "bmesa@gmail.com") {
          // Log in success
          localStorage.setItem("ElectroMart_LoggedInUser", email);
          showUserProfile(email);
        } else {
          alert("Incorrect email or password.");
        }
      });
  
      // Logout
      logoutBtn.addEventListener("click", () => {
        localStorage.removeItem("ElectroMart_LoggedInUser");
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
    function displayPastPurchases(currentUserEmail) {
      if (!purchasesTableBody) return;
      let allPurchases = JSON.parse(localStorage.getItem("ElectroMart_Purchases")) || [];
      purchasesTableBody.innerHTML = "";
  
      // Admin = sees all, else sees only their own
      let isAdmin = (currentUserEmail === "bmesa@gmail.com");
      let filteredPurchases = isAdmin
        ? allPurchases
        : allPurchases.filter(p => p.user === currentUserEmail);
  
      filteredPurchases.forEach((p) => {
        let row = document.createElement("tr");
  
        let userCell = document.createElement("td");
        userCell.textContent = p.user;
        row.appendChild(userCell);
  
        let nameCell = document.createElement("td");
        nameCell.textContent = p.name;
        row.appendChild(nameCell);
  
        let priceCell = document.createElement("td");
        priceCell.textContent = p.price;
        row.appendChild(priceCell);
  
        let dateCell = document.createElement("td");
        dateCell.textContent = p.date;
        row.appendChild(dateCell);
  
        purchasesTableBody.appendChild(row);
      });
    }
  });
  