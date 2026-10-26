# Connect 8bercue

The app talks to a local companion running as Android Shell (UID 2000). Authorize
it through on-device wireless ADB, a trusted computer with USB ADB, or an
already-running Shizuku service with ADB permissions. None of these methods
grants root. Card access is checked separately after connection.

## Wireless setup on the phone

Requires Android 12+ and Wi-Fi for this app's TLS implementation.

1. From Home choose **Connection & access → This phone · Wireless ADB**,
   then **Start setup**. Allow the
   notification permission so you can enter the code while staying in Settings.
2. Open Android **Wireless debugging**, enable it if needed, then choose
   **Pair device with pairing code**.
3. Keep that code window open. Expand the 8bercue notification and choose
   **Enter pairing code**. Enter the six digits in the notification reply.
4. Return to 8bercue. Select the intended card when prompted.

Pairing remembers the app's client identity. Later, choose the wireless method
and **Connect on this phone**. Wireless debugging must remain available. Reconnect after the companion
stops; a normal background/foreground return may reuse a live session.

Keep Android Settings open until pairing finishes. Android closes its pairing
server when that page is paused, so 8bercue accepts the code through notification
reply rather than a separate input Activity. If the keyboard fails to appear,
use the computer option and investigate the device's IME/SystemUI behavior;
8bercue cannot force another app's keyboard to render.

## Computer setup

From Home or Settings, open **Connection & access → Computer · USB ADB**.
Computer and wireless are equal choices; existing wireless pairing does not
prevent using the computer method. On Android versions or builds without
on-device wireless support, the computer method remains available.

Enable USB debugging, connect to a trusted computer and accept its Android
authorization prompt. Copy the command from 8bercue and run it during the visible
two-minute window. With several phones connected, target the intended serial.
An expired window requires **Start connection again**, then the same command.

The command resolves the installed APK on the phone and starts its fixed
connection entrypoint. It downloads no separate helper and requires no Python
runtime. The terminal confirms an authenticated Binder connection or reports a
bounded failure. A successful launch attempt alone is not treated as success.

## Shizuku (optional)

If Shizuku is already running with ADB permissions on this phone, **Shizuku**
appears as a third method under **Connection & access**. Choose it and allow
8bercue in Shizuku's authorization prompt. No extra pairing or computer command
is needed for 8bercue. Having Shizuku installed without a running service is not
enough; start it normally in Shizuku first, then return to 8bercue.

This integration supports Shizuku server API 13 or later, started through ADB
(Shell UID 2000). Root-started Shizuku and Sui are not supported by this Shell
backend. The app requests Shizuku permission only after you select this method.

Shizuku launches a one-use service from the installed 8bercue APK. That service
can only start 8bercue's fixed connection entrypoint; it accepts no command,
path or card operation. The same current-App challenge, installed APK digest,
caller checks and typed management Binder used by the other methods still
apply. The launcher is removed after the handoff; existing card operation
consent, locks, journals and postflight checks do not change.

See the [official Shizuku API guide](https://github.com/RikkaApps/Shizuku-API)
for the distinction between ADB and root identities and the UserService API.

## Connection versus card access

A connected companion still needs a usable HAL, Telephony or OMAPI route.
[Compatibility](COMPATIBILITY.md) explains the differences. Multiple cards require
explicit selection; a changed/missing selected card stops access instead of
silently using another one.

Do not clear app data, remove unrelated pairings or change debugging/network
settings as a generic recovery step. Existing unknown card outcomes require
[recovery review](READ_ONLY_RECOVERY.md), not another write attempt.
