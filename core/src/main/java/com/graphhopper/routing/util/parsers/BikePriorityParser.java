package com.graphhopper.routing.util.parsers;

import com.graphhopper.routing.ev.*;
import com.graphhopper.util.PMap;

public class BikePriorityParser extends BikeCommonPriorityParser {

    public BikePriorityParser(EncodedValueLookup lookup, PMap properties) {
        this(
                lookup.getDecimalEncodedValue(VehiclePriority.key(properties.getString("name", "bike"))),
                lookup.getDecimalEncodedValue(VehicleSpeed.key(properties.getString("name", "bike"))),
                lookup.getEnumEncodedValue(BikeNetwork.KEY, RouteNetwork.class)
        );
    }

    public BikePriorityParser(DecimalEncodedValue priorityEnc, DecimalEncodedValue speedEnc, EnumEncodedValue<RouteNetwork> bikeRouteEnc) {
        super(priorityEnc, speedEnc, bikeRouteEnc);

        // Comment out addPushingSection, to allow biking on paths without dismounting
        // addPushingSection("path");

        preferHighwayTags.add("service");
        preferHighwayTags.add("tertiary");
        preferHighwayTags.add("tertiary_link");
        preferHighwayTags.add("residential");
        preferHighwayTags.add("unclassified");

        setSpecificClassBicycle("touring");
    }
}
