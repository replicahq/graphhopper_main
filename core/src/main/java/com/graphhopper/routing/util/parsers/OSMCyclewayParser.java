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
package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.Cycleway;
import com.graphhopper.routing.ev.DrivingSide;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.util.countryrules.CountryRule;
import com.graphhopper.storage.IntsRef;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses cycleway tags from OSM ways and stores the cycleway infrastructure type
 * for both forward and backward directions.
 * <p>
 * This parser handles:
 * - cycleway (applies to both directions)
 * - cycleway:both (applies to both directions)
 * - cycleway:left / cycleway:right (direction depends on driving side)
 * - opposite_* tags (deprecated but still used)
 * <p>
 * Based on BikeHopper's cycleway parsing improvements.
 */
public class OSMCyclewayParser implements TagParser {

    private final EnumEncodedValue<Cycleway> cyclewayEnc;
    
    // Map of opposite_* tag values to their normalized cycleway type
    private static final Map<String, Cycleway> OPPOSITE_LANES = new HashMap<>();
    
    static {
        OPPOSITE_LANES.put("opposite", Cycleway.SHARED_LANE);
        OPPOSITE_LANES.put("opposite_lane", Cycleway.LANE);
        OPPOSITE_LANES.put("opposite_track", Cycleway.TRACK);
        OPPOSITE_LANES.put("opposite_share_busway", Cycleway.SHARE_BUSWAY);
    }

    public OSMCyclewayParser(EnumEncodedValue<Cycleway> cyclewayEnc) {
        this.cyclewayEnc = cyclewayEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay readerWay, IntsRef relationFlags) {
        String cycleway = readerWay.getTag("cycleway");
        String cyclewayBoth = readerWay.getTag("cycleway:both");
        String cyclewayLeft = readerWay.getTag("cycleway:left");
        String cyclewayRight = readerWay.getTag("cycleway:right");
        
        // Get driving side from country rule or way tag override
        DrivingSide drivingSide = getDrivingSide(readerWay);
        
        Cycleway cyclewayForward;
        Cycleway cyclewayBackward;
        
        // If we find opposite_* tags, normalize them, and treat that as the final word
        // (discounting any contradictory left/right tags which would be a tagging error).
        // Note: in this case it's ambiguous what forward infrastructure exists, so we
        // set to missing. This is why this kind of tagging is deprecated in OSM.
        if (cycleway != null && OPPOSITE_LANES.containsKey(cycleway)) {
            cyclewayForward = Cycleway.MISSING;
            cyclewayBackward = OPPOSITE_LANES.get(cycleway);
        } else {
            // Driving side is needed to compute default directionality of cycleway:left/right.
            // In right-hand traffic: right side = forward direction, left side = backward
            // In left-hand traffic: left side = forward direction, right side = backward
            
            Cycleway leftCycleway = Cycleway.find(cyclewayLeft);
            Cycleway rightCycleway = Cycleway.find(cyclewayRight);
            Cycleway bothCycleway = cyclewayBoth != null ? Cycleway.find(cyclewayBoth) : Cycleway.MISSING;
            Cycleway baseCycleway = cycleway != null ? Cycleway.find(cycleway) : Cycleway.MISSING;
            
            if (drivingSide == DrivingSide.LEFT) {
                // Left-hand traffic: left = forward, right = backward
                cyclewayForward = selectBest(leftCycleway, bothCycleway, baseCycleway);
                cyclewayBackward = selectBest(rightCycleway, bothCycleway, baseCycleway);
            } else {
                // Right-hand traffic (default): right = forward, left = backward
                cyclewayForward = selectBest(rightCycleway, bothCycleway, baseCycleway);
                cyclewayBackward = selectBest(leftCycleway, bothCycleway, baseCycleway);
            }
        }
        
        // Store the cycleway values - forward direction uses reverse=false, backward uses reverse=true
        if (cyclewayForward != Cycleway.MISSING) {
            cyclewayEnc.setEnum(false, edgeId, edgeIntAccess, cyclewayForward);
        }
        if (cyclewayBackward != Cycleway.MISSING) {
            cyclewayEnc.setEnum(true, edgeId, edgeIntAccess, cyclewayBackward);
        }
    }
    
    /**
     * Select the best (most specific) cycleway value from the candidates.
     * Prefers specific values over MISSING.
     */
    private Cycleway selectBest(Cycleway specific, Cycleway both, Cycleway base) {
        if (specific != Cycleway.MISSING) return specific;
        if (both != Cycleway.MISSING) return both;
        return base;
    }
    
    /**
     * Get the driving side for this way, checking for way-level override first,
     * then falling back to country rule.
     */
    private DrivingSide getDrivingSide(ReaderWay readerWay) {
        // Check for way-level driving:side override tag
        String drivingSideTag = readerWay.getTag("driving_side");
        if (drivingSideTag != null) {
            return DrivingSide.find(drivingSideTag);
        }
        
        // Fall back to country rule
        CountryRule countryRule = readerWay.getTag("country_rule", null);
        if (countryRule != null) {
            return countryRule.getDrivingSide();
        }
        
        // Default to right-hand traffic (most common worldwide)
        return DrivingSide.RIGHT;
    }
}

