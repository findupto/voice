# Voice POS

Premium offline Android POS for restaurant operations.

## Local multi-device network (no cloud required)

One Android phone can act as the restaurant's main data server. Staff phones connect to that phone over the same existing Wi-Fi network or an already-enabled phone hotspot.

1. Connect all phones to the same local Wi-Fi/hotspot. Internet access is not required.
2. On the owner phone, open the router icon in the POS toolbar and tap Start Main Server.
3. Share the displayed local IPv4 address and access code with staff.
4. Use the owner device's random access code once to enroll the first OWNER account, then sign in as that owner.
5. The owner creates named staff accounts with individual PINs and roles. Staff connect using the owner IP address and sign in with their assigned name and PIN; synchronization then uses their staff session.
6. Open Live Orders on staff phones. The staff board polls the owner server every four seconds. Waiter order submissions and amendments are sent to the owner; chef status updates and waiter served acknowledgements are recorded on the owner device. Counter checkout can send the completed sale and receipt data back to the owner.

The app does not toggle Wi-Fi, switch networks, enable mobile data, or start/stop a hotspot. The owner phone must remain powered on and the POS app must remain running while it hosts the server. Android may stop background apps under battery pressure.

## Local network notes

- LAN server port: TCP 8765.
- The server uses a random access code stored on the owner phone. On first setup, use this code to enroll the first OWNER account; no shared default staff PINs are created.
- The one-time owner access code is used only for first-owner enrollment. Business sync and live-order endpoints require authenticated staff sessions; full backup sync is OWNER-only.
- The /health endpoint is a basic LAN connectivity check.
- Keep the access code within the restaurant team and use a trusted private network.
- Live order updates use local-network polling (about four seconds), not push notifications. Keep the owner POS open and connected.
- Staff order alerts recover after monitor restarts: unacknowledged active orders are re-alerted if still actionable after 15 minutes, while changed order/kitchen state alerts immediately. Temporary LAN failures use bounded exponential retry backoff (up to 60 seconds) instead of retrying at a fixed rapid interval. Completed orders have their local alert markers cleared.
- The live board shows active-order age, highlights orders waiting 15 minutes or more, and gives the owner a recent activity feed.
- The server rejects invalid workflow transitions and enforces role checks for accept, prepare, ready, served, delivery and billing actions. Audit history retains up to 5,000 events.
- Bluetooth transport and automatic Wi-Fi discovery are not included yet; manual local IP entry works on the same LAN/hotspot.

## Staff account security and administration

- The first account must be enrolled as OWNER using the random access code shown on the owner device; predictable shared default PINs are not provisioned.
- Staff PINs are stored as salted PBKDF2-HMAC-SHA256 hashes. Failed sign-ins are rate-limited, and authenticated sessions expire after 30 days.
- Owner-only LAN endpoints can list staff accounts, enable or disable an account, reset a staff PIN, and sign out the current session. Disabling an account or resetting its PIN revokes its active sessions. The final active OWNER account cannot be disabled.
- LAN requests reject redirects and oversized request/response bodies. Offline orders are persisted locally, retried in small batches without blocking new order entry, and retained for WorkManager retry until the owner device accepts them; the queue has a 5,000-order safety limit.
- Android cloud/device-transfer backups exclude staff PIN hashes, active LAN tokens/sessions, and staff messages. Business data continues to use the app's own validated JSON backup and rotating local backup files.
- **Transport limitation:** the local POS server currently uses HTTP, not TLS. Use only a trusted private Wi-Fi network or phone hotspot; never expose TCP port 8765 to the public internet or forward it from a router. These controls harden the local workflow but do not provide end-to-end encryption.

## Restaurant order workflow

Orders carry table, waiter, counter, chef, status, special-instruction, update-time, and served-time fields. Supported states include NEW, ACCEPTED, PREPARING, READY, SERVED, OUT_FOR_DELIVERY, DELIVERED, and BILLED. Owner-side audit entries record order creation and staff status changes. The existing Bluetooth customer/kitchen printer support remains available on the device connected to each printer.

This is an incremental workflow foundation rather than a certified enterprise deployment. Staff accounts use individual PINs and server-side role checks; live updates currently use polling plus Android order notifications. Further hardening should include encrypted transport for untrusted networks, owner-managed account recovery and revocation, durable sync conflict resolution, item-level kitchen tickets, printer routing rules, and automated multi-device failure testing.

## Duplicate handling and sales

- New order IDs include a per-install device component, reducing collisions between staff phones.
- Repeated pending-order and sale writes with the same ID are ignored.
- Central sync merges sales, pending orders, payments, expenses, shifts, dues, and event records by identity instead of replacing the server's whole dataset.
- Financial reconciliation keeps the most recent paid sale balance, retains settled dues for sync, rebuilds due paid amounts from the merged payment ledger, and prefers closed shifts over stale open copies.
- The sales report should be read from the main device after staff devices have synchronized.

## Backup

The existing JSON business backup remains available from Settings. For a central copy, export it from the owner/server phone after synchronizing staff devices.

## Build

Android APK builds run automatically on pushes to main. Successful builds publish a downloadable APK artifact and GitHub Release.
