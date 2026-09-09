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

import com.carrotsearch.hppc.IntIntHashMap;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.google.common.collect.HashMultimap;
import com.google.transit.realtime.GtfsRealtime;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.InMemConstructionIndex;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static com.conveyal.gtfs.model.Entity.Writer.convertToGtfsTime;
import static java.time.temporal.ChronoUnit.DAYS;

class GtfsReader {

    private final PtGraph ptGraph;
    private final PtGraphOut out;
    private final InMemConstructionIndex indexBuilder;
    private LocalDate startDate;
    private LocalDate endDate;

    interface PtGraphOut {

        int createEdge(int src, int dest, PtEdgeAttributes attrs);

        int createNode();
    }

    static class TripWithStopTimes {
        TripWithStopTimes(Trip trip, List<StopTime> stopTimes, BitSet validOnDay, Set<Integer> cancelledArrivals, Set<Integer> cancelledDepartures) {
            this.trip = trip;
            this.stopTimes = stopTimes;
            this.validOnDay = validOnDay;
            this.cancelledArrivals = cancelledArrivals;
            this.cancelledDeparture = cancelledDepartures;
        }

        Trip trip;
        List<StopTime> stopTimes;
        BitSet validOnDay;
        Set<Integer> cancelledArrivals;
        Set<Integer> cancelledDeparture;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GtfsReader.class);

    static final double FAR_SNAP_METERS = 100;

    private final LocationIndex streetNetworkIndex;
    private final GtfsStorage gtfsStorage;

    private final Transfers transfers;
    private final String id;
    private GTFSFeed feed;
    private final Map<String, Map<GtfsStorage.PlatformDescriptor, NavigableMap<Integer, Integer>>> departureTimelinesByStop = new HashMap<>();
    private final Map<String, Map<GtfsStorage.PlatformDescriptor, NavigableMap<Integer, Integer>>> arrivalTimelinesByStop = new HashMap<>();

    GtfsReader(String id, PtGraph ptGraph, PtGraphOut out, GtfsStorage gtfsStorage, LocationIndex streetNetworkIndex, Transfers transfers, InMemConstructionIndex indexBuilder) {
        this.id = id;
        this.gtfsStorage = gtfsStorage;
        this.streetNetworkIndex = streetNetworkIndex;
        this.feed = this.gtfsStorage.getGtfsFeeds().get(id);
        this.transfers = transfers;
        this.startDate = feed.getStartDate();
        this.endDate = feed.getEndDate();
        this.ptGraph = ptGraph;
        this.out = out;
        this.indexBuilder = indexBuilder;
    }

