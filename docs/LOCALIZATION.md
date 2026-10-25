# Display languages

8bercue includes English and Simplified Chinese. **Settings → Appearance &
language → Language** offers Follow system, 简体中文 and English. Theme selection
is on the same page.

Android 13+ uses platform per-app locales. Android 9–12 uses an app-scoped
preference and configuration context. Neither changes the phone's system locale.
Both translations are packaged for offline use; language splitting is disabled.

Translate app-owned labels, fixed errors, confirmations and accessibility text.
Keep user/operator names, activation/confirmation codes, card identifiers, ADB
commands, protocol keys and exported diagnostic vocabulary unchanged. Third-party
legal notices retain their original text. Map backend messages at presentation
boundaries, without translating arbitrary data or changing protocol comparisons.

Language recreation preserves the settings section without replaying operations
or restoring stale operation previews. A normal read-only refresh may run after
recreation; it is not a card mutation.

Run `python3 android-app/tools/test_localization.py` for resource, placeholder and
owned-error coverage, plus the UI regression suite for presentation changes.
Offline resource parity does not qualify TalkBack, large fonts or an older ROM's
live locale behavior; report those observations separately.
