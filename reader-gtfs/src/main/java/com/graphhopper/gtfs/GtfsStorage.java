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
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.IntIntCursor;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Fare;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.google.common.collect.HashMultimap;
import com.graphhopper.gtfs.analysis.Trips;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.index.LineIntIndex;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GtfsStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(GtfsStorage.class);

	/**
	 * Profile used to snap stops when {@code gtfs.stop_snap_profiles} is not configured. Riders reach
	 * transit on foot, and reader-gtfs already assumes a "foot" profile exists for transfer walking.
	 */
	public static final String DEFAULT_STOP_SNAP_PROFILE = "foot";

	private static final String STOP_SNAP_PROFILES_FILE = "stop_snap_profiles";

	static ObjectMapper ionMapper = new ObjectMapper();

	private LineIntIndex stopIndex;
	private PtGraph ptGraph;
	public Trips tripTransfers;

	public void setStopIndex(LineIntIndex stopIndex) {
		this.stopIndex = stopIndex;
	}

	public LineIntIndex getStopIndex() {
		return stopIndex;
	}

    public PtGraph getPtGraph() {
        return ptGraph;
    }

    public void setPtGraph(PtGraph ptGraph) {
        this.ptGraph = ptGraph;
    }

	public IntObjectHashMap<int[]> getSkippedEdgesForTransfer() {
		return skippedEdgesForTransfer;
	}

	public static class Validity implements Serializable {
		final BitSet validity;
		final ZoneId zoneId;
		final LocalDate start;

		public Validity(BitSet validity, ZoneId zoneId, LocalDate start) {
			this.validity = validity;
			this.zoneId = zoneId;
			this.start = start;
		}

		@Override
		public boolean equals(Object other) {
			if (! (other instanceof Validity)) return false;
			Validity v = (Validity) other;
			return validity.equals(v.validity) && zoneId.equals(v.zoneId) && start.equals(v.start);
		}

		@Override
		public int hashCode() {
			return Objects.hash(validity, zoneId, start);
		}
	}

	public static class FeedIdWithTimezone implements Serializable {
		public final String feedId;
		final ZoneId zoneId;

		FeedIdWithTimezone(String feedId, ZoneId zoneId) {
			this.feedId = feedId;
			this.zoneId = zoneId;
		}

		@Override
		public boolean equals(Object other) {
			if (! (other instanceof FeedIdWithTimezone)) return false;
			FeedIdWithTimezone v = (FeedIdWithTimezone) other;
			return feedId.equals(v.feedId) && zoneId.equals(v.zoneId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(feedId, zoneId);
		}

	}

	public static class FeedIdWithStopId implements Serializable {

		@JsonProperty("feed_id")
		public final String feedId;

		@JsonProperty("stop_id")
		public final String stopId;

		public FeedIdWithStopId(@JsonProperty("feed_id") String feedId,
								@JsonProperty("stop_id") String stopId) {
			this.feedId = feedId;
			this.stopId = stopId;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			FeedIdWithStopId that = (FeedIdWithStopId) o;
			return feedId.equals(that.feedId) &&
					stopId.equals(that.stopId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(feedId, stopId);
		}

		@Override
		public String toString() {
			return "FeedIdWithStopId{" +
					"feedId='" + feedId + '\'' +
					", stopId='" + stopId + '\'' +
					'}';
		}
	}

	private boolean isClosed = false;
	private Directory dir;
	private Set<String> gtfsFeedIds;
	private Map<String, GTFSFeed> gtfsFeeds = new HashMap<>();
	private Map<String, Map<String, Fare>> faresByFeed;
	private Map<FeedIdWithStopId, Integer> stationNodes;
	private IntObjectHashMap<int[]> skippedEdgesForTransfer;

	/**
	 * Street attachment of each stop, one mapping per stop-snap profile.
	 *
	 * A stop has a single stop node in the transit graph, but may attach to the street network at a
	 * different place for each mode: a rail platform is reached on foot via an adjacent footway, and
	 * by car via the nearest kerb. Which mapping a query consults is decided by its access/egress
	 * profile; see {@link GraphExplorer}.
	 *
	 * The first entry of {@link #stopSnapProfiles} is the primary profile. It alone decides stop node
	 * identity -- and therefore which co-located stops collapse onto one stop node -- so that the
	 * transit graph does not depend on which modes happen to be configured.
	 */
	private List<String> stopSnapProfiles = Collections.singletonList(DEFAULT_STOP_SNAP_PROFILE);
	private Map<String, IntIntHashMap> ptToStreetByProfile = new LinkedHashMap<>();
	private Map<String, IntIntHashMap> streetToPtByProfile = new LinkedHashMap<>();
	private final Set<String> warnedUnsnappedProfiles = ConcurrentHashMap.newKeySet();

	public enum EdgeType {
		HIGHWAY, ENTER_TIME_EXPANDED_NETWORK, LEAVE_TIME_EXPANDED_NETWORK, ENTER_PT, EXIT_PT, HOP, DWELL, BOARD, ALIGHT, OVERNIGHT, TRANSFER, WAIT, WAIT_ARRIVAL
    }

	public DB data;

	public GtfsStorage(Directory dir) {
		this.dir = dir;
	}

	boolean loadExisting() {
		File file = new File(dir.getLocation() + "/transit_schedule");
		if (!file.exists()) {
			return false;
		}
		this.data = DBMaker.newFileDB(file).transactionDisable().mmapFileEnable().readOnly().make();
		init();
        for (int i = 0; i < gtfsFeedIds.size(); i++) {
            String gtfsFeedId = "gtfs_" + i;
            File dbFile = new File(dir.getLocation() + "/" + gtfsFeedId);

            if (!dbFile.exists()) {
                throw new RuntimeException(String.format("The mapping of the gtfsFeeds in the transit_schedule DB does not reflect the files in %s. "
                                + "dbFile %s is missing.",
                        dir.getLocation(), dbFile.getName()));
            }

            GTFSFeed feed = new GTFSFeed(dbFile);
            this.gtfsFeeds.put(gtfsFeedId, feed);
        }
		resetStopSnapProfiles(readStopSnapProfiles());
		for (String profile : stopSnapProfiles) {
			ptToStreetByProfile.put(profile, deserializeIntoIntIntHashMap(ptToStreetFile(profile)));
			streetToPtByProfile.put(profile, deserializeIntoIntIntHashMap(streetToPtFile(profile)));
		}
		LOGGER.info("Loaded stop street attachments for profiles {} (primary: {})", stopSnapProfiles, getPrimaryStopSnapProfile());
		skippedEdgesForTransfer = deserializeIntoIntObjectHashMap("skipped_edges_for_transfer");
		try (InputStream is = Files.newInputStream(Paths.get(dir.getLocation() + "interpolated_transfers"))) {
			MappingIterator<JsonNode> objectMappingIterator = ionMapper.reader(JsonNode.class).readValues(is);
			objectMappingIterator.forEachRemaining(e -> {
				try {
					FeedIdWithStopId key = ionMapper.treeToValue(e.get(0), FeedIdWithStopId.class);
					for (JsonNode jsonNode : e.get(1)) {
						InterpolatedTransfer interpolatedTransfer = ionMapper.treeToValue(jsonNode, InterpolatedTransfer.class);
						interpolatedTransfers.put(key, interpolatedTransfer);
					}
				} catch (JsonProcessingException ex) {
					throw new RuntimeException(ex);
				}
			});
		} catch (IOException e) {
            throw new RuntimeException(e);
        }
        postInit();
		return true;
	}

	private static String ptToStreetFile(String profile) {
		return "pt_to_street_" + profile;
	}

	private static String streetToPtFile(String profile) {
		return "street_to_pt_" + profile;
	}

	/**
	 * Reads the profile list written by {@link #flush()}.
	 *
	 * A store written before per-profile stop snapping has a single "pt_to_street" instead, and its
	 * attachments were built by intersecting every configured profile -- not equivalent to any profile
	 * we could name here. Fail with an explicit message rather than a FileNotFoundException, because
	 * the usual cause is a router image rolled ahead of a graph rebuild.
	 */
	private List<String> readStopSnapProfiles() {
		File file = new File(dir.getLocation() + STOP_SNAP_PROFILES_FILE);
		if (!file.exists()) {
			throw new IllegalStateException("Graph store at " + dir.getLocation() + " predates per-profile stop"
					+ " snapping: '" + STOP_SNAP_PROFILES_FILE + "' is missing. This store must be rebuilt by a"
					+ " matching builder image; a newer router cannot read it.");
		}
		try {
			List<String> profiles = new ArrayList<>();
			for (String line : Files.readAllLines(file.toPath())) {
				String profile = line.trim();
				if (!profile.isEmpty()) {
					profiles.add(profile);
				}
			}
			if (profiles.isEmpty()) {
				throw new IllegalStateException("No stop snap profiles recorded in " + file);
			}
			return profiles;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private IntIntHashMap deserializeIntoIntIntHashMap(String filename) {
		try (FileInputStream in = new FileInputStream(dir.getLocation() + filename)) {
			ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(in));
			int size = ois.readInt();
			IntIntHashMap result = new IntIntHashMap();
			for (int i = 0; i < size; i++) {
				result.put(ois.readInt(), ois.readInt());
			}
			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public IntObjectHashMap<int[]> deserializeIntoIntObjectHashMap(String filename) {
		try (FileInputStream in = new FileInputStream(dir.getLocation() + filename)) {
			ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(in));
			int size = ois.readInt();
			IntObjectHashMap<int[]> result = new IntObjectHashMap<>(size);
			for (int i = 0; i < size; i++) {
				int key = ois.readInt();
				int n = ois.readInt();
				int[] ints = new int[n];
				for (int j = 0; j < n; j++) {
					ints[j] = ois.readInt();
				}
				result.put(key, ints);
			}
			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	void create() {
		this.dir.create();
		final File file = new File(dir.getLocation() + "/transit_schedule");
		try {
			Files.deleteIfExists(file.toPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.data = DBMaker.newFileDB(file).transactionDisable().mmapFileEnable().asyncWriteEnable().make();
		init();
	}

    private void init() {
		this.gtfsFeedIds = data.getHashSet("gtfsFeeds");
		this.stationNodes = data.getHashMap("stationNodes");
		this.skippedEdgesForTransfer = new IntObjectHashMap<>();
	}

	/**
	 * Declares which profiles stops will be snapped for, ordered, primary first. Must be called before
	 * the GTFS readers run.
	 */
	void setStopSnapProfiles(List<String> profiles) {
		resetStopSnapProfiles(profiles);
		for (String profile : stopSnapProfiles) {
			ptToStreetByProfile.put(profile, new IntIntHashMap());
			streetToPtByProfile.put(profile, new IntIntHashMap());
		}
	}

	/** Adopts the profile list and clears the attachment maps, ready to be populated per profile. */
	private void resetStopSnapProfiles(List<String> profiles) {
		if (profiles == null || profiles.isEmpty()) {
			throw new IllegalArgumentException("At least one stop snap profile is required");
		}
		this.stopSnapProfiles = Collections.unmodifiableList(new ArrayList<>(profiles));
		this.ptToStreetByProfile = new LinkedHashMap<>();
		this.streetToPtByProfile = new LinkedHashMap<>();
	}

	void loadGtfsFromZipFileOrDirectory(String id, File zipFileOrDirectory) {
		File dbFile = new File(dir.getLocation() + "/" + id);
		try {
			Files.deleteIfExists(dbFile.toPath());
			GTFSFeed feed = new GTFSFeed(dbFile);
			feed.loadFromFileAndLogErrors(zipFileOrDirectory);
			this.gtfsFeeds.put(id, feed);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.gtfsFeedIds.add(id);
	}

	// TODO: Refactor initialization
	public void postInit() {
		LocalDate latestStartDate = LocalDate.ofEpochDay(this.gtfsFeeds.values().stream().mapToLong(f -> f.getStartDate().toEpochDay()).max().getAsLong());
		LocalDate earliestEndDate = LocalDate.ofEpochDay(this.gtfsFeeds.values().stream().mapToLong(f -> f.getEndDate().toEpochDay()).min().getAsLong());
		LOGGER.info("Calendar range covered by all feeds: {} till {}", latestStartDate, earliestEndDate);
		faresByFeed = new HashMap<>();
		this.gtfsFeeds.forEach((feed_id, feed) -> faresByFeed.put(feed_id, feed.fares));
		tripTransfers = new Trips(this);
	}

	public void close() {
		if (!isClosed) {
			isClosed = true;
			data.close();
			for (GTFSFeed feed : gtfsFeeds.values()) {
				feed.close();
			}
		}
	}

	public Map<String, Map<String, Fare>> getFares() {
		return faresByFeed;
	}

	public List<String> getStopSnapProfiles() {
		return stopSnapProfiles;
	}

	/**
	 * Profile that decided stop node identity at import time. Used wherever the street attachment is
	 * needed but no access/egress mode is in play -- notably transfer walking, which is always on foot.
	 */
	public String getPrimaryStopSnapProfile() {
		return stopSnapProfiles.get(0);
	}

	/**
	 * Stop node -> street node for the given profile. Absent means this stop has no attachment usable
	 * by that mode, which callers represent as a street node of -1.
	 */
	public IntIntHashMap getPtToStreet(String profile) {
		return requireSnapProfile(ptToStreetByProfile, profile);
	}

	/**
	 * Street node -> stop node for the given profile. Note this direction is inherently one-to-one: if
	 * two stops share their nearest street node for a mode, only the first is discoverable from the
	 * street side for that mode.
	 */
	public IntIntHashMap getStreetToPt(String profile) {
		return requireSnapProfile(streetToPtByProfile, profile);
	}

	/**
	 * Maps a requested access/egress profile onto one that actually has attachments.
	 *
	 * A request may name any configured routing profile, but only the profiles in
	 * {@code gtfs.stop_snap_profiles} were snapped. Rather than fail such a query, fall back to the
	 * primary profile's attachments: those sit on the walking network, which a non-walk mode can still
	 * use wherever the attachment node is shared with a road. List the mode in
	 * {@code gtfs.stop_snap_profiles} and rebuild to give it attachments of its own.
	 */
	public String resolveStopSnapProfile(String requestedProfile) {
		if (ptToStreetByProfile.containsKey(requestedProfile)) {
			return requestedProfile;
		}
		if (warnedUnsnappedProfiles.add(requestedProfile)) {
			LOGGER.warn("Stops were not snapped for profile '{}' (snapped: {}); falling back to '{}'"
					+ " attachments for access/egress with that profile. Add it to gtfs.stop_snap_profiles"
					+ " and rebuild the graph to give it its own attachments.",
					requestedProfile, stopSnapProfiles, getPrimaryStopSnapProfile());
		}
		return getPrimaryStopSnapProfile();
	}

	private IntIntHashMap requireSnapProfile(Map<String, IntIntHashMap> maps, String profile) {
		IntIntHashMap map = maps.get(profile);
		if (map == null) {
			throw new IllegalArgumentException("No stop snapping was built for profile '" + profile + "'."
					+ " Configured stop snap profiles: " + stopSnapProfiles
					+ ". Add it to gtfs.stop_snap_profiles and rebuild the graph.");
		}
		return map;
	}

	public Map<String, GTFSFeed> getGtfsFeeds() {
		return Collections.unmodifiableMap(gtfsFeeds);
	}

	public Map<FeedIdWithStopId, Integer> getStationNodes() {
		return stationNodes;
	}

	public void flush() {
		try {
			Files.write(Paths.get(dir.getLocation() + STOP_SNAP_PROFILES_FILE), stopSnapProfiles);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		for (String profile : stopSnapProfiles) {
			serialize(ptToStreetFile(profile), ptToStreetByProfile.get(profile));
			serialize(streetToPtFile(profile), streetToPtByProfile.get(profile));
		}
		serialize("skipped_edges_for_transfer", skippedEdgesForTransfer);
		try (OutputStream os = Files.newOutputStream(Paths.get(dir.getLocation() + "interpolated_transfers"))) {
			SequenceWriter sequenceWriter = ionMapper.writer().writeValuesAsArray(os);
			for (Map.Entry<FeedIdWithStopId, Collection<InterpolatedTransfer>> e : interpolatedTransfers.asMap().entrySet()) {
				sequenceWriter.write(ionMapper.createArrayNode().addPOJO(e.getKey()).addPOJO(e.getValue()));
			}
			sequenceWriter.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void serializeTripTransfersMap(String filename, Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> data) {
		try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(dir.getLocation() + filename))))) {
			oos.writeInt(data.size());
			for (Map.Entry<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> entry : data.entrySet()) {
				oos.writeInt(entry.getKey().tripIdx);
				oos.writeInt(entry.getKey().stop_sequence);
				oos.writeInt(entry.getValue().size());
				for (Trips.TripAtStopTime tripAtStopTime : entry.getValue()) {
					oos.writeInt(tripAtStopTime.tripIdx);
					oos.writeInt(tripAtStopTime.stop_sequence);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> deserializeTripTransfersMap(String filename) {
		try (FileInputStream in = new FileInputStream(dir.getLocation() + filename)) {
			ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(in));
			int size = ois.readInt();
			Map<Trips.TripAtStopTime, Collection<Trips.TripAtStopTime>> result = new TreeMap<>();
			for (int i = 0; i < size; i++) {
				Trips.TripAtStopTime origin = new Trips.TripAtStopTime(ois.readInt(), ois.readInt());
				int nDestinations = ois.readInt();
				List<Trips.TripAtStopTime> destinations = new ArrayList<>(nDestinations);
				for (int j = 0; j < nDestinations; j++) {
					int tripIdxTo = ois.readInt();
					int stop_sequenceTo = ois.readInt();
					destinations.add(new Trips.TripAtStopTime(tripIdxTo, stop_sequenceTo));
				}
				result.put(origin, destinations);
			}
			return result;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void serialize(String filename, IntObjectHashMap<int[]> data) {
		try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(dir.getLocation() + filename))))) {
			oos.writeInt(data.size());
			for (IntObjectCursor<int[]> e : data) {
				oos.writeInt(e.key);
				oos.writeInt(e.value.length);
				for (int v : e.value) {
					oos.writeInt(v);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void serialize(String filename, IntIntHashMap data) {
		try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(dir.getLocation() + filename))))) {
			oos.writeInt(data.size());
			for (IntIntCursor e : data) {
				oos.writeInt(e.key);
				oos.writeInt(e.value);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public abstract static class PlatformDescriptor implements Serializable {
		public String feed_id;
		public String stop_id;

		public static PlatformDescriptor route(String feed_id, String stop_id, String route_id) {
			RoutePlatform routePlatform = new RoutePlatform();
			routePlatform.feed_id = feed_id;
			routePlatform.stop_id = stop_id;
			routePlatform.route_id = route_id;
			return routePlatform;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			PlatformDescriptor that = (PlatformDescriptor) o;
			return Objects.equals(feed_id, that.feed_id) &&
					Objects.equals(stop_id, that.stop_id);
		}

		@Override
		public int hashCode() {
			return Objects.hash(feed_id, stop_id);
		}

		public static RouteTypePlatform routeType(String feed_id, String stop_id, int route_type) {
			RouteTypePlatform routeTypePlatform = new RouteTypePlatform();
			routeTypePlatform.feed_id = feed_id;
			routeTypePlatform.stop_id = stop_id;
			routeTypePlatform.route_type = route_type;
			return routeTypePlatform;
		}

	}

	public static class RoutePlatform extends PlatformDescriptor {
		String route_id;

		@Override
		public String toString() {
			return "RoutePlatform{" +
					"feed_id='" + feed_id + '\'' +
					", stop_id='" + stop_id + '\'' +
					", route_id='" + route_id + '\'' +
					'}';
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			RoutePlatform that = (RoutePlatform) o;
			return route_id.equals(that.route_id);
		}

		@Override
		public int hashCode() {
			return Objects.hash(super.hashCode(), route_id);
		}
	}

	public static class RouteTypePlatform extends PlatformDescriptor {
		int route_type;

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			if (!super.equals(o)) return false;
			RouteTypePlatform that = (RouteTypePlatform) o;
			return route_type == that.route_type;
		}

		@Override
		public int hashCode() {
			return Objects.hash(super.hashCode(), route_type);
		}

		@Override
		public String toString() {
			return "RouteTypePlatform{" +
					"feed_id='" + feed_id + '\'' +
					", stop_id='" + stop_id + '\'' +
					", route_type=" + route_type +
					'}';
		}
	}

	public HashMultimap<FeedIdWithStopId, InterpolatedTransfer> interpolatedTransfers = HashMultimap.create();


	public static class InterpolatedTransfer {

		@JsonProperty("to_stop")
		public final FeedIdWithStopId toPlatformDescriptor;

		@JsonProperty("street_time")
		public final int streetTime;

		@JsonProperty("skipped_edges")
		public final int[] skippedEdgesForTransfer;

		public InterpolatedTransfer(@JsonProperty("to_stop") FeedIdWithStopId toPlatformDescriptor,
									@JsonProperty("street_time") int streetTime,
									@JsonProperty("skipped_edges") int[] skippedEdgesForTransfer) {
			this.toPlatformDescriptor = toPlatformDescriptor;
			this.streetTime = streetTime;
			this.skippedEdgesForTransfer = skippedEdgesForTransfer;
		}
	}

}
