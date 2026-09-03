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

package com.graphhopper.gtfs;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.Transfer;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimaps;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.Profile;
import com.graphhopper.gtfs.analysis.Trips;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.index.InMemConstructionIndex;
import com.graphhopper.storage.index.IndexStructureInfo;
import com.graphhopper.storage.index.LineIntIndex;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import com.graphhopper.util.shapes.BBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class GraphHopperGtfs extends GraphHopper {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphHopperGtfs.class);

    private final GraphHopperConfig ghConfig;
    private GtfsStorage gtfsStorage;
    private PtGraph ptGraph;

    public GraphHopperGtfs(GraphHopperConfig ghConfig) {
        this.ghConfig = ghConfig;
    }

    @Override
    protected void importOSM() {
        if (ghConfig.has("datareader.file")) {
            super.importOSM();
        } else {
            createBaseGraphAndProperties();
            writeEncodingManagerToProperties();
        }
    }

    @Override
    protected void importPublicTransit() {
        ptGraph = new PtGraph(getBaseGraph().getDirectory(), 100);
        gtfsStorage = new GtfsStorage(getBaseGraph().getDirectory());
        gtfsStorage.setPtGraph(ptGraph);
        LineIntIndex stopIndex = new LineIntIndex(new BBox(-180.0, 180.0, -90.0, 90.0), getBaseGraph().getDirectory(), "stop_index");
        if (getGtfsStorage().loadExisting()) {
            ptGraph.loadExisting();
            stopIndex.loadExisting();
            if (ghConfig.getBool("gtfs.trip_based", false)) {
                for (String trafficDayString : ghConfig.getString("gtfs.schedule_day", null).split(",")) {
                    LocalDate trafficDay = LocalDate.parse(trafficDayString);
                    LOGGER.info("Loading trip-based transfers for pt router. Schedule day: {}", trafficDay);
                    gtfsStorage.tripTransfers.getTripTransfers().put(trafficDay, gtfsStorage.deserializeTripTransfersMap("trip_transfers_" + trafficDayString));
                }
                for (Map.Entry<String, GTFSFeed> entry : this.gtfsStorage.getGtfsFeeds().entrySet()) {
                    for (Stop stop : entry.getValue().stops.values()) {
                        gtfsStorage.tripTransfers.getPatternBoardings(new GtfsStorage.FeedIdWithStopId(entry.getKey(), stop.stop_id));
                    }
                }
            }
        } else {
            // Resolved before any store is created: a bad profile name is a config error, and reporting it
            // from inside the block below would blame the GTFS feed for it.
            List<String> stopSnapProfiles = readStopSnapProfiles();
            ensureWriteAccess();
            getGtfsStorage().create();
            getGtfsStorage().initStopSnapProfiles(stopSnapProfiles);
            ptGraph.create(100);
            InMemConstructionIndex indexBuilder = new InMemConstructionIndex(IndexStructureInfo.create(
                    new BBox(-180.0, 180.0, -90.0, 90.0), 300));
            try {
                int idx = 0;
                List<String> gtfsFiles = ghConfig.has("gtfs.file") ? Arrays.asList(ghConfig.getString("gtfs.file", "").split(",")) : Collections.emptyList();
                for (String gtfsFile : gtfsFiles) {
                    getGtfsStorage().loadGtfsFromZipFileOrDirectory("gtfs_" + idx++, new File(gtfsFile));
                }
                getGtfsStorage().postInit();
                String primaryProfile = getGtfsStorage().getPrimaryStopSnapProfile();
                LOGGER.info("Snapping stops to the street network once per profile: {} (primary: {})",
                        stopSnapProfiles, primaryProfile);
                // Feed-independent, so build the filters once rather than per feed.
                Map<String, EdgeFilter> snapFilters = new LinkedHashMap<>();
                for (String profileName : stopSnapProfiles) {
                    snapFilters.put(profileName, new DefaultSnapFilter(createWeighting(getProfile(profileName), new PMap()),
                            getEncodingManager().getBooleanEncodedValue(Subnetwork.key(profileName))));
                }
                Map<String, Transfers> allTransfers = new HashMap<>();
                HashMap<String, GtfsReader> allReaders = new HashMap<>();
                getGtfsStorage().getGtfsFeeds().forEach((id, gtfsFeed) -> {
                    Transfers transfers = new Transfers(gtfsFeed);
                    allTransfers.put(id, transfers);
                    GtfsReader gtfsReader = new GtfsReader(id, ptGraph, ptGraph, getGtfsStorage(), getLocationIndex(), transfers, indexBuilder);
                    // One attachment per mode, rather than one attachment that every mode must accept.
                    gtfsReader.connectStopsToStreetNetwork(snapFilters, primaryProfile);
                    LOGGER.info("Building transit graph for feed {}", gtfsFeed.feedId);
                    gtfsReader.buildPtNetwork();
                    allReaders.put(id, gtfsReader);
                });
                interpolateTransfers(allReaders, allTransfers);
                if (ghConfig.getBool("gtfs.trip_based", false)) {
                    ArrayListMultimap<Integer, GtfsStorage.FeedIdWithStopId> stopsForStationNode = Multimaps.invertFrom(Multimaps.forMap(gtfsStorage.getStationNodes()), ArrayListMultimap.create());
                    for (String trafficDayString : ghConfig.getString("gtfs.schedule_day", null).split(",")) {
                        LocalDate trafficDay = LocalDate.parse(trafficDayString);
                        LOGGER.info("Computing trip-based transfers for pt router. Schedule day: {}", trafficDay);
                        Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> tripTransfersMap = gtfsStorage.tripTransfers.getTripTransfers(trafficDay);
                        gtfsStorage.tripTransfers.findAllTripTransfersInto(tripTransfersMap, trafficDay, allTransfers, stopsForStationNode, ghConfig.getInt("gtfs.trip_based.max_transfer_time", 15 * 60));
                        LOGGER.info("Writing. Schedule day: {}", trafficDay);
                        gtfsStorage.serializeTripTransfersMap("trip_transfers_" + trafficDayString, tripTransfersMap);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error while constructing transit network. Is your GTFS file valid? Please check log for possible causes.", e);
            }
            ptGraph.flush();
            getGtfsStorage().flush();
            stopIndex.store(indexBuilder);
            stopIndex.flush();
        }
        gtfsStorage.setStopIndex(stopIndex);
    }

    /**
     * Profiles to snap stops for, from {@code gtfs.stop_snap_profiles} (comma-separated, first entry is
     * primary). Defaults to {@link GtfsStorage#DEFAULT_STOP_SNAP_PROFILE} alone: riders reach transit on
     * foot, and requiring an attachment that cars and trucks can also use strands stops whose only
     * nearby street is a footway (DMP-16462). List additional profiles to support non-walk access legs.
     */
    private List<String> readStopSnapProfiles() {
        String configured = ghConfig.getString("gtfs.stop_snap_profiles", GtfsStorage.DEFAULT_STOP_SNAP_PROFILE);
        List<String> profiles = new ArrayList<>();
        for (String name : configured.split(",")) {
            String profileName = name.trim();
            if (profileName.isEmpty()) {
                continue;
            }
            // getProfile returns null for an unknown name, which would otherwise NPE inside createWeighting.
            if (getProfile(profileName) == null) {
                throw new IllegalArgumentException("gtfs.stop_snap_profiles names profile '" + profileName
                        + "', which is not configured. Available profiles: "
                        + getProfiles().stream().map(Profile::getName).collect(Collectors.toList()));
            }
            if (!profiles.contains(profileName)) {
                profiles.add(profileName);
            }
        }
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("gtfs.stop_snap_profiles is set but names no usable profile: '"
                    + configured + "'");
        }
        return profiles;
    }

    private void interpolateTransfers(HashMap<String, GtfsReader> readers, Map<String, Transfers> allTransfers) {
        LOGGER.info("Looking for transfers");
        final int maxTransferWalkTimeSeconds = ghConfig.getInt("gtfs.max_transfer_interpolation_walk_time_seconds", 120);
        QueryGraph queryGraph = QueryGraph.create(getBaseGraph(), Collections.emptyList());
        Weighting transferWeighting = createWeighting(getProfile("foot"), new PMap());
        // Transfer walking always uses the primary profile's attachments, matching the foot weighting above.
        String transferSnapProfile = getGtfsStorage().getPrimaryStopSnapProfile();
        final GraphExplorer graphExplorer = new GraphExplorer(queryGraph, ptGraph, transferWeighting, getGtfsStorage(), RealtimeFeed.empty(), true, true, false, 5.0, false, 0, transferSnapProfile);
        getGtfsStorage().getStationNodes().values().stream().distinct().map(n -> new Label.NodeId(gtfsStorage.getPtToStreet(transferSnapProfile).getOrDefault(n, -1), n)).forEach(stationNode -> {
            MultiCriteriaLabelSetting router = new MultiCriteriaLabelSetting(graphExplorer, true, false, false, 0, new ArrayList<>());
            router.setLimitStreetTime(Duration.ofSeconds(maxTransferWalkTimeSeconds).toMillis());
            for (Label label : router.calcLabels(stationNode, Instant.ofEpochMilli(0))) {
                if (label.parent != null) {
                    if (label.edge.getType() == GtfsStorage.EdgeType.EXIT_PT) {
                        GtfsStorage.PlatformDescriptor fromPlatformDescriptor = label.edge.getPlatformDescriptor();
                        Transfers transfers = allTransfers.get(fromPlatformDescriptor.feed_id);
                        for (PtGraph.PtEdge ptEdge : ptGraph.edgesAround(stationNode.ptNode)) {
                            if (ptEdge.getType() == GtfsStorage.EdgeType.ENTER_PT) {
                                GtfsStorage.PlatformDescriptor toPlatformDescriptor = ptEdge.getAttrs().platformDescriptor;
                                LOGGER.debug(fromPlatformDescriptor + " -> " + toPlatformDescriptor);
                                if (!toPlatformDescriptor.feed_id.equals(fromPlatformDescriptor.feed_id)) {
                                    LOGGER.debug(" Different feed. Inserting transfer with " + (int) (label.streetTime / 1000L) + " s.");
                                    insertInterpolatedTripTransfer(fromPlatformDescriptor, toPlatformDescriptor, (int) (label.streetTime / 1000L), getSkippedEdgesForTransfer(label));
                                    insertInterpolatedTransfer(label, toPlatformDescriptor, readers, getSkippedEdgesForTransfer(label));
                                } else {
                                    List<Transfer> transfersToStop = transfers.getTransfersToStop(toPlatformDescriptor.stop_id, routeIdOrNull(toPlatformDescriptor));
                                    if (transfersToStop.stream().noneMatch(t -> t.from_stop_id.equals(fromPlatformDescriptor.stop_id))) {
                                        LOGGER.debug("  Inserting transfer with " + (int) (label.streetTime / 1000L) + " s.");
                                        insertInterpolatedTripTransfer(fromPlatformDescriptor, toPlatformDescriptor, (int) (label.streetTime / 1000L), getSkippedEdgesForTransfer(label));
                                        insertInterpolatedTransfer(label, toPlatformDescriptor, readers, getSkippedEdgesForTransfer(label));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }


    private void insertInterpolatedTripTransfer(GtfsStorage.PlatformDescriptor fromPlatformDescriptor, GtfsStorage.PlatformDescriptor toPlatformDescriptor, int streetTime, int[] skippedEdgesForTransfer) {
        if (skippedEdgesForTransfer.length > 0) { // TODO: Elsewhere, we distinguish empty path ("at" a node) from no path
            gtfsStorage.interpolatedTransfers.put(new GtfsStorage.FeedIdWithStopId(fromPlatformDescriptor.feed_id, fromPlatformDescriptor.stop_id), new GtfsStorage.InterpolatedTransfer(new GtfsStorage.FeedIdWithStopId(toPlatformDescriptor.feed_id, toPlatformDescriptor.stop_id), streetTime, skippedEdgesForTransfer));
        }
    }

    private void insertInterpolatedTransfer(Label label, GtfsStorage.PlatformDescriptor toPlatformDescriptor, HashMap<String, GtfsReader> readers, int[] skippedEdgesForTransfer) {
        GtfsReader toFeedReader = readers.get(toPlatformDescriptor.feed_id);
        List<Integer> transferEdgeIds = toFeedReader.insertTransferEdges(label.node.ptNode, (int) (label.streetTime / 1000L), toPlatformDescriptor);
        if (skippedEdgesForTransfer.length > 0) { // TODO: Elsewhere, we distinguish empty path ("at" a node) from no path
            assert isValidPath(skippedEdgesForTransfer);
            for (Integer transferEdgeId : transferEdgeIds) {
                gtfsStorage.getSkippedEdgesForTransfer().put(transferEdgeId, skippedEdgesForTransfer);
            }
        }
    }

    private int[] getSkippedEdgesForTransfer(Label label) {
        List<Label.Transition> transitions = Label.getTransitions(label.parent, true);
        int[] skippedEdgesForTransfer = transitions.stream().filter(t -> t.edge != null).mapToInt(t -> {
            Label.NodeId adjNode = t.label.node;
            EdgeIteratorState edgeIteratorState = getBaseGraph().getEdgeIteratorState(t.edge.getId(), adjNode.streetNode);
            return edgeIteratorState.getEdgeKey();
        }).toArray();
        return skippedEdgesForTransfer;
    }

    private boolean isValidPath(int[] edgeKeys) {
        List<EdgeIteratorState> edges = Arrays.stream(edgeKeys).mapToObj(i -> getBaseGraph().getEdgeIteratorStateForKey(i)).collect(Collectors.toList());
        for (int i = 1; i < edges.size(); i++) {
            if (edges.get(i).getBaseNode() != edges.get(i-1).getAdjNode())
                return false;
        }
        TripFromLabel tripFromLabel = new TripFromLabel(getBaseGraph(), getEncodingManager(), gtfsStorage, RealtimeFeed.empty(), getPathDetailsBuilderFactory(), 6.0);
        tripFromLabel.transferPath(edgeKeys, createWeighting(getProfile("foot"), new PMap()), 0L);
        return true;
    }

    private String routeIdOrNull(GtfsStorage.PlatformDescriptor platformDescriptor) {
        if (platformDescriptor instanceof GtfsStorage.RouteTypePlatform) {
            return null;
        } else {
            return ((GtfsStorage.RoutePlatform) platformDescriptor).route_id;
        }
    }

    @Override
    public void close() {
        getGtfsStorage().close();
        super.close();
    }

    public GtfsStorage getGtfsStorage() {
        return gtfsStorage;
    }

    public PtGraph getPtGraph() {
        return ptGraph;
    }
}