    /**
     * Attaches every stop to the street network once per mode.
     *
     * Each stop gets a single stop node in the transit graph, but one street attachment per profile in
     * {@code snapFiltersByProfile}, so a rail platform can be reached over the adjacent footway on foot
     * and over the nearest kerb by car. Previously a stop had one attachment, found with a filter every
     * profile had to accept at once; nothing near an airport platform satisfies that, which pushed the
     * attachment hundreds of metres away or dropped it entirely (DMP-16462).
     *
     * {@code primaryProfile} decides stop node identity, including which co-located stops collapse onto
     * a shared stop node. Identity must come from exactly one profile, otherwise the set of stop nodes
     * -- and hence the transit graph -- would depend on which modes happen to be configured.
     *
     * @param snapFiltersByProfile snap filter per profile
     * @param primaryProfile       key in {@code snapFiltersByProfile} that governs stop node identity
     */
    void connectStopsToStreetNetwork(Map<String, EdgeFilter> snapFiltersByProfile, String primaryProfile) {
        if (!snapFiltersByProfile.containsKey(primaryProfile)) {
            throw new IllegalArgumentException("Primary stop snap profile '" + primaryProfile
                    + "' is not among the snapped profiles " + snapFiltersByProfile.keySet());
        }
        int unattachedStops = 0;
        int shadowedAttachments = 0;
        int droppedSecondaryAttachments = 0;
        int farPrimarySnaps = 0;
        for (Stop stop : feed.stops.values()) {
            if (stop.location_type == 0) { // Only stops. Not interested in parent stations for now.
                Map<String, Integer> streetNodeByProfile = new LinkedHashMap<>();
                for (Map.Entry<String, EdgeFilter> e : snapFiltersByProfile.entrySet()) {
                    Snap snap = streetNetworkIndex.findClosest(stop.stop_lat, stop.stop_lon, e.getValue());
                    if (snap.isValid()) {
                        streetNodeByProfile.put(e.getKey(), snap.getClosestNode());
                        // log stops that snap to far-away nodes with the primary profile
                        if (e.getKey().equals(primaryProfile)) {
                            double metres = distanceToAttachmentNode(stop, snap);
                            if (metres > FAR_SNAP_METERS) {
                                farPrimarySnaps++;
                                LOGGER.warn("Feed {}: stop {} ({}) attaches {}m from its own coordinates on the '{}' profile.",
                                        id, stop.stop_id, stop.stop_name, (int) metres, primaryProfile);
                            }
                        }
                    }
                }

                Integer primaryStreetNode = streetNodeByProfile.get(primaryProfile);
                int stopNode = -1;
                if (primaryStreetNode != null) {
                    // Reuse the stop node of an earlier stop sharing this street node, so co-located stops
                    // (including across feeds) stay a single boarding point as they did before.
                    stopNode = gtfsStorage.getStreetToPt(primaryProfile).getOrDefault(primaryStreetNode, -1);
                }
                if (stopNode == -1) {
                    stopNode = out.createNode();
                    indexBuilder.addToAllTilesOnLine(stopNode, stop.stop_lat, stop.stop_lon, stop.stop_lat, stop.stop_lon);
                }
                if (streetNodeByProfile.isEmpty()) {
                    unattachedStops++;
                }

                for (Map.Entry<String, Integer> e : streetNodeByProfile.entrySet()) {
                    IntIntHashMap ptToStreet = gtfsStorage.getPtToStreet(e.getKey());
                    IntIntHashMap streetToPt = gtfsStorage.getStreetToPt(e.getKey());
                    // This direction is one-to-one too, the mirror image of the streetToPt case below: when
                    // the primary profile merges two originally-distinct stops onto one stopNode, they can
                    // still disagree on this profile's nearest street node. Whichever one got here first is
                    // kept -- alighting at this stop under this profile always lands on that node, never the
                    // other one's.
                    if (!ptToStreet.containsKey(stopNode)) {
                        ptToStreet.put(stopNode, e.getValue());
                    } else if (ptToStreet.get(stopNode) != e.getValue()) {
                        droppedSecondaryAttachments++;
                    }
                    // This direction is one-to-one. A hit here is usually two stops merged onto the same
                    // stop node, which is fine; only a hit resolving to a *different* stop node means this
                    // stop is not discoverable from the street side for this profile.
                    int claimedBy = streetToPt.getOrDefault(e.getValue(), -1);
                    if (claimedBy == -1) {
                        streetToPt.put(e.getValue(), stopNode);
                    } else if (claimedBy != stopNode) {
                        shadowedAttachments++;
                    }
                }
                gtfsStorage.getStationNodes().put(new GtfsStorage.FeedIdWithStopId(id, stop.stop_id), stopNode);
            }
        }
        if (unattachedStops > 0) {
            LOGGER.warn("Feed {}: {} stops could not be attached to the street network for any of the profiles"
                    + " {}, so they are reachable only by stop id.", id, unattachedStops, snapFiltersByProfile.keySet());
        }
        if (shadowedAttachments > 0) {
            LOGGER.info("Feed {}: {} stop/profile attachments landed on a street node already claimed by a"
                    + " different stop; those are not discoverable from the street side for that profile.", id, shadowedAttachments);
        }
        if (droppedSecondaryAttachments > 0) {
            LOGGER.info("Feed {}: {} secondary attachments were discarded because a stop merged onto an"
                    + " existing stop node already had a different one; alighting there will use the wrong"
                    + " attachment for that profile.", id, droppedSecondaryAttachments);
        }
        if (farPrimarySnaps > 0) {
            LOGGER.warn("Feed {}: {} stops attach to the street network more than {}m from their own"
                    + " coordinates on the '{}' profile; see the per-stop lines above.",
                    id, farPrimarySnaps, (int) FAR_SNAP_METERS, primaryProfile);
        }
    }

    /**
     * Distance from the stop to {@code snap.getClosestNode()}, the tower node we actually attach to.
     * That node is one end of the closest edge, and the edge's geometry carries both ends, so no
     * separate node access is needed.
     */
    private static double distanceToAttachmentNode(Stop stop, Snap snap) {
        PointList geometry = snap.getClosestEdge().fetchWayGeometry(FetchMode.ALL);
        int end = snap.getClosestNode() == snap.getClosestEdge().getBaseNode() ? 0 : geometry.size() - 1;
        return DistanceCalcEarth.DIST_EARTH.calcDist(stop.stop_lat, stop.stop_lon, geometry.getLat(end), geometry.getLon(end));
    }


