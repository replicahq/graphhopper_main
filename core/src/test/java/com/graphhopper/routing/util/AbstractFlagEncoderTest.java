/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper licenses this file to you under the Apache License, 
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
package com.graphhopper.routing.util;

import com.graphhopper.reader.OSMRelation;
import com.graphhopper.reader.OSMWay;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author Peter Karich
 */
public class AbstractFlagEncoderTest
{
    @Test
    public void testAcceptsCar()
    {

        CarFlagEncoder encoder = new CarFlagEncoder(5, 5, 0);
        assertEquals(40, encoder.parseSpeed("40 km/h"), 1e-3);
        assertEquals(40, encoder.parseSpeed("40km/h"), 1e-3);
        assertEquals(40, encoder.parseSpeed("40kmh"), 1e-3);
        assertEquals(64.374, encoder.parseSpeed("40mph"), 1e-3);
        assertEquals(48.28, encoder.parseSpeed("30 mph"), 1e-3);
        assertEquals(-1, encoder.parseSpeed(null), 1e-3);
        assertEquals(18.52, encoder.parseSpeed("10 knots"), 1e-3);
        assertEquals(19, encoder.parseSpeed("19 kph"), 1e-3);
        assertEquals(19, encoder.parseSpeed("19kph"), 1e-3);

        assertEquals(50, encoder.parseSpeed("RO:urban"), 1e-3);

        assertEquals(80, encoder.parseSpeed("RU:rural"), 1e-3);

        assertEquals(6, encoder.parseSpeed("walk"), 1e-3);
    }

    @Test
    public void testParseDuration()
    {
        assertEquals(10, AbstractFlagEncoder.parseDuration("00:10"));
        assertEquals(70, AbstractFlagEncoder.parseDuration("01:10"));
        assertEquals(0, AbstractFlagEncoder.parseDuration("oh"));
        assertEquals(0, AbstractFlagEncoder.parseDuration(null));
        assertEquals(60 * 20, AbstractFlagEncoder.parseDuration("20:00"));
        assertEquals(60 * 20, AbstractFlagEncoder.parseDuration("0:20:00"));
        assertEquals(60 * 24 * 2 + 60 * 20 + 2, AbstractFlagEncoder.parseDuration("02:20:02"));
    }
}
