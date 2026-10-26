# 8bercue 0.20.0-beta.6 — candidate

An operator's explicit authentication refusal no longer becomes an indefinite
card restriction when the download worker and native process have stopped,
no package-install command was sent, and independent reads prove that the same
card's complete inventory is unchanged. The app reports that installation did
not complete and allows another valid activation code. It never retries the
failed code or claims that code is unused.

For an older download whose outcome remains unknown, Operation records can
offer a separate confirmation only when the original card, complete history
and unchanged pre-download inventory are available. Confirmation rereads the
card twice and checks worker quiescence under the transaction lock. The
original outcome remains unknown, with an immutable abandonment receipt;
no install, enable or delete is replayed. Changed inventories and incomplete
history remain restricted. Other cards keep their independent records.

VersionCode: **54**. Exact production-signed APK acceptance is pending. The
protected workflow prepares a draft; publication requires testing that artifact.
Detailed device acceptance remains in internal archives. Offline tests and local
builds do not qualify actual downloads, mobile data or other phones.

A same-signer upgrade retains app data and pairing identity. Crash reporting
remains optional and off by default.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
