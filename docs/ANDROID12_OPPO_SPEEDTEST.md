# Android 12 Telephony Speedtest cycle

Target: OPPO handset, Android 12 / API 31, ColorOS. A removable 5ber eUICC was in physical slot 0. No other phone was operated. This record does not inherit the Xiaomi Android 13 or Redmi Android 16 cycles.

The owner authorized the public Truphone Speedtest entry (`rsp.truphone.com`, matching id `QRF-SPEEDTEST`) and then its enable. The card baseline before installation was empty. No private activation code, purchase, or account signup was used.

The installed build was an unsigned local `0.19.1-beta.3` / versionCode 48 candidate, not an officially signed release. Discovery selected **Telephony / `telephony.0.0`**. HIDL SIM1, SIM2, and eSE1 lookups were unreachable and were not the selected route. The AOSP test SM-DP+ `prod.smdp-plus.rsp.goog` stopped at TLS during `INITIATE_AUTHENTICATION` and did not change the card; that server uses a test certificate this build does not trust.

## Normal UI cycle (22 October 2026)

| Confirmed UI action | Observed result |
| --- | --- |
| Install Speedtest once | Verified. One new disabled profile, name Speedtest Travel. HTTP 200 / `Executed-Success`, phase `NATIVE_EXIT`, worker and native stopped. Operator notification delivered. |
| Enable Speedtest Travel | Verified at 16:10:27. Journal `operation=enable`, `status=verified`. Following read: `telephony.0.0`, count 1. Home showed the profile enabled. |

## Signal after enable

Android subscription for slot 0 was Speedtest Travel. Its home network was MCC 204 / MNC 04. The visited network was China Mobile, roaming. Voice was `IN_SERVICE` on LTE (channel present, 20 MHz). Data registration was also `IN_SERVICE`, with data radio technology `IWLAN`. Slot state stayed `LOADED,ABSENT`.

Card enable and this registration are separate. The app does not claim `networkRegistrationChecked`.

## Boundaries

The profile is still enabled on this card. It has not been disabled or deleted. No rename cycle was run. This local build is not a release qualification for other phones or for the officially signed APK.
