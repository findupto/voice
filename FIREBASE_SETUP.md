# Firebase multi-device setup (Voice POS)

## Current state
The Android app is still local-first. This repository now generates device-scoped order IDs and ignores duplicate local pending-order and sale writes. These changes are a foundation for safe synchronization; they do **not** turn on cloud synchronization by themselves.

## Create the Firebase project
1. Open https://console.firebase.google.com/ and create a project for the restaurant.
2. Add an Android app with package name `com.findupto.voicepos`.
3. Enable **Authentication → Email/Password**.
4. Create a **Cloud Firestore** database in production mode.
5. Publish the repository's `firestore.rules` ruleset in Firestore → Rules.
6. Download the Android `google-services.json` for the app.

## Important
- Do not commit `google-services.json`, service-account keys, passwords, or API secrets to a public repository.
- Send the downloaded `google-services.json` to the developer privately / attach it in this ChatGPT conversation so Firebase can be wired into the Android build.
- Firestore security rules are mandatory. Do not use public test rules such as `allow read, write: if true`.
- Each restaurant has its own `restaurants/{restaurantId}` namespace. Staff accounts must have a member document with one of: `OWNER`, `WAITER`, `CHEF`, or `COUNTER`.
- The first owner account must be provisioned during the owner setup flow. Staff must not be able to self-assign the owner role.

## Synchronization design
- Every order has a stable document ID derived from a device-specific install node and timestamp/sequence.
- Writes use the same document ID on retries, making order submission idempotent.
- Sales and payments are separate immutable/controlled records; the sales dashboard aggregates unique sale documents, never per-device totals.
- Kitchen updates are limited to kitchen status and notes by the rules.
- The Owner role controls menu, staff, settings, backup, and restore.
- Offline writes must queue locally and upload with the original document IDs when connectivity returns.
- Cloud backup should be a versioned owner-only snapshot; restore must require an explicit confirmation and retain a restore audit record.

## Device roles
- Owner: full access, staff management, settings, reports, backup/restore.
- Waiter: create orders and view their progress.
- Chef: view orders and update kitchen status only.
- Counter: create orders, finalize bills, receive payments, and view sales.

## Build status
Firebase SDK integration, account onboarding, Firestore listeners/writes, role-aware UI, offline outbox, and scheduled cloud backups still need the project configuration file before they can be enabled and tested end-to-end.
