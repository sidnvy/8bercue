# Card and phone compatibility

Compatibility is a combination of **app version, phone/ROM, card and operation**.
The APK installs on ARM64 Android 9+; on-device wireless setup requires Android
12+ and Wi-Fi. Installation and ADB access alone do not establish card access.

## Card families

| Card family | Current information | What still needs documenting |
| --- | --- | --- |
| 5ber | Original project target; used for card/profile management during development | Coverage still depends on phone, ROM, firmware and operation |
| 9eSIM | Project owner reports successful use | Exact card model/firmware, phone/ROM, app version and exercised operations |
| 海豚卡 | Project owner reports successful use | Exact product identity/model, firmware, phone/ROM, app version and exercised operations |
| Other removable eUICCs | No vendor allowlist; standard management access may work | Submit a read-only result before making a support claim |

The two additional owner reports are not a claim that every product variant or
operation was tested. Contribute precise results with the
[device report template](../.github/ISSUE_TEMPLATE/device-compatibility.yml).
Built-in eUICCs may also be visible; always select the intended card.

## Phone results

| Phone / ROM | App version | Reported scope |
| --- | --- | --- |
| Pixel 10 Pro XL / Android 17 | 0.20.0-beta.2 / 50 | Production-signed upgrade, on-device notification pairing, three app restart/reconnect/profile-read cycles, English/Chinese navigation; no writes or network-service tests in this release acceptance |

Add your phone/ROM as a small scoped row after the device check. Include the
backend when verified, and distinguish read-only results from each tested write.
A new row records a useful combination; it does not add a model gate to the app.

## Android transports

| Backend | Discovery | Current operation boundary |
| --- | --- | --- |
| Secure Element AIDL HAL | Registered instances and trusted system interface classes | Management paths where the ROM permits Shell access and card checks pass |
| Secure Element HIDL HAL | Declared VINTF readers and usable interfaces | Read-only |
| Telephony | Implemented physical-slot/port Binder API and real Shell identity | Experimental; operation-scoped management, dependent on ROM policy |
| OMAPI | Framework readers and channels | Retains system/card access rules; operations depend on access and admission |

There is no phone-brand or Android-16 allowlist. A ROM update can change interface
availability or authorization. A declared service may be inaccessible; an API
failure does not always distinguish an absent service from denied lookup.

## Check your combination

1. Connect the companion through wireless or USB ADB.
2. Explicitly select the intended card when several are readable.
3. Confirm a fresh profile list and verify that it belongs to that card.
4. For a device report, note the backend under **Settings → Help & feedback →
   Connection details**. Card discovery also runs when you use **Refresh**.

Discovery and the read-only check do not install, switch or delete profiles.
Failed or uncertain channel cleanup stops fallback. HIDL read success does not
permit writes. A valid profile inventory does not prove operator activation,
network registration or mobile data.

See [Check a new phone](NEW_DEVICE_CHECK.md) for reporting results and
[Adapters](ADAPTERS.md) for adding support. Release-specific validation scope is
summarized in the [release notes](https://github.com/sidnvy/8bercue/releases);
per-session test diaries are not a compatibility database.
