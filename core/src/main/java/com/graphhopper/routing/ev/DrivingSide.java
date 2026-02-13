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
 * This enum defines the driving side for a country or region.
 * Used to correctly interpret cycleway:left and cycleway:right tags,
 * which have different meanings depending on the driving side.
 * <p>
 * In right-hand traffic countries (most of the world):
 * - cycleway:right is in the same direction as traffic flow
 * - cycleway:left is against traffic flow (contraflow)
 * <p>
 * In left-hand traffic countries (UK, Japan, Australia, etc.):
 * - cycleway:left is in the same direction as traffic flow
 * - cycleway:right is against traffic flow (contraflow)
 */
public enum DrivingSide {
    /**
     * Left-hand traffic (drive on left side of road).
     * Used in UK, Japan, Australia, India, etc.
     */
    LEFT,
    
    /**
     * Right-hand traffic (drive on right side of road).
     * Used in most of the world including US, Europe (except UK), China, etc.
     */
    RIGHT,
    
    /**
     * Other/unknown driving side.
     */
    OTHER,
    
    /**
     * Missing driving side information.
     */
    MISSING;

    public static final String KEY = "driving_side";

    public static EnumEncodedValue<DrivingSide> create() {
        return new EnumEncodedValue<>(KEY, DrivingSide.class);
    }

    @Override
    public String toString() {
        return Helper.toLowerCase(super.toString());
    }

    public static DrivingSide find(String name) {
        if (Helper.isEmpty(name))
            return MISSING;
        try {
            return DrivingSide.valueOf(Helper.toUpperCase(name));
        } catch (IllegalArgumentException ex) {
            return OTHER;
        }
    }
    
    /**
     * Returns the opposite driving side.
     * LEFT -> RIGHT, RIGHT -> LEFT, others return themselves.
     */
    public DrivingSide opposite() {
        if (this == LEFT) return RIGHT;
        if (this == RIGHT) return LEFT;
        return this;
    }
}

