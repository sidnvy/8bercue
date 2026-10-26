# Card and phone compatibility

Compatibility is a combination of **app version, phone/ROM, card and operation**.
The APK installs on ARM64 Android 9+; on-device wireless setup requires Android
12+ and Wi-Fi. Installation, companion connection, card reading, profile writes
and mobile network service are separate results.

The current published version is [0.20.0-beta.7 / APK55](https://github.com/sidnvy/8bercue/releases/tag/v0.20.0-beta.7).
A row below describes its stated version and scope; it is not a guarantee for
another ROM update, card revision or operation.

## Card families

| Card family | What is known | Limits of the information |
| --- | --- | --- |
| 5ber | Original project target, with card/profile management used during development | Results depend on the phone, ROM, card firmware and operation |
| Nekoko | Profile reads and disposable-profile enable/disable passed in the APK55 combination below | Exact product revision/firmware is unknown; no brand-wide guarantee |
| 9eSIM | Earlier successful use reported by the project owner; a later read-only trial returned empty inventories | Whether those trial cards already held profiles was unknown, so an empty list proves neither complete compatibility nor incompatibility |
| 海豚卡 | Successful use reported by the project owner | Exact product/model, firmware, phone/ROM, app version and tested operations still need a scoped report |
| Other removable eUICCs | No vendor allowlist; standard management access may work | Start with a read-only result and identify the product if known |

Do not infer a card's firmware or capabilities from its printed brand. Built-in
eUICCs may also be visible; explicitly select the intended card. The current
manager supports a single enabled profile per card. A phone's built-in eSIM MEP
support does not establish dual-profile support for its removable slot or card.

## Scoped phone results

| Phone / ROM | Card | App version | Verified scope | Outside that scope |
| --- | --- | --- | --- | --- |
| Pixel 10 Pro XL / Android 17 | Removable eUICC used in that acceptance | 0.20.0-beta.2 / 50 | Signed upgrade, on-device notification pairing, three app restart/reconnect/profile-read cycles, English/Chinese navigation | Writes and network service; this is a historical result, not an APK55 qualification |
| Motorola edge 70 / Android 17 | Two Nekoko cards; product revision unknown | 0.20.0-beta.7 / 55 | Signed upgrade with data retained; saved-identity wireless reconnect; computer ADB command and normal Shizuku permission/connection; reads of both cards; automatic confirmation of an earlier uncertain enable; four verified disposable-profile enable/disable operations with automatic wireless/Shizuku reconnect; other card unchanged | Physical USB transport, fresh pairing, new downloads, rename/delete and mobile network service on APK55 |

The computer command in the APK55 row was exercised over the existing computer
TCP ADB transport; it was not a new physical USB-cable test. The test profile's
enabled card state does not establish network registration, calls or data.
Detailed session evidence is kept internally, outside the source tree.

Contribute a compact row after the device check. Record the backend when known;
write **unknown** rather than guessing. A new row documents a combination and
does not add a phone-model gate to the app.

## Android transports

| Backend | Discovery | Current operation boundary |
| --- | --- | --- |
| Secure Element AIDL HAL | Registered instances and trusted system interface classes | Management paths where the ROM permits Shell access and card checks pass |
| Secure Element HIDL HAL | Declared VINTF readers and usable interfaces | Read-only |
| Telephony | Implemented physical-slot/port Binder API and real Shell identity | Experimental; operation-scoped management, dependent on ROM policy |
| OMAPI | Framework readers and channels | Retains system/card access rules; operations depend on access and admission |

The connection method (wireless ADB, computer ADB or Shizuku) and card backend
are different choices: all connection methods reach the same local Shell
companion, which discovers the available card interfaces. Choosing another
connection method does not grant a HAL that the ROM denies.

There is no phone-brand or Android-16 allowlist. A ROM update can change interface
availability or authorization. A declared service may be inaccessible; an API
failure does not always distinguish an absent service from denied lookup.

## Check your combination

1. Use one of the [connection methods](ADB_CONNECTION.md).
2. Explicitly select the intended card when several are readable.
3. Confirm a fresh profile list and verify that it belongs to that card.
4. For a device report, note the backend under **Settings → Help & feedback →
   Connection details**. Use **Refresh** to repeat the read; no separate
   compatibility test is required.

Discovery and the read-only check do not install, switch or delete profiles.
Failed or uncertain channel cleanup stops fallback. HIDL read success does not
permit writes. An empty inventory can be valid; if its expected contents are
unknown, say so in the report.

See [Check a new phone](NEW_DEVICE_CHECK.md), the
[device report template](../.github/ISSUE_TEMPLATE/device-compatibility.yml), and
[Adapters](ADAPTERS.md). Release-specific scope belongs in the
[release notes](https://github.com/sidnvy/8bercue/releases); per-session diaries
are not a compatibility database.
