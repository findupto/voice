# Safe three-device POS failover

## Current behavior

- The Owner phone is the only supported primary server.
- Staff phones pair to the Owner address and authenticate against the Owner's staff directory.
- The current background worker retries orders and delivery updates against the configured Owner address.
- The QR code contains an address only; it is not a device identity or a replication credential.
- A standby phone does not currently have a replicated staff directory, authoritative event log, or a safe leader-election lease.

Automatic promotion is intentionally disabled. A phone that merely cannot reach the Owner cannot distinguish a failed Owner from a Wi-Fi partition. Promoting on timeout alone can create two writable primaries and divergent payments, stock, and orders.

## Required roles and priority

1. Owner — preferred primary.
2. Admin — first standby.
3. Kitchen Head (Chef) — second standby.

Priority is not sufficient by itself. A candidate may serve writes only after obtaining a valid, monotonically increasing leadership term under the election protocol.

## Implementation gates

### Gate 1 — Trusted device enrollment

- Pair devices using a one-time QR challenge, not a reusable server secret.
- Give each device a persistent cryptographic identity and revoke capability.
- Bind the enrolled identity to exactly one authorized role and priority.
- Require Owner confirmation for Admin and Kitchen Head enrollment.
- Authenticate every peer request and protect replication traffic with authenticated encryption.
- Never copy staff PINs in plaintext or place credentials in QR contents.

### Gate 2 — Durable replication

- Introduce an append-only, uniquely identified business event log.
- Replicate orders, order transitions, kitchen station progress, sales, payments, expenses, stock changes, dues, shifts, and audit events.
- Persist each event before acknowledging it to a client.
- Track per-peer durable sequence acknowledgements and detect gaps.
- Replicate staff account verifier material only through the authenticated enrollment channel; support account disable/revocation and session invalidation.
- Do not use whole-backup last-write-wins as the replication protocol. It can overwrite valid concurrent financial changes.

### Gate 3 — Leader election and fencing

- Use a quorum-backed lease with a monotonic term and a single authoritative lease holder.
- Every write carries the current term; reject stale-term writes at the server and in replicated storage.
- Do not promote Admin or Chef if the election cannot establish quorum or prove the previous lease expired.
- If the network is partitioned and quorum is unavailable, fail closed to read-only/offline queued operation.
- Apply role priority only among eligible, synchronized candidates.

### Gate 4 — Owner recovery and handback

- Reconcile missing event ranges and verify hashes before accepting writes on the returning Owner.
- Merge by immutable event ID and business entity version; never discard a payment or stock movement because another copy has a later wall-clock timestamp.
- Verify financial invariants and unresolved conflicts before handback.
- Transfer leadership with a new term; do not allow the returning Owner to take over merely because it has higher priority.
- Preserve a recoverable backup before applying reconciliation.

## Release acceptance tests

- Kill Owner power during an order, payment, kitchen update, and stock movement.
- Verify Admin promotion only after lease expiry and successful quorum.
- Disconnect the network into two partitions; prove that at most one partition accepts writes.
- Repeat with Admin unavailable and verify Chef is selected only when safe.
- Reconnect all devices with concurrent offline orders and verify every immutable event is retained exactly once.
- Return Owner and verify all financial totals, payments, stock movements, dues, shifts, and audit history match before handback.
- Revoke a standby device and prove it cannot authenticate, replicate, or lead.
- Reboot each device during replication and verify durable sequence recovery.

## Safety rule

Until all four gates and acceptance tests are implemented, tested on physical devices, and released together, the app must keep the Owner as the only writable primary. A manual full-backup restore is recovery assistance, not automatic failover.
