/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.ev;

import com.graphhopper.util.Helper;

/**
 * This enum defines the cycleway infrastructure type of an edge.
 * Based on BikeHopper's cycleway parsing improvements.
 * <p>
 * Values are ordered roughly from best to worst cycling infrastructure:
 * - TRACK: Physically separated cycle track
 * - LANE: Painted cycle lane on road
 * - SHARED_LANE: Sharrow or shared lane marking
 * - SHARE_BUSWAY: Shared bus lane
 * - SIDEPATH: Separate sidepath (use_sidepath)
 * - YES/RIGHT/LEFT/BOTH: Generic cycleway tags
 * - OTHER: Unknown cycleway type
 * - MISSING: No cycleway information
 */
public enum Cycleway {
    // Order matters - roughly from best to worst infrastructure
    MISSING,
    OTHER,
    TRACK,
    LANE,
    SHARED_LANE,
    SHARE_BUSWAY,
    SIDEPATH,
    YES,
    RIGHT,
    LEFT,
    BOTH;

    public static final String KEY = "cycleway";

    /**
     * Creates a Cycleway encoded value that stores values for both directions.
     * This is needed because cycleway infrastructure can be different for forward
     * and backward directions (e.g., cycleway:left vs cycleway:right).
     */
    public static EnumEncodedValue<Cycleway> create() {
        return new EnumEncodedValue<>(KEY, Cycleway.class, true);
    }

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    /**
     * Find the Cycleway enum value from a string tag value.
     * Handles various OSM cycleway tag values including opposite_* variants.
     */
    public static Cycleway find(String name) {
        if (Helper.isEmpty(name))
            return MISSING;
        
        // Normalize opposite_* tags to their base type
        if (name.startsWith("opposite_")) {
            name = name.substring("opposite_".length());
        }
        
        // Handle common aliases
        switch (Helper.toLowerCase(name)) {
            case "track":
            case "opposite_track":
                return TRACK;
            case "lane":
            case "opposite_lane":
                return LANE;
            case "shared_lane":
            case "sharrow":
                return SHARED_LANE;
            case "share_busway":
            case "shared_busway":
                return SHARE_BUSWAY;
            case "sidepath":
            case "use_sidepath":
                return SIDEPATH;
            case "yes":
                return YES;
            case "right":
                return RIGHT;
            case "left":
                return LEFT;
            case "both":
                return BOTH;
            case "no":
            case "none":
                return MISSING;
            default:
                try {
                    return Cycleway.valueOf(Helper.toUpperCase(name));
                } catch (IllegalArgumentException ex) {
                    return OTHER;
                }
        }
    }
}

