# Pause progress

## Current status

- Done: Steps 1–6.
- Done: Step 8 backend payment flow, trusted-contact actions, and PayPal test-route restriction.
- Done: Step 9 optional Vultr analyst.
- Done: User-centred platform foundation (registration/login, private dashboard data, notifications, and frontend integration).
- Not implemented: verified PayPal webhooks, external trusted-contact delivery, README/license/demo video.
- Latest full test run: `mvn -q test` from `backend` on 2026-11-13; 58 tests, 0 failures, 0 errors, 0 skipped.
- The full suite uses automated tests and mocks; it does not make live PayPal payments. A separate manual Vultr check was previously reported working, including an app response with `aiUsed: true`.

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
- Temporary PayPal side-effect test endpoints are in `PayPalTestController` and are enabled only with the explicit `paypal-test` Spring profile. The default application profile returns 404 for all five test routes: `/paypal/test`, `/paypal/orders/new`, `/paypal/orders/{id}/authorize`, `/paypal/authorizations/{id}/capture`, and `/paypal/authorizations/{id}/void`.
- `PayPalTestControllerAvailabilityTest` verifies those routes are unavailable by default and that `/paypal/return` and `/paypal/cancel` remain mapped. It mocks `PayPalClient` so the test cannot contact PayPal.

## Step 9: Optional Vultr AI analyst

### Purpose and design

- Step 9 adds an optional language-model analyst after the deterministic rules and investigation tools have run. It is an analyst that reviews supplied facts, not the authority that releases a payment.
- `LlmClient` is a small interface with `complete(systemPrompt, userPrompt)`. This keeps `MikasaAnalyst` independent of the provider and lets tests supply a fake client.
- `VultrLlmClient` is the single provider implementation. It uses Spring `RestClient` to POST to `{llm.base-url}/chat/completions`, sends the configured model, system/user messages, `max_tokens: 600`, and `temperature: 0.2`, and reads `choices[0].message.content`.
- The HTTP connect and read timeouts are 10 seconds. The client does not retry. It sends the API key as a Bearer header and does not enable provider-specific JSON mode.
- `llm.api-key`, `llm.model`, and `llm.base-url` are injected from Spring properties. The normal properties file optionally imports `application-local.properties`; the local file is ignored and must not be committed. The API key, model, or URL are not hardcoded in Java. If the API key is blank, the analyst is disabled. The client also fails safely if any required provider setting is absent.

### Prompt and data boundaries

- The analyst is called by `CheckpointService` after `RuleEngine` and `InvestigationService` have produced their results.
- The prompt contains only payment facts and checkpoint findings needed for analysis: payee, amount, supplied hour, rule score/decision/signals, and investigation points/findings. It does not include payment-record IDs, PayPal order IDs, authorization IDs, or trusted-contact tokens.
- The pasted text is escaped and placed inside `<untrusted_message>` tags. The system prompt says the tagged text is data, must not be followed as instructions, and asks for JSON only.
- The prompt requests `tactics`, `explanation`, `questions` (up to three), and `suggestedDecision` (`ALLOW`, `HOLD`, or `BLOCK`). The pasted message is still sent to the configured external Vultr service, so it should not contain information the user does not want sent to that provider.

### Response validation and fallback

- `MikasaAnalyst` strips an outer Markdown code fence if present, parses JSON, requires the expected fields and types, validates the decision enum, rejects more than three questions, and rejects a blank or 600-character-and-longer explanation. Unknown JSON fields are ignored.
- Model-provided tactics, explanation, and questions are sanitized by removing HTML tags and control characters before they are returned. The raw model response is not returned to the user.
- Missing API key, blank pasted message, timeout, provider error (including throttling), empty/filtered content, malformed JSON, or any other analyst failure produces an unavailable result. The checkpoint then uses its existing rule/tool decision and templated explanation, with `aiUsed: false`. A blank message skips the LLM call entirely.
- `CheckpointResult` includes `tactics`, `explanation`, `questions`, and `aiUsed`. Without a valid analyst result, explanation remains the existing personalized checkpoint message, lists are empty, and `aiUsed` is false. With a valid result, the sanitized analyst fields are included and `aiUsed` is true.
- The final decision is the strictest of the existing rule/combined-score/standing-order decision and the analyst suggestion, using the `Decision` severity order. The analyst can raise severity but cannot lower it; for example, an AI `ALLOW` suggestion cannot undo a rules `BLOCK`.
- Provider exceptions are replaced with a generic unavailable error. Prompts, API keys, raw responses, and exception details are not intentionally logged or returned.

### Step 9 tests and observed behavior

