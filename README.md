# Voice POS

Premium offline Android POS for restaurant operations.

## Local multi-device network (no cloud required)

One Android phone can act as the restaurant's main data server. Staff phones connect to that phone over the same existing Wi-Fi network or an already-enabled phone hotspot.

1. Connect all phones to the same local Wi-Fi/hotspot. Internet access is not required.
2. On the owner phone, open the router icon in the POS toolbar and tap Start Main Server.
3. Share the displayed local IPv4 address and access code with staff.
4. On each staff phone, open the same screen, enter the owner address and code, then tap Sync With Main Device.
5. The server merges orders and business records by stable record IDs and returns the consolidated restaurant data.

The app does not toggle Wi-Fi, switch networks, enable mobile data, or start/stop a hotspot. The owner phone must remain powered on and the POS app must remain running while it hosts the server. Android may stop background apps under battery pressure.

## Local network notes

- LAN server port: TCP 8765.
- The server uses a random access code stored on the owner phone.
- The /sync endpoint requires that code.
- The /health endpoint is a basic LAN connectivity check.
- Keep the access code within the restaurant team and use a trusted private network.
- This initial LAN release uses user-triggered sync. It is not yet continuous real-time push synchronization.
- Bluetooth transport and automatic Wi-Fi discovery are not included yet; manual local IP entry works on the same LAN/hotspot.

## Duplicate handling and sales

- New order IDs include a per-install device component, reducing collisions between staff phones.
- Repeated pending-order and sale writes with the same ID are ignored.
- Central sync merges sales, pending orders, payments, expenses, shifts, dues, and event records by identity instead of replacing the server's whole dataset.
- The sales report should be read from the main device after staff devices have synchronized.

## Backup

The existing JSON business backup remains available from Settings. For a central copy, export it from the owner/server phone after synchronizing staff devices.

## Build

Android APK builds run automatically on pushes to main. Successful builds publish a downloadable APK artifact and GitHub Release.
