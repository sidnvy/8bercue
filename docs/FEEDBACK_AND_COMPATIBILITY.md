# Feedback and diagnostics

Use **Settings → Help & feedback** for setup help, a problem report or diagnostic
export. Export offers a ZIP or text summary; preview and confirmation precede
sharing. Opening a report does not submit it.

There are no background analytics. Minimized crash reporting is optional and off
by default under **Settings → Privacy & about**. Manual reporting remains a
separate explicit action. Builds without a reporting destination can use offline
export/email; see [reporting builds](../release/REPORTING_BUILD.md).

Compatibility diagnostics describe backend, reader, stage and fixed error
categories. They do not include activation data, full card/profile identities,
raw APDUs or raw vendor exception strings. A missing/denied service cannot always
be distinguished by an API failure; reports retain that uncertainty.

For a new phone or card, use [the device checklist](NEW_DEVICE_CHECK.md) and
[issue template](../.github/ISSUE_TEMPLATE/device-compatibility.yml). Keep public
reports short and exclude credentials, full EID/ICCID and unreviewed screenshots.
