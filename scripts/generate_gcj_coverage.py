#!/usr/bin/env python3
"""Rebuild the bundled GCJ geographic coverage approximation (stdlib only).

The runtime does not download data. This developer tool pins and verifies the
upstream dataset before selecting its CHN, HKG and MAC polygons, without further
simplification. Natural Earth data is public domain; see gcj_coverage_README.txt.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import urllib.request

SOURCE_URL = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/"
    "v5.1.1/geojson/ne_10m_admin_0_countries.geojson"
)
SOURCE_SHA256 = "239eec57ac17f100a11e2536cffc56752c318b50ae765b0918ff7aab4ce8f255"
REGIONS = ("CHN", "HKG", "MAC")
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/resources/gcj_coverage.bin"


def generate(source: bytes) -> bytes:
    if hashlib.sha256(source).hexdigest() != SOURCE_SHA256:
        raise ValueError("Source checksum mismatch; review the new dataset before updating the pin")
    features = json.loads(source)["features"]
    selected = {f["properties"]["ADM0_A3"]: f for f in features
                if f["properties"]["ADM0_A3"] in REGIONS}
    if set(selected) != set(REGIONS):
        raise ValueError("Expected all three coordinate-coverage regions")
    polygons = []
    for region in REGIONS:
        geometry = selected[region]["geometry"]
        if geometry["type"] == "Polygon":
            polygons.append(geometry["coordinates"])
        elif geometry["type"] == "MultiPolygon":
            polygons.extend(geometry["coordinates"])
        else:
            raise ValueError("Coverage requires polygon geometry")
    result = bytearray(struct.pack(">4sii", b"KGCJ", 1, len(polygons)))
    for polygon in polygons:
        result.extend(struct.pack(">i", len(polygon)))
        for ring in polygon:
            points = [(round(lng * 1_000_000), round(lat * 1_000_000)) for lng, lat in ring]
            if points[0] == points[-1]:
                points.pop()  # Rings close implicitly; do not store a duplicate zero-length edge.
            if len(points) < 3:
                raise ValueError("A boundary ring must have at least three vertices")
            result.extend(struct.pack(">i", len(points)))
            for lng, lat in points:
                if not (-180_000_000 <= lng <= 180_000_000 and -90_000_000 <= lat <= 90_000_000):
                    raise ValueError("Invalid WGS-84 boundary coordinate")
                result.extend(struct.pack(">ii", lng, lat))
    return bytes(result)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Optional local copy of the pinned upstream GeoJSON")
    args = parser.parse_args()
    if args.source:
        source = args.source.read_bytes()
    else:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            source = response.read()
    data = generate(source)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(data)
    print(f"{OUTPUT}: {len(data)} bytes, SHA256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()