    void buildPtNetwork() {
        createTrips();
        wireUpStops();
        insertGtfsTransfers();
    }

    private void createTrips() {
        HashMultimap<String, Trip> blockTrips = HashMultimap.create();
        for (Trip trip : feed.trips.values()) {
            if (trip.block_id != null) {
                blockTrips.put(trip.block_id, trip);
            } else {
                blockTrips.put("non-block-trip" + trip.trip_id, trip);
            }
        }
        blockTrips.asMap().values().forEach(unsortedTrips -> {
            List<TripWithStopTimes> trips = unsortedTrips.stream()
                    .map(trip -> {
                        Service service = feed.services.get(trip.service_id);
                        BitSet validOnDay = new BitSet((int) DAYS.between(startDate, endDate));
                        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                            if (service.activeOn(date)) {
                                validOnDay.set((int) DAYS.between(startDate, date));
                            }
                        }
                        ArrayList<StopTime> stopTimes = new ArrayList<>();
                        feed.getInterpolatedStopTimesForTrip(trip.trip_id).forEach(stopTimes::add);
                        return new TripWithStopTimes(trip, stopTimes, validOnDay, Collections.emptySet(), Collections.emptySet());
                    })
                    .sorted(Comparator.comparingInt(trip -> trip.stopTimes.iterator().next().departure_time))
                    .collect(Collectors.toList());
            if (trips.stream().map(trip -> feed.getFrequencies(trip.trip.trip_id)).distinct().count() != 1) {
                throw new RuntimeException("Found a block with frequency-based trips. Not supported.");
            }
            ZoneId zoneId = ZoneId.of(feed.agency.get(feed.routes.get(trips.iterator().next().trip.route_id).agency_id).agency_timezone);
            Collection<Frequency> frequencies = feed.getFrequencies(trips.iterator().next().trip.trip_id);
            if (frequencies.isEmpty()) {
                addTrips(zoneId, trips, 0, false);
            } else {
                for (Frequency frequency : frequencies) {
                    for (int time = frequency.start_time; time < frequency.end_time; time += frequency.headway_secs) {
                        addTrips(zoneId, trips, time, true);
                    }
                }
            }
        });
    }

    private void wireUpStops() {
        arrivalTimelinesByStop.forEach((stopId, arrivalTimelines) -> {
            Stop stop = feed.stops.get(stopId);
            arrivalTimelines.forEach(((platformDescriptor, arrivalTimeline) ->
                    wireUpArrivalTimeline(stop, arrivalTimeline, routeType(platformDescriptor), platformDescriptor)));
        });
        departureTimelinesByStop.forEach((stopId, departureTimelines) -> {
            Stop stop = feed.stops.get(stopId);
            departureTimelines.forEach(((platformDescriptor, departureTimeline) ->
                    wireUpDepartureTimeline(stop, departureTimeline, routeType(platformDescriptor), platformDescriptor)));
        });
    }

    private void insertGtfsTransfers() {
        departureTimelinesByStop.forEach((toStopId, departureTimelines) ->
                departureTimelines.forEach((this::insertInboundTransfers)));
    }

    private void insertInboundTransfers(GtfsStorage.PlatformDescriptor toPlatformDescriptor, NavigableMap<Integer, Integer> departureTimeline) {
        LOGGER.debug("Creating transfers to stop {}, platform {}", toPlatformDescriptor.stop_id, toPlatformDescriptor);
        List<Transfer> transfersToPlatform = transfers.getTransfersToStop(toPlatformDescriptor.stop_id, routeIdOrNull(toPlatformDescriptor));
        transfersToPlatform.forEach(transfer -> {
            GtfsStorage.FeedIdWithStopId stopId = new GtfsStorage.FeedIdWithStopId(id, transfer.from_stop_id);
            Integer stationNode = gtfsStorage.getStationNodes().get(stopId);
            if (stationNode != null) {
                for (PtGraph.PtEdge ptEdge : ptGraph.backEdgesAround(stationNode)) {
                    if (ptEdge.getType() == GtfsStorage.EdgeType.EXIT_PT) {
                        GtfsStorage.PlatformDescriptor fromPlatformDescriptor = ptEdge.getAttrs().platformDescriptor;
                        if (fromPlatformDescriptor.stop_id.equals(transfer.from_stop_id) &&
                                (transfer.from_route_id == null && fromPlatformDescriptor instanceof GtfsStorage.RouteTypePlatform || transfer.from_route_id != null && GtfsStorage.PlatformDescriptor.route(id, transfer.from_stop_id, transfer.from_route_id).equals(fromPlatformDescriptor))) {
                            LOGGER.debug("  Creating transfers from stop {}, platform {}", transfer.from_stop_id, fromPlatformDescriptor);
                            insertTransferEdges(ptEdge.getAdjNode(), transfer.min_transfer_time, departureTimeline, toPlatformDescriptor);
                        }
                    }
                }
            } else {
                Stop stop = gtfsStorage.getGtfsFeeds().get(stopId.feedId).stops.get(stopId.stopId);
                LOGGER.warn("Stop {} has no station node", stopId);
            }
        });
    }

    public ArrayList<Integer> insertTransferEdges(int arrivalPlatformNode, int minTransferTime, GtfsStorage.PlatformDescriptor departurePlatform) {
        return insertTransferEdges(arrivalPlatformNode, minTransferTime, departureTimelinesByStop.get(departurePlatform.stop_id).get(departurePlatform), departurePlatform);
    }

    private ArrayList<Integer> insertTransferEdges(int arrivalPlatformNode, int minTransferTime, NavigableMap<Integer, Integer> departureTimeline, GtfsStorage.PlatformDescriptor departurePlatform) {
        ArrayList<Integer> result = new ArrayList<>();
        for (PtGraph.PtEdge e : ptGraph.backEdgesAround(arrivalPlatformNode)) {
            if (e.getType() == GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK) {
                int arrivalTime = e.getTime();
                SortedMap<Integer, Integer> tailSet = departureTimeline.tailMap(arrivalTime + minTransferTime);
                if (!tailSet.isEmpty()) {
                    int id = out.createEdge(e.getAdjNode(), tailSet.get(tailSet.firstKey()), new PtEdgeAttributes(GtfsStorage.EdgeType.TRANSFER, tailSet.firstKey() - arrivalTime, null, routeType(departurePlatform), null, 0, -1, null, departurePlatform));
                    result.add(id);
                }
            }
        }
        return result;
    }

    void wireUpAdditionalDeparturesAndArrivals(ZoneId zoneId) {
        departureTimelinesByStop.forEach((stopId, departureTimelines) -> {
            Stop stop = feed.stops.get(stopId);
            departureTimelines.forEach(((platformDescriptor, timeline) ->
                    wireUpOrPatchDepartureTimeline(zoneId, stop, timeline, platformDescriptor)));
        });
        arrivalTimelinesByStop.forEach((stopId, arrivalTimelines) -> {
            Stop stop = feed.stops.get(stopId);
            arrivalTimelines.forEach(((platformDescriptor, timeline) ->
                    wireUpOrPatchArrivalTimeline(zoneId, stop, routeIdOrNull(platformDescriptor), timeline, platformDescriptor)));
        });
    }

    private void addTrips(ZoneId zoneId, List<TripWithStopTimes> trips, int time, boolean frequencyBased) {
        List<TripWithStopTimeAndArrivalNode> arrivalNodes = new ArrayList<>();
        for (TripWithStopTimes trip : trips) {
            addTrip(zoneId, time, arrivalNodes, trip, getTripDescriptor(time, frequencyBased, trip));
        }
    }

    private static GtfsRealtime.TripDescriptor getTripDescriptor(int time, boolean frequencyBased, TripWithStopTimes trip) {
        GtfsRealtime.TripDescriptor.Builder tripDescriptor = GtfsRealtime.TripDescriptor.newBuilder()
                .setTripId(trip.trip.trip_id)
                .setRouteId(trip.trip.route_id);
        if (frequencyBased) {
            tripDescriptor = tripDescriptor.setStartTime(convertToGtfsTime(time));
        }
        return tripDescriptor.build();
    }

    private static class TripWithStopTimeAndArrivalNode {
        TripWithStopTimes tripWithStopTimes;
        int arrivalNode;
        int arrivalTime;
    }

    void addTrip(ZoneId zoneId, int time, List<TripWithStopTimeAndArrivalNode> arrivalNodes, TripWithStopTimes trip, GtfsRealtime.TripDescriptor tripDescriptor) {
        StopTime prev = null;
        int arrivalNode = -1;
        int arrivalTime = -1;
        int departureNode = -1;
        for (StopTime stopTime : trip.stopTimes) {
            arrivalNode = out.createNode();
            arrivalTime = stopTime.arrival_time + time;
            if (prev != null) {
                out.createEdge(departureNode, arrivalNode, new PtEdgeAttributes(GtfsStorage.EdgeType.HOP, stopTime.arrival_time - prev.departure_time, null, -1, null, 0, stopTime.stop_sequence, null, null));
            }
            Route route = feed.routes.get(trip.trip.route_id);
            GtfsStorage.PlatformDescriptor platform;
            if (transfers.hasNoRouteSpecificDepartureTransferRules(stopTime.stop_id)) {
                platform = GtfsStorage.PlatformDescriptor.routeType(id, stopTime.stop_id, route.route_type);
            } else {
                platform = GtfsStorage.PlatformDescriptor.route(id, stopTime.stop_id, route.route_id);
            }
            Map<GtfsStorage.PlatformDescriptor, NavigableMap<Integer, Integer>> departureTimelines = departureTimelinesByStop.computeIfAbsent(stopTime.stop_id, s -> new HashMap<>());
            NavigableMap<Integer, Integer> departureTimeline = departureTimelines.computeIfAbsent(platform, s -> new TreeMap<>());
            int departureTimelineNode = departureTimeline.computeIfAbsent((stopTime.departure_time + time) % (24 * 60 * 60), t -> out.createNode());
            Map<GtfsStorage.PlatformDescriptor, NavigableMap<Integer, Integer>> arrivalTimelines = arrivalTimelinesByStop.computeIfAbsent(stopTime.stop_id, s -> new HashMap<>());
            NavigableMap<Integer, Integer> arrivalTimeline = arrivalTimelines.computeIfAbsent(platform, s -> new TreeMap<>());
            int arrivalTimelineNode = arrivalTimeline.computeIfAbsent((stopTime.arrival_time + time) % (24 * 60 * 60), t -> out.createNode());
            departureNode = out.createNode();
            int dayShift = stopTime.departure_time / (24 * 60 * 60);
            GtfsStorage.Validity validOn = new GtfsStorage.Validity(getValidOn(trip.validOnDay, dayShift), zoneId, startDate);
            out.createEdge(departureTimelineNode, departureNode, new PtEdgeAttributes(GtfsStorage.EdgeType.BOARD, 0, validOn, -1, null, 1, stopTime.stop_sequence, tripDescriptor, null));
            out.createEdge(arrivalNode, arrivalTimelineNode, new PtEdgeAttributes(GtfsStorage.EdgeType.ALIGHT, 0, validOn, -1, null, 0, stopTime.stop_sequence, tripDescriptor, null));
            out.createEdge(arrivalNode, departureNode, new PtEdgeAttributes(GtfsStorage.EdgeType.DWELL, stopTime.departure_time - stopTime.arrival_time, null, -1, null, 0, -1, null, null));

            if (prev == null) {
                insertInboundBlockTransfers(arrivalNodes, tripDescriptor, departureNode, stopTime.departure_time + time, stopTime, validOn, zoneId, platform);
            }
            prev = stopTime;
        }
        TripWithStopTimeAndArrivalNode tripWithStopTimeAndArrivalNode = new TripWithStopTimeAndArrivalNode();
        tripWithStopTimeAndArrivalNode.tripWithStopTimes = trip;
        tripWithStopTimeAndArrivalNode.arrivalNode = arrivalNode;
        tripWithStopTimeAndArrivalNode.arrivalTime = arrivalTime;
        arrivalNodes.add(tripWithStopTimeAndArrivalNode);
    }

    private void wireUpDepartureTimeline(Stop stop, NavigableMap<Integer, Integer> departureTimeline, int route_type, GtfsStorage.PlatformDescriptor platformDescriptor) {
        LOGGER.debug("Creating timeline at stop {} for departure platform {}", stop.stop_id, platformDescriptor);
        int platformEnterNode = out.createNode();
        int streetNode = gtfsStorage.getStationNodes().get(new GtfsStorage.FeedIdWithStopId(platformDescriptor.feed_id, platformDescriptor.stop_id));
        out.createEdge(streetNode, platformEnterNode, new PtEdgeAttributes(GtfsStorage.EdgeType.ENTER_PT, 0,null, route_type, null,0, -1, null, platformDescriptor));
        wireUpAndConnectTimeline(platformEnterNode, departureTimeline, GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK, GtfsStorage.EdgeType.WAIT);
    }

    private void wireUpArrivalTimeline(Stop stop, NavigableMap<Integer, Integer> arrivalTimeline, int route_type, GtfsStorage.PlatformDescriptor platformDescriptor) {
        LOGGER.debug("Creating timeline at stop {} for arrival platform {}", stop.stop_id, platformDescriptor);
        int platformExitNode = out.createNode();
        int streetNode = gtfsStorage.getStationNodes().get(new GtfsStorage.FeedIdWithStopId(platformDescriptor.feed_id, platformDescriptor.stop_id));
        out.createEdge(platformExitNode, streetNode, new PtEdgeAttributes(GtfsStorage.EdgeType.EXIT_PT, 0, null, route_type, null, 0, -1, null, platformDescriptor));
        wireUpAndConnectTimeline(platformExitNode, arrivalTimeline, GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK, GtfsStorage.EdgeType.WAIT_ARRIVAL);
    }

    private void wireUpOrPatchDepartureTimeline(ZoneId zoneId, Stop stop, NavigableMap<Integer, Integer> timeline, GtfsStorage.PlatformDescriptor route) {
        int platformEnterNode = findPlatformEnter(route);
        if (platformEnterNode != -1) {
            patchDepartureTimeline(zoneId, timeline, platformEnterNode);
        } else {
            wireUpDepartureTimeline(stop, timeline, 0, route);
        }
    }

    private void wireUpOrPatchArrivalTimeline(ZoneId zoneId, Stop stop, String routeId, NavigableMap<Integer, Integer> timeline, GtfsStorage.PlatformDescriptor route) {
        int platformExitNode = findPlatformExit(route);
        if (platformExitNode != -1) {
            patchArrivalTimeline(zoneId, timeline, platformExitNode);
        } else {
            wireUpArrivalTimeline(stop, timeline, 0, route);
        }
        final Optional<Transfer> withinStationTransfer = transfers.getTransfersFromStop(stop.stop_id, routeId).stream().filter(t -> t.from_stop_id.equals(stop.stop_id)).findAny();
        if (!withinStationTransfer.isPresent()) {
            insertOutboundTransfers(stop.stop_id, null, 0, timeline);
        }
        transfers.getTransfersFromStop(stop.stop_id, routeId).forEach(transfer ->
                insertOutboundTransfers(transfer.from_stop_id, transfer.from_route_id, transfer.min_transfer_time, timeline));
    }

    private void patchDepartureTimeline(ZoneId zoneId, NavigableMap<Integer, Integer> timeline, int platformNode) {
        NavigableMap<Integer, Integer> staticDepartureTimelineForRoute = findDepartureTimelineForPlatform(platformNode);
        timeline.forEach((time, node) -> {
            SortedMap<Integer, Integer> headMap = staticDepartureTimelineForRoute.headMap(time);
            if (!headMap.isEmpty()) {
                out.createEdge(headMap.get(headMap.lastKey()), node, new PtEdgeAttributes(GtfsStorage.EdgeType.WAIT, time - headMap.lastKey(), null, -1, null, 0, -1, null, null));
            }
            SortedMap<Integer, Integer> tailMap = staticDepartureTimelineForRoute.tailMap(time);
            if (!tailMap.isEmpty()) {
                out.createEdge(node, tailMap.get(tailMap.firstKey()), new PtEdgeAttributes(GtfsStorage.EdgeType.WAIT, tailMap.firstKey() - time, null, -1, null, 0, -1, null, null));
            }
            out.createEdge(platformNode, node, new PtEdgeAttributes(GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK, time, null, -1, new GtfsStorage.FeedIdWithTimezone(id, zoneId), 0, -1, null, null));
        });
    }

    private void patchArrivalTimeline(ZoneId zoneId, NavigableMap<Integer, Integer> timeline, int platformExitNode) {
        timeline.forEach((time, node) -> out.createEdge(node, platformExitNode, new PtEdgeAttributes(GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK, time, null, -1, new GtfsStorage.FeedIdWithTimezone(id, zoneId), 0, -1, null, null)));
    }

    private NavigableMap<Integer, Integer> findDepartureTimelineForPlatform(int platformEnterNode) {
        TreeMap<Integer, Integer> result = new TreeMap<>();
        if (platformEnterNode == -1) {
            return result;
        }
        for (PtGraph.PtEdge edge : ptGraph.edgesAround(platformEnterNode)) {
            if (edge.getType() == GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK) {
                result.put(edge.getTime(), edge.getAdjNode());
            }
        }
        return result;
    }

    private int findPlatformEnter(GtfsStorage.PlatformDescriptor platformDescriptor) {
        int stopNode = gtfsStorage.getStationNodes().get(new GtfsStorage.FeedIdWithStopId(platformDescriptor.feed_id, platformDescriptor.stop_id));
        for (PtGraph.PtEdge ptEdge : ptGraph.edgesAround(stopNode)) {
            if (ptEdge.getType() == GtfsStorage.EdgeType.ENTER_PT && platformDescriptor.equals(ptEdge.getAttrs().platformDescriptor)) {
                return ptEdge.getAdjNode();
            }
        }
        return -1;
    }

    private int findPlatformExit(GtfsStorage.PlatformDescriptor platformDescriptor) {
        int stopNode = gtfsStorage.getStationNodes().get(new GtfsStorage.FeedIdWithStopId(platformDescriptor.feed_id, platformDescriptor.stop_id));
        for (PtGraph.PtEdge ptEdge : ptGraph.backEdgesAround(stopNode)) {
            if (ptEdge.getType() == GtfsStorage.EdgeType.EXIT_PT && platformDescriptor.equals(ptEdge.getAttrs().platformDescriptor)) {
                return ptEdge.getAdjNode();
            }
        }
        return -1;
    }


    int addDelayedBoardEdge(ZoneId zoneId, GtfsRealtime.TripDescriptor tripDescriptor, int stopSequence, int departureTime, int departureNode, BitSet validOnDay) {
        Trip trip = feed.trips.get(tripDescriptor.getTripId());
        StopTime stopTime = feed.stop_times.get(new Fun.Tuple2(tripDescriptor.getTripId(), stopSequence));
        Map<GtfsStorage.PlatformDescriptor, NavigableMap<Integer, Integer>> departureTimelineNodesByRoute = departureTimelinesByStop.computeIfAbsent(stopTime.stop_id, s -> new HashMap<>());
        NavigableMap<Integer, Integer> departureTimelineNodes = departureTimelineNodesByRoute.computeIfAbsent(GtfsStorage.PlatformDescriptor.route(id, stopTime.stop_id, trip.route_id), s -> new TreeMap<>());
        int departureTimelineNode = departureTimelineNodes.computeIfAbsent(departureTime % (24 * 60 * 60), t -> ptGraph.createNode());

        int dayShift = departureTime / (24 * 60 * 60);
        GtfsStorage.Validity validOn = new GtfsStorage.Validity(getValidOn(validOnDay, dayShift), zoneId, startDate);
        return out.createEdge(departureTimelineNode, departureNode, new PtEdgeAttributes(GtfsStorage.EdgeType.BOARD, 0, validOn, -1, null, 1, stopSequence, tripDescriptor, null));
    }

    private void wireUpAndConnectTimeline(int platformNode, NavigableMap<Integer, Integer> timeNodes, GtfsStorage.EdgeType timeExpandedNetworkEdgeType, GtfsStorage.EdgeType waitEdgeType) {
        ZoneId zoneId = ZoneId.of(feed.agency.values().iterator().next().agency_timezone);
        int time = 0;
        int prev = -1;
        for (Map.Entry<Integer, Integer> e : timeNodes.descendingMap().entrySet()) {
            if (timeExpandedNetworkEdgeType == GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK) {
                out.createEdge(e.getValue(), platformNode, new PtEdgeAttributes(timeExpandedNetworkEdgeType, e.getKey(), null, -1, new GtfsStorage.FeedIdWithTimezone(id, zoneId), 0, -1, null, null));
            } else if (timeExpandedNetworkEdgeType == GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK) {
                out.createEdge(platformNode, e.getValue(), new PtEdgeAttributes(timeExpandedNetworkEdgeType, e.getKey(), null, -1, new GtfsStorage.FeedIdWithTimezone(id, zoneId), 0, -1, null, null));
            } else {
                throw new RuntimeException();
            }
            if (prev != -1) {
                out.createEdge(e.getValue(), prev, new PtEdgeAttributes(waitEdgeType, time - e.getKey(), null, -1, null, 0, -1, null, null));
            }
            time = e.getKey();
            prev = e.getValue();
        }
        if (!timeNodes.isEmpty()) {
            int rolloverTime = 24 * 60 * 60 - timeNodes.lastKey() + timeNodes.firstKey();
            out.createEdge(timeNodes.get(timeNodes.lastKey()), timeNodes.get(timeNodes.firstKey()), new PtEdgeAttributes(GtfsStorage.EdgeType.OVERNIGHT, rolloverTime, null, -1, null, 0, -1, null, null));
        }
    }

    private void insertInboundBlockTransfers(List<TripWithStopTimeAndArrivalNode> arrivalNodes, GtfsRealtime.TripDescriptor tripDescriptor, int departureNode, int departureTime, StopTime stopTime, GtfsStorage.Validity validOn, ZoneId zoneId, GtfsStorage.PlatformDescriptor platform) {
        BitSet accumulatorValidity = new BitSet(validOn.validity.size());
        accumulatorValidity.or(validOn.validity);
        ListIterator<TripWithStopTimeAndArrivalNode> li = arrivalNodes.listIterator(arrivalNodes.size());
        while (li.hasPrevious() && accumulatorValidity.cardinality() > 0) {
            TripWithStopTimeAndArrivalNode lastTrip = li.previous();
            int dwellTime = departureTime - lastTrip.arrivalTime;
            if (dwellTime >= 0 && accumulatorValidity.intersects(lastTrip.tripWithStopTimes.validOnDay)) {
                BitSet blockTransferValidity = new BitSet(validOn.validity.size());
                blockTransferValidity.or(validOn.validity);
                blockTransferValidity.and(accumulatorValidity);
                GtfsStorage.Validity blockTransferValidOn = new GtfsStorage.Validity(blockTransferValidity, zoneId, startDate);
                int node = ptGraph.createNode();
                out.createEdge(lastTrip.arrivalNode, node, new PtEdgeAttributes(GtfsStorage.EdgeType.TRANSFER, dwellTime, null, -1, null, 0, -1, null, platform));
                out.createEdge(node, departureNode, new PtEdgeAttributes(GtfsStorage.EdgeType.BOARD, 0, blockTransferValidOn, -1, null, 0, stopTime.stop_sequence, tripDescriptor, null));
                accumulatorValidity.andNot(lastTrip.tripWithStopTimes.validOnDay);
            }
        }
    }

    private void insertOutboundTransfers(String toStopId, String toRouteId, int minimumTransferTime, NavigableMap<Integer, Integer> fromStopTimelineNodes) {
        int stationNode = gtfsStorage.getStationNodes().get(new GtfsStorage.FeedIdWithStopId(id, toStopId));
        for (PtGraph.PtEdge ptEdge : ptGraph.edgesAround(stationNode)) {
            GtfsStorage.PlatformDescriptor toPlatform = ptEdge.getAttrs().platformDescriptor;
            if (toRouteId == null || toPlatform instanceof GtfsStorage.RouteTypePlatform || GtfsStorage.PlatformDescriptor.route(id, toStopId, toRouteId).equals(toPlatform)) {
                fromStopTimelineNodes.forEach((time, e) -> {
                    for (PtGraph.PtEdge j : ptGraph.edgesAround(ptEdge.getAdjNode())) {
                        if (j.getType() == GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK) {
                            int departureTime = j.getTime();
                            if (departureTime < time + minimumTransferTime) {
                                continue;
                            }
                            out.createEdge(e, j.getAdjNode(), new PtEdgeAttributes(GtfsStorage.EdgeType.TRANSFER, departureTime - time, null, -1, null, 0, -1, null, toPlatform));
                            break;
                        }
                    }
                });
            }
        }
    }

    private BitSet getValidOn(BitSet validOnDay, int dayShift) {
        if (dayShift == 0) {
            return validOnDay;
        } else {
            BitSet bitSet = new BitSet(validOnDay.length() + 1);
            for (int i = 0; i < validOnDay.length(); i++) {
                if (validOnDay.get(i)) {
                    bitSet.set(i + 1);
                }
            }
            return bitSet;
        }
    }

    private int routeType(GtfsStorage.PlatformDescriptor platformDescriptor) {
        if (platformDescriptor instanceof GtfsStorage.RouteTypePlatform) {
            return ((GtfsStorage.RouteTypePlatform) platformDescriptor).route_type;
        } else {
            return feed.routes.get(((GtfsStorage.RoutePlatform) platformDescriptor).route_id).route_type;
        }
    }

    private String routeIdOrNull(GtfsStorage.PlatformDescriptor platformDescriptor) {
        if (platformDescriptor instanceof GtfsStorage.RouteTypePlatform) {
            return null;
        } else {
            return ((GtfsStorage.RoutePlatform) platformDescriptor).route_id;
        }
    }

}
