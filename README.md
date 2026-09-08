# ElectroMart

ElectroMart is a prototype e-commerce platform demonstrating core functionalities required for online shopping
experiences: user authentication, product browsing, a shopping cart, a checkout/payment interface, purchase
history, categorized browsing and responsive navigation.

The trusted parts of the prototype (catalog, prices, stock, checkout and orders) now run in a **Java 17 /
Spring Boot 3.3.5** backend. The frontend is still the same plain HTML/CSS/JavaScript (no framework) and is
served by that backend from the same origin, so the design and the page flow are unchanged.

---

## Requirements

* Java 17 (JDK)
* Maven 3.8+

## Build, test and run

```bash
# build (compiles the backend and packages the frontend into the jar)
mvn package

# run the tests only
mvn test

# run the complete application (backend + frontend on http://localhost:8080)
java -jar target/electromart.jar

# ... or during development
mvn spring-boot:run
```

Then open <http://localhost:8080/> (or <http://localhost:8080/index.html>).
Do **not** open the `.html` files directly from disk any more: the pages need the API of the running server.

### Configuration

| Environment variable    | Default                        | Meaning                                   |
|-------------------------|--------------------------------|-------------------------------------------|
| `PORT`                  | `8080`                         | HTTP port of the server                   |
| `ELECTROMART_DATA_FILE` | `data/electromart-state.json`  | Single JSON file with the persisted state |

```bash
PORT=9090 ELECTROMART_DATA_FILE=/var/lib/electromart/state.json java -jar target/electromart.jar
```

The state file holds the **inventory**, the **completed orders** and the **idempotency records**, so all of them
survive a restart. When the file does not exist it is created from the catalog. Successful changes are written
to a temporary file in the same directory and then atomically renamed, so an interrupted write can never leave a
partially written state behind. If an existing file is invalid the server **fails to start with an explicit
error** instead of silently replacing it.

---

## API

All endpoints are served from the same origin as the pages, under `/api`.

### `GET /api/products`

Returns the whole catalog (one entry per distinct product of the home, search and category pages):

```json
[ { "id": "smartwatch", "name": "Smartwatch", "priceCents": 15000, "stock": 40 } ]
```

### `POST /api/checkout`

Headers: `Content-Type: application/json`, `Idempotency-Key: <key>` (**required**).

```json
{ "user": "buyer@example.com",
  "items": [ { "productId": "smartwatch", "quantity": 2 } ] }
```

The server merges repeated product ids, validates the complete request, and calculates every price, line total
and the order total **from its own catalog** - prices sent by the browser are ignored. Creating the order and
decrementing the inventory happen in one transaction, so simultaneous requests cannot oversell a product.

| Situation                                            | Response |
|------------------------------------------------------|----------|
| Order created                                        | `201` with the order |
| Same key **and** same request repeated               | `200` with the original order (no stock taken again) |
| Missing key, unknown product, invalid quantity/user  | `400` with `{ "error": "invalid_request", "message": ..., "details": [...] }` |
| Not enough stock                                     | `409` with `{ "error": "insufficient_stock", ... }` |
| Key reused for a different user or cart              | `409` with `{ "error": "idempotency_key_reuse", ... }` |

Failed requests never change the inventory and never create a partial order.

An order looks like this:

```json
{ "id": "ord_2f5b...", "user": "buyer@example.com", "createdAt": "2025-01-31T18:24:05.412Z",
  "items": [ { "productId": "smartwatch", "name": "Smartwatch",
               "quantity": 2, "unitPriceCents": 15000, "lineTotalCents": 30000 } ],
  "totalCents": 30000 }
```

### `GET /api/orders?user=<email>`

Returns the orders of that user. To preserve the behaviour of the prototype, `bmesa@gmail.com` receives **all**
orders. A missing `user` parameter returns `400`.

---

## Frontend notes

* Every `.add-to-cart-btn` now carries `data-product-id` (and `data-product-quantity`); no price is read from
  the page any more. `ElectroMart_Cart` stores only `{ productId, quantity }`.
* **Legacy carts** that contain `{ name, price }` entries are migrated automatically: products are matched by
  name, the saved price is ignored and repeated products are combined into one quantity. An entry that matches
  no product is shown as *unavailable*, can be removed, and is never sent to the checkout.
* The credit card fields in `shoppingCart.html` are still validated in the browser, but their values are never
  sent to the backend and never stored anywhere.
* `confirmPaymentBtn` is disabled while a checkout is pending; retrying the same cart after a network failure
  reuses the same `Idempotency-Key` (so no second order can be created), and `ElectroMart_Cart` is only cleared
  after a successful response. Backend and network errors are shown in `paymentMessage`.
* `displayPastPurchases()` reads the orders from `GET /api/orders`; `ElectroMart_Purchases` is no longer read
  or written.
* Sign up, login, logout, navigation and the responsive layout are unchanged (accounts still live in
  `localStorage`, exactly as in the prototype).

Duplicated products of the prototype were unified into a single catalog entry, and the pages show the catalog
price: *MSI Gaming Laptop* ($1000, the page's `data-product-price="300"` was exactly the kind of client side
price the backend now ignores) and *Soundbar* ($300, the price of the speakers category page).

---

## Project layout

```
pom.xml                     Maven build (Java 17, Spring Boot 3.3.5), packages the frontend into the jar
index.html, search.html,    the existing frontend, served from the same origin
category-*.html,
shoppingCart.html,
profile.html, signUp.html,
app.js, style.css, Pictures/
src/main/resources/
  catalog.json              the single server side catalog
  application.properties    PORT / ELECTROMART_DATA_FILE configuration
src/main/java/com/electromart/
  api/                      REST controllers, request/response DTOs, error handling
  service/                  catalog, checkout rules, orders (business logic)
  persistence/              JSON state file, atomic writes, transactional state repository
  domain/                   Product, Order, OrderLine, ...
  config/                   properties and bean wiring
src/test/java/com/electromart/
  service/                  validation, price calculation, idempotency, stock conflicts, concurrency
  persistence/              state file initialization, corruption handling, atomic write, restart
  api/                      HTTP status codes and payloads, static frontend
  ApplicationRestartTest     starts the whole application twice on the same state file
```

## Tests

`mvn test` runs the automated tests covering checkout validation, price calculation from the catalog,
idempotent retries and key reuse, inventory conflicts, concurrent checkouts (no overselling), and persistence
across a restart (including refusing to start on a corrupted state file).

---

## License

This project is available under the MIT License.