- `MikasaAnalystTest` uses fake `LlmClient` implementations. It covers valid JSON and sanitization, invalid JSON fallback, a four-question rejection, a throwing client, blank-message avoidance, missing-key disablement, untrusted closing-tag escaping, and an AI `ALLOW` suggestion that cannot lower a rules `BLOCK`.
- `CheckpointServiceTest` exercises checkpoint behavior through Spring MockMvc with the LLM API key disabled; it does not call Vultr.
- A live Vultr/API check is not part of the automated suite. The user previously reported a successful manual app response with `aiUsed: true`; this is separate from the mocked tests and from any PayPal Sandbox payment.

## Project decisions

- Java 21, Spring Boot 4.1.2, Maven, H2, package `com.pause.backend`.
- PayPal Sandbox only; rules and standing orders remain stricter than any AI decision.
- Vultr analysis is optional and advisory; deterministic checks always remain in control of the final severity.
- Do not change `pom.xml` starter names; the Spring Boot 4 starter names are intentional.
- Keep secrets in local ignored configuration or environment variables. Never read, print, or commit local secrets.
- New accounts receive conservative demo limits (200 per payment, 500 weekly, new payees require approval); the seeded Rose account has no password and is not a login account.

## User-centred platform work

### Accounts and access control

- Registration is `POST /api/auth/register`; login is `POST /api/auth/login`; the signed-in profile is `GET /api/auth/me`; logout is `POST /api/auth/logout`.
- Passwords are stored as PBKDF2 hashes. The browser uses a server-side Spring Security session, and login rotates the session ID. State-changing requests require a session CSRF token.
- Account-specific payment initiation, history, payee count, and notifications resolve the signed-in email to that account's database ID. They do not use the former globally shared user ID.
- The old Rose demo account retains its seeded payment history for the demo but has no password hash, so it cannot be logged into as a shared default account. New users start with their own records and no fabricated history.
- Anonymous payment/dashboard APIs return 401. The public PayPal return/cancel callbacks and trusted-contact capability-token actions remain public by design; the one-time token is the authority for the trusted-contact action.

### Payment activity and notifications

- The dashboard reads its profile from `/api/auth/me`, payment activity from `/api/payments`, and notifications from `/api/notifications`.
- Payment activity uses a safe DTO rather than serializing PayPal order/authorization identifiers or trusted-contact tokens.
- Real payment initiation creates a deduplicated assessment notification; BLOCK creates a blocked notification. Verified cancellation, capture, hold, and trusted-contact approve/block outcomes create notifications from their actual application events.
- Notifications are scoped by user ID, include timestamps and read state, and can be marked read with `POST /api/notifications/{id}/read`. A database unique event key prevents duplicate notifications for the same payment event.
- The database currently uses the existing in-memory H2 setup with `ddl-auto=create-drop`. Notification rows are persisted while that database is running, but are lost when the application restarts; production-style durable storage is not configured.

### Frontend integration

- The existing static HTML/CSS/vanilla JavaScript is served at `/`. It now includes account registration/sign-in, logout, account limits, payment activity, pending-review count, notifications/read state, and the existing checkpoint form and decision details.
- The browser uses relative API paths, session cookies, and CSRF headers. Backend-provided text is inserted with `textContent` or DOM nodes rather than untrusted HTML.
- Empty activity/notification states are based on API results; the UI does not fabricate sample notifications or transaction history. The preset buttons only fill the payment form.
- HOLD messaging explicitly says authorization is not capture and no trusted contact has been notified. ALLOW/HOLD buyer approval links remain PayPal Sandbox links; BLOCK has no approval action.

### PayPal external event limitations

- No webhook endpoint was added because the project does not have a registered PayPal webhook ID, a deployed public HTTPS listener, or a configured event subscription. The current localhost callback is a browser redirect for payments initiated by this application, not a general feed of external PayPal payments.
- Relevant Checkout/authorization lifecycle event families include `CHECKOUT.ORDER.APPROVED`, `PAYMENT.AUTHORIZATION.CREATED`, and capture pending/completed/denied events. PayPal requires webhook signature verification against the app's registered webhook configuration; an unverified payload must not drive payment state changes.
- Consequently, Pause currently processes only its own authenticated payment-initiation flow and its verified PayPal API/callback lifecycle. It does not detect or claim to assess incoming payments initiated elsewhere in PayPal.
- No trusted-contact email/SMS/push delivery is configured. The backend can validate a one-time trusted-contact token, but the project does not automatically deliver a link to the named contact.

### Remaining submission work and limitations

- Add a README with setup/run/test/API instructions, an MIT license if that is the intended license, and a sub-three-minute demo video.
- Configure durable storage before treating notification/activity history as long-lived user data. H2 create-drop is suitable only for this demo.
- The app has no password recovery, email verification, rate limiting, account deletion, or production deployment hardening; authentication is a hackathon foundation, not a production identity service.
- No live PayPal payment should be inferred from automated test results; payment-flow tests mock `PayPalClient`. The frontend's visual states should be manually exercised without initiating unnecessary Sandbox payments.
