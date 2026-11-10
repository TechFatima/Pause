# Pause progress

## Current status

- Done: Steps 1–6.
- Done: Step 8 backend payment flow and safety checks.
- Not started: Step 9 (LLM agent), Step 10 (frontend), Step 11 (README, license, demo video).
- Last full test run: 44 tests, 0 failures, 0 errors, 0 skipped.

## Step 8: PayPal payment checkpoint

### Initiating a payment

- `POST /api/payments/initiate` accepts the checkpoint request fields and runs the existing checkpoint rules.
- A `BLOCK` decision saves the payment record as `BLOCKED`, creates no PayPal order, and returns no approval URL.
- `ALLOW` and `HOLD` create a PayPal Sandbox order with `intent=AUTHORIZE`. The amount is read from the saved payment record, then the PayPal order ID is saved to that record.
- PayPal errors returned by the backend use safe, generic messages; raw PayPal response bodies are not returned.

### PayPal return, authorization, and capture

- `GET /paypal/return?token=ORDER_ID` only acts on an order ID linked to a saved payment record. Unknown IDs return 404.
- Return handling locks the payment record and ignores duplicate callbacks after the record leaves its waiting state.
- An authorization is not treated as successful based on the authorize response alone. The backend reads the PayPal order and confirms the matching authorization is active before continuing.
- For `ALLOW`, the backend captures the verified authorization and marks the record `COMPLETED` only when PayPal explicitly reports capture completion.
- For `HOLD`, the backend does not capture. It records `HELD` only after confirming an active, uncaptured authorization.
- Capture errors do not trigger an automatic retry, preventing duplicate captures. Unconfirmed or malformed PayPal state remains uncertain instead of being treated as success.

### Authorization recovery and cancellation

- If authorization fails, the backend reads the PayPal order and authorization state before deciding whether the payment can be recovered.
- An `AUTH_FAILED` retry checks PayPal first. It reuses a verified existing authorization, attempts authorization only when the order is approved and no authorization exists, and does not retry while PayPal state is unknown.
- `GET /paypal/cancel?token=ORDER_ID` reports cancellation only after PayPal confirms there is no active authorization or confirms a void.
- If an authorization exists, cancellation attempts to void it and then verifies the PayPal state. If state is unreachable or voiding is unconfirmed, the record stays uncertain and the response does not claim cancellation.
- Return and cancel callbacks share a database row lock to prevent concurrent processing races.

### Trusted-contact approve/block actions

- A `HOLD` creates a 32-byte `SecureRandom` token encoded as URL-safe Base64 and an expiry 24 hours later. Token fields are hidden from JSON responses.
- `POST /api/trusted-contact/approve` and `POST /api/trusted-contact/block` accept `{ "token": "..." }` in the request body.
- Each action checks that the token exists, is unexpired, and belongs to a payment still in `HELD` state. A database lock and token removal prevent reuse and competing decisions.
- Approval confirms the authorization is still active and uncaptured before capture. Blocking confirms the same state before voiding and records `BLOCKED` only after PayPal confirms the void.
- If PayPal state cannot be checked before an action, no capture or void is attempted and the held token remains available for a later retry. If the outcome of a capture/void call is uncertain, the token is consumed and the record is marked `CONTACT_ACTION_UNCERTAIN` to prevent a duplicate action.
- No trusted-contact email, phone number, or messaging integration exists yet. The backend does not send a summary or deliver the token/link; a contact must receive the token through a separately arranged secure channel to call these endpoints.

### PayPal client and errors

- PayPal calls use the Sandbox API. OAuth credentials and the callback base URL are supplied through the existing configuration mechanism; credentials are not stored in source code.
- Order inspection reads the order, authorization, and capture states. Unparseable payment details are treated as unknown.
- PayPal errors and unexpected controller errors return safe messages. Credentials, access tokens, Authorization headers, raw PayPal error bodies, and trusted-contact tokens are not intentionally logged or returned.
- Temporary `/paypal/*` Sandbox test endpoints remain available for manual flow checks. Do not use them for production payments.

## Project decisions

- Java 21, Spring Boot 4.1.2, Maven, H2, package `com.pause.backend`.
- PayPal Sandbox only; rules and standing orders remain stricter than any AI decision.
- Do not change `pom.xml` starter names; the Spring Boot 4 starter names are intentional.
- Keep secrets in local ignored configuration or environment variables. Never read, print, or commit local secrets.
