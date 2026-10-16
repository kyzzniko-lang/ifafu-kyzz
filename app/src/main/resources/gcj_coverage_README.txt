GCJ-02 geographic coverage approximation

Source: Natural Earth v5.1.1, 1:10m Admin 0 Countries, CHN/HKG/MAC polygons.
Dataset URL:
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.1/geojson/ne_10m_admin_0_countries.geojson
Upstream SHA256:
239eec57ac17f100a11e2536cffc56752c318b50ae765b0918ff7aab4ce8f255
License: Public domain.
License statement: https://www.naturalearthdata.com/about/terms-of-use/
Made with Natural Earth. https://www.naturalearthdata.com/

The geographic polygons select mainland China, its dataset islands, Hong Kong
and Macao for map-coordinate conversion. Region selection is a technical
coordinate policy, not a territorial-boundary statement. Taiwan and surrounding
overseas regions do not use this conversion policy.

This is a geographic approximation, not a boundary published by the map vendor
and not survey-grade data. The map vendor does not publish an exact GCJ coverage
polygon. The upstream coastline and boundaries have their original 1:10m
generalization; there is no additional simplification. Source coordinates are
stored at their existing six decimal places. Near coasts, reclaimed land and
international borders, applicability requires comparison with the map vendor.

The transform shifts covered coordinates but leaves uncovered coordinates
unchanged. Their numeric images may overlap along a boundary, so a latitude/
longitude pair alone cannot always distinguish a shifted GCJ point from a nearby
unshifted WGS point. Inverse conversion prioritizes a valid covered GCJ inverse
candidate. This preserves forward/inverse round trips when a shift crosses the
geographic boundary; it cannot resolve that source ambiguity without provenance.

Binary format (big endian): KGCJ magic, int32 version=1, int32 polygon count;
for each polygon, int32 ring count (first is outer, subsequent rings are holes);
for each ring, int32 vertex count, then longitude/latitude int32 microdegrees.
Rings close implicitly. The file has 76 polygons and 14,532 vertices.
Bundled binary SHA256:
9c98bd2707b3a6627d66c700c215d09e24d845ef845a7bb68c5461069a2b7147

Regenerate: python scripts/generate_gcj_coverage.py
Offline regenerate: add --source PATH_TO_PINNED_GEOJSON.
The generator verifies the source checksum and does not accept new upstream
versions silently. Review fixtures and border behavior before changing the pin.
