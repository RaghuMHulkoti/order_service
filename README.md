# Order Service

Checkout saga and order lifecycle (TDD §8.7, §12.6). Spring Boot 4 / Java 25, Oracle schema `ORDERS`, port **8086**.
Same layering as the other services: `web` -> `core/usecase` -> `core/service` -> `gateway`.

## Run

1. Create the Oracle schema once (Flyway then creates the tables): user/schema `ORDERS` on the shared `ecommerce_tp` database.
2. Start Cart (8084), Payment (8085) and Inventory, then:

```
DB_PASSWORD=... WALLET_DIR=... ./mvnw spring-boot:run
```

| Env | Default |
|---|---|
| `CART_SERVICE_URL` | `https://cart-service-csoc.onrender.com` (use `http://localhost:8084` locally) |
| `PAYMENT_SERVICE_URL` | `https://payment-service-iyeh.onrender.com` (use `http://localhost:8085` locally) |
| `INVENTORY_SERVICE_URL` | `https://inventory-manager-8q64.onrender.com` |

Swagger UI: `http://localhost:8086/swagger-ui.html`. Tests (`./mvnw test`) run on H2 in Oracle mode, no database needed.

## API (`X-User-Id` set by the gateway)

| Method | Path | |
|---|---|---|
| POST | `/api/v1/orders` | Place order from the cart. Needs `Idempotency-Key`. 202 new, 200 replay |
| POST | `/api/v1/orders/{id}/pay` | Pay with `{"paymentMethodToken": "tok_..."}`; captured -> CONFIRMED |
| POST | `/api/v1/orders/{id}/cancel` | Cancel while unpaid; releases stock |
| GET | `/api/v1/orders/{id}` | Detail (owner only) |
| GET | `/api/v1/orders?status=&cursor=&size=` | History, newest first, cursor pagination |

Payment tokens against the simulated PSP: `tok_ok` succeeds, `tok_fail*` declines, `tok_3ds*` needs customer action.

## Saga

```
POST /orders -> cart snapshot -> pick warehouse -> order CREATED -> Inventory reserve -> PAYMENT_PENDING -> Payment intent
pay / poll   -> payment CAPTURED -> Inventory deduct -> CONFIRMED (+ ORDER_CONFIRMED outbox, ordered cart lines removed)
             -> payment FAILED   -> release stock -> FAILED
cancel / 15m timeout            -> release stock -> CANCELLED
paid but stock cannot be deducted -> refund -> FAILED
```

`SettleOpenOrdersUsecase` (every 30 s) polls Payment for open orders, because Payment has no event channel to this
service yet; both services write an outbox that no relay publishes.

Not built yet: shipments, returns/refund-on-return, invoices, coupons/tax/shipping fees, address book (the shipping
address is sent inline and snapshotted), Kafka relay and consumers.
