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

package com.graphhopper;

import com.carrotsearch.hppc.IntIntHashMap;
import com.carrotsearch.hppc.cursors.IntIntCursor;
import com.graphhopper.config.Profile;
import com.graphhopper.gtfs.GraphHopperGtfs;
import com.graphhopper.gtfs.GtfsStorage;
import com.graphhopper.util.Helper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stops are attached to the street network once per mode, so that a query's access/egress profile
 * decides which attachment it uses (DMP-16462).
 */
class PerModeStopSnappingIT {

    private static GraphHopperConfig config(String graphLoc, String stopSnapProfiles) {
        GraphHopperConfig ghConfig = new GraphHopperConfig();
        ghConfig.putObject("datareader.file", "files/beatty.osm");
        ghConfig.putObject("import.osm.ignored_highways", "");
        ghConfig.putObject("gtfs.file", "files/sample-feed");
        ghConfig.putObject("graph.location", graphLoc);
        if (stopSnapProfiles != null) {
            ghConfig.putObject("gtfs.stop_snap_profiles", stopSnapProfiles);
        }
        ghConfig.setProfiles(Arrays.asList(
                new Profile("foot").setVehicle("foot").setWeighting("fastest"),
                new Profile("car_default").setVehicle("car").setWeighting("fastest")));
        return ghConfig;
    }

    private static GraphHopperGtfs importFresh(GraphHopperConfig ghConfig) {
        Helper.removeDir(new File(ghConfig.getString("graph.location", "")));
        GraphHopperGtfs graphHopperGtfs = new GraphHopperGtfs(ghConfig);
        graphHopperGtfs.init(ghConfig);
        graphHopperGtfs.importOrLoad();
        return graphHopperGtfs;
    }

    @Test
    void defaultsToFootOnly() {
        GraphHopperConfig ghConfig = config("target/PerModeStopSnappingIT-default", null);
        GraphHopperGtfs graphHopperGtfs = importFresh(ghConfig);
        try {
            GtfsStorage storage = graphHopperGtfs.getGtfsStorage();
            // Riders walk to transit. Requiring an attachment that cars can also use is what stranded
            // airport platforms, so foot alone is the default rather than every configured profile.
            assertThat(storage.getStopSnapProfiles()).containsExactly("foot");
            assertThat(storage.getPrimaryStopSnapProfile()).isEqualTo("foot");
            assertThat(storage.getPtToStreet("foot")).isNotEmpty();

            assertThatThrownBy(() -> storage.getPtToStreet("car_default"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gtfs.stop_snap_profiles");
        } finally {
            graphHopperGtfs.close();
        }
    }

    @Test
    void buildsAnIndependentAttachmentPerConfiguredProfile() {
        GraphHopperConfig ghConfig = config("target/PerModeStopSnappingIT-multi", "foot,car_default");
        GraphHopperGtfs graphHopperGtfs = importFresh(ghConfig);
        try {
            GtfsStorage storage = graphHopperGtfs.getGtfsStorage();
            assertThat(storage.getStopSnapProfiles()).containsExactly("foot", "car_default");
            // First entry is primary: it alone decides stop node identity.
            assertThat(storage.getPrimaryStopSnapProfile()).isEqualTo("foot");

            IntIntHashMap footAttachments = storage.getPtToStreet("foot");
            IntIntHashMap carAttachments = storage.getPtToStreet("car_default");
            assertThat(footAttachments).isNotEmpty();
            assertThat(carAttachments).isNotEmpty();
            // Separate maps, not aliases of one another.
            assertThat(carAttachments).isNotSameAs(footAttachments);

            // Both maps are keyed by stop node, so a query can look up its own mode's attachment for any
            // stop it reaches. A stop may be absent from a mode's map -- that is the legitimate "not
            // reachable by this mode" answer, which the search reads as a street node of -1 -- but every
            // key present must be a real stop node.
            Set<Integer> stopNodes = new HashSet<>(storage.getStationNodes().values());
            assertThat(stopNodes).isNotEmpty();
            for (IntIntCursor c : footAttachments) {
                assertThat(stopNodes).contains(c.key);
            }
            for (IntIntCursor c : carAttachments) {
                assertThat(stopNodes).contains(c.key);
            }
            assertThat(footAttachments.size()).isLessThanOrEqualTo(stopNodes.size());
        } finally {
            graphHopperGtfs.close();
        }
    }

    @Test
    void perProfileAttachmentsSurviveReload() {
        GraphHopperConfig ghConfig = config("target/PerModeStopSnappingIT-reload", "foot,car_default");
        GraphHopperGtfs imported = importFresh(ghConfig);
        IntIntHashMap footBefore;
        IntIntHashMap carBefore;
        try {
            footBefore = new IntIntHashMap(imported.getGtfsStorage().getPtToStreet("foot"));
            carBefore = new IntIntHashMap(imported.getGtfsStorage().getPtToStreet("car_default"));
        } finally {
            imported.close();
        }

        GraphHopperGtfs reloaded = new GraphHopperGtfs(ghConfig);
        reloaded.init(ghConfig);
        reloaded.importOrLoad();
        try {
            GtfsStorage storage = reloaded.getGtfsStorage();
            assertThat(storage.getStopSnapProfiles()).containsExactly("foot", "car_default");
            assertThat(storage.getPtToStreet("foot")).isEqualTo(footBefore);
            assertThat(storage.getPtToStreet("car_default")).isEqualTo(carBefore);
        } finally {
            reloaded.close();
        }
    }

    @Test
    void fallsBackToPrimaryForAProfileThatWasNotSnapped() {
        GraphHopperConfig ghConfig = config("target/PerModeStopSnappingIT-fallback", "foot");
        GraphHopperGtfs graphHopperGtfs = importFresh(ghConfig);
        try {
            GtfsStorage storage = graphHopperGtfs.getGtfsStorage();
            // A request may name any configured routing profile, not only a snapped one. Such a query has
            // to keep routing -- on the primary attachments -- rather than fail.
            assertThat(storage.resolveStopSnapProfile("car_default")).isEqualTo("foot");
            assertThat(storage.resolveStopSnapProfile("foot")).isEqualTo("foot");
        } finally {
            graphHopperGtfs.close();
        }
    }

    @Test
    void rejectsAnUnknownProfileNameInsteadOfNpeing() {
        GraphHopperConfig ghConfig = config("target/PerModeStopSnappingIT-bad", "foot,walk");
        Helper.removeDir(new File("target/PerModeStopSnappingIT-bad"));
        GraphHopperGtfs graphHopperGtfs = new GraphHopperGtfs(ghConfig);
        graphHopperGtfs.init(ghConfig);
        // getProfile() returns null for an unknown name, which would NPE inside createWeighting.
        // Raised before any store is created, so it is not reported as a bad GTFS feed -- and there is
        // nothing to close afterwards.
        assertThatThrownBy(graphHopperGtfs::importOrLoad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("walk")
                .hasMessageContaining("gtfs.stop_snap_profiles");
    }
}
