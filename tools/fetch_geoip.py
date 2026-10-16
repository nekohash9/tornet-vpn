"""Кладёт базу GeoIP Tor в assets — без неё не работает выбор страны выхода.

Берётся из свежего Tor Expert Bundle. Запуск: python tools/fetch_geoip.py
"""

import io
import json
import tarfile
import urllib.request
from pathlib import Path

DOWNLOADS = "https://aus1.torproject.org/torbrowser/update_3/release/downloads.json"
BUNDLE = "https://dist.torproject.org/torbrowser/{v}/tor-expert-bundle-linux-x86_64-{v}.tar.gz"
TARGET = Path(__file__).resolve().parent.parent / "app/src/main/assets/geoip"


def main() -> None:
    version = json.load(urllib.request.urlopen(DOWNLOADS, timeout=60))["version"]
    data = urllib.request.urlopen(BUNDLE.format(v=version), timeout=300).read()
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith("data/geoip"))
        geoip = tar.extractfile(member).read()
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_bytes(geoip)
    print(f"Tor {version}: {TARGET} ({len(geoip) // 1024} KB)")


if __name__ == "__main__":
    main()
