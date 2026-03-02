# BikeHopper GraphHopper Integration - Changes Summary

This document summarizes the changes made to integrate bike routing improvements from the [BikeHopper fork](https://github.com/bikehopper/graphhopper) into this GraphHopper codebase.

## Overview

| Category | Files Changed | Description |
|----------|---------------|-------------|
| Bike Routing | 1 | Car-free pathway priority bonus |
| Cycleway Support | 7 | Cycleway parsing and driving side detection |
| Country Rules | 3 | Country-specific driving side support |
| Elevation Infrastructure | 5 | Support for fractional degree elevation tiles (future-proofing) |
| Test Updates | 2 | Updated tests for new routing behavior |

All unit tests pass across all modules (core, web, web-api, web-bundle, reader-gtfs, map-matching, navigation).

---

## 1. Car-Free Pathway Priority Bonus

**Inspired by:** [BikeHopper PR #141 - Reward car-free pathways (motor_vehicle=no)](https://github.com/bikehopper/graphhopper/pull/141)

### Description
Routes with `motor_vehicle=no` now receive a priority bonus, making bike routing prefer car-free pathways like multi-use trails, pedestrian plazas, and bike-only paths.

### Files Modified
- `core/src/main/java/com/graphhopper/routing/util/parsers/BikeCommonPriorityParser.java`

### Key Code Change
```java
// Added priority bonus for car-free pathways
if ("no".equals(way.getTag("motor_vehicle"))) {
    priorityFromRelation = PriorityCode.VERY_NICE.getValue();
}
```

---

## 2. Cycleway Parser & Driving Side Support

**Inspired by:** [BikeHopper PR #175 - Fix opposite value handling for cycleway:left,right](https://github.com/bikehopper/graphhopper/pull/175)

### Description
Added support for parsing cycleway tags (`cycleway:left`, `cycleway:right`, `cycleway:both`) and determining the driving side (left-hand vs right-hand traffic) for proper cycleway positioning.

### New Files Created
| File | Description |
|------|-------------|
| `core/src/main/java/com/graphhopper/routing/ev/Cycleway.java` | Enum for cycleway types (TRACK, LANE, SHARED_LANE, SHARE_BUSWAY, etc.) |
| `core/src/main/java/com/graphhopper/routing/ev/DrivingSide.java` | Enum for driving side (LEFT, RIGHT) with country defaults |
| `core/src/main/java/com/graphhopper/routing/util/parsers/OSMCyclewayParser.java` | Parser for cycleway OSM tags |

### Files Modified
| File | Change |
|------|--------|
| `DefaultEncodedValueFactory.java` | Added cycleway and driving_side encoded values |
| `DefaultTagParserFactory.java` | Added OSMCyclewayParser |
| `CountryRule.java` | Added `getDrivingSide()` method |
| `UnitedStatesCountryRule.java` | New file - US country rule (right-hand driving) |
| `IrelandCountryRule.java` | Added left-hand driving side |
| `UnitedKingdomCountryRule.java` | Added left-hand driving side |

---

## 3. Elevation Infrastructure (Fractional Degree Tile Support)

**Inspired by:** BikeHopper's infrastructure work to support USGS 1/9 arc-second elevation data

### Description
Modified elevation provider infrastructure to support fractional degree tiles (e.g., 0.25° x 0.25° tiles) instead of only integer degree tiles. This change future-proofs the codebase for higher-resolution elevation data sources.

**Note:** While the USGS provider itself was not included (as the data source was discontinued in 2015), the infrastructure changes were kept as they:
- Don't break existing elevation providers (CGIAR, GMTED, SRTM, etc.)
- Enable future use of fractional-degree elevation data sources
- Make the code more flexible and maintainable

### Files Modified
| File | Change |
|------|--------|
| `HeightTile.java` | Changed `minLat`, `minLon`, `horizontalDegree`, `verticalDegree` from `int` to `double` |
| `AbstractTiffElevationProvider.java` | Changed `LAT_DEGREE`, `LON_DEGREE` from `int` to `double`; updated constructor and method signatures; changed `fillDataAccessWithElevationData` visibility from `private` to package-private |
| `CGIARProvider.java` | Updated `getMinLatForTile()` and `getMinLonForTile()` return types from `int` to `double` |
| `GMTEDProvider.java` | Updated `getMinLatForTile()` and `getMinLonForTile()` return types from `int` to `double`; updated helper method signatures |
| `MultiSourceElevationProvider.java` | Added documentation clarifying CGIAR/GMTED usage |

---

## 4. Test Updates

Several tests were updated to reflect the new routing behavior:

| Test | Change | Reason |
|------|--------|--------|
| `GraphHopperTest.testAlternativeRoutesBike` | Expected time: 3116 → 3103 seconds | motor_vehicle=no priority bonus |
| `RouteResourceClientHCTest.testWaypointIndicesAndLegDetails` | Expected distance: 5428 → 5151 meters | motor_vehicle=no priority bonus |

---

## 5. Configuration Changes

### New Config Files
- `config-bart.yml` - BART transit configuration
- `config-mini_norcal.yml` - Mini Northern California configuration

### Profile Changes
Tests and configurations updated to explicitly specify weighting (fastest, shortest, etc.) in profiles.

---

## Usage

### Enable Car-Free Pathway Bonus
This is enabled by default for all bike profiles (bike, mtb, racingbike).

### Enable Cycleway Parsing
Add to your encoded values configuration:
```yaml
graph.encoded_values: cycleway_left, cycleway_right, driving_side
```

---

## Related BikeHopper Issues/PRs

| PR | Title | Status |
|----|-------|--------|
| [#141](https://github.com/bikehopper/graphhopper/pull/141) | Reward car-free pathways (motor_vehicle=no) | ✅ Implemented |
| [#175](https://github.com/bikehopper/graphhopper/pull/175) | Fix opposite value handling for cycleway:left,right | ✅ Implemented |
| [#150](https://github.com/bikehopper/graphhopper/pull/150) | Enable CountryRules by default | ✅ Country rules added |

---

## Behavioral Impact

1. **Bike routes now prefer car-free pathways** - Routes with `motor_vehicle=no` get a priority bonus, resulting in slightly different (often shorter/safer) routes

2. **Cycleway information available** - Routing can now consider cycleway type and position relative to driving direction

3. **Country-specific driving side** - Infrastructure ready for left-hand vs right-hand driving considerations

