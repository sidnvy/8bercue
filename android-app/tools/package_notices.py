#!/usr/bin/env python3
"""Assemble unmodified upstream notices into the app. Does not resolve license scope."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
third=root/'third_party'
files=[third/'zxing-Apache-2.0-LICENSE.txt',third/'sentry-java-MIT-LICENSE.txt',third/'shizuku-api-MIT-LICENSE.txt']
files+=sorted((third/'lpac-licenses').iterdir())
heading='''8bercue — third-party notices

8bercue is an independent project and is not an official 5ber product.
Original 8bercue code is licensed under AGPL-3.0-only by sid.
Third-party licenses and notices apply to their respective components.

Runtime components: lpac (AGPL-3.0-only), libeuicc (LGPL-2.1-only alternative),
cJSON and cjson-ext (MIT), ZXing core 3.5.3 (Apache-2.0), Sentry Android core 8.61.0 (MIT), and optional Shizuku API/provider 13.1.5 (MIT).
The bundled lpac source archive also contains platform-specific files that are
not compiled into this Android build; their original notices are preserved.
The commercial libeuicc license text is retained as upstream documentation;
8bercue has not acquired or asserted a commercial license.

Corresponding native source, local patch and offline build instructions are
provided alongside the APK with the complete corresponding release source.
See COPYING and LICENSING.md in the source package.
'''
text=heading
for file in files:
    text+='\n\n===== '+str(file.relative_to(third))+' =====\n\n'+file.read_text()
dest=root/'app/src/main/assets/legal/third-party-notices.txt'
dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text)
print('PASS packaged unmodified upstream notice texts:',len(files))
