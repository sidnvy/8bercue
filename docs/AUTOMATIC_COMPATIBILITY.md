# Capability-based card discovery

Discovery uses actual Android interfaces and card responses. It has no
phone-brand, model or Android-16 admission list.

1. Enumerate registered Secure Element AIDL instances and trusted interface classes.
2. Check declared HIDL HAL readers if no AIDL card was verified.
3. Try the implemented Shell Telephony physical-slot/port API.
4. Try framework OMAPI, retaining its access rules.

Each reader must pass service/interface lookup, card presence, opening the fixed
ISD-R applet, a complete bounded GetEID response and owned-channel closure.
Discovery does not install profiles, send notification HTTP, initialize/reset the
HAL or change global communication settings.

More than one verified card requires explicit choice. Invalid/duplicate reader
inventories and uncertain cleanup stop fallback. A successful choice pins the
complete card identity and reader for the live service session. Changed cards
stop access; selection never clears journals or authorizes a write.

**Settings → Advanced → Compatibility check** presents fixed-vocabulary backend,
reader, stage and condition results. It preserves ambiguity when a failed lookup
cannot distinguish an absent service from denied access. Safe diagnostics omit
card identities, profile identifiers, activation data and raw vendor exceptions.

[Compatibility](COMPATIBILITY.md) lists backend limits. [Adapters](ADAPTERS.md)
maps the code and regression expectations. [Check a new phone](NEW_DEVICE_CHECK.md)
explains real-device reporting. Per-session hardware logs belong in internal
archives; a concise, scoped compatibility result belongs in the public project.
