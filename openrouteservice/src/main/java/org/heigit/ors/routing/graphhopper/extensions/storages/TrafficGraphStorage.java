/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.storages;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphExtension;
import com.graphhopper.storage.RAMDirectory;
import org.heigit.ors.routing.graphhopper.extensions.reader.traffic.TrafficEnums;

/**
 * Graph storage class for the Border Restriction routing
 */
public class TrafficGraphStorage implements GraphExtension {

    public enum Property {ROAD_TYPE}

    public enum Direction {FROM_TRAFFIC, TO_TRAFFIC}

    public enum Weekday {MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY}

    /* pointer for road type */
    private static final byte LOCATION_ROAD_TYPE = 0;         // byte location of road type
    private static final int LOCATION_FORWARD_TRAFFIC_PRIORITY = 0;         // byte location of the from traffic link id
    private static final int LOCATION_FORWARD_TRAFFIC = 1;         // byte location of the from traffic link id
    private static final int LOCATION_BACKWARD_TRAFFIC_PRIORITY = 15;         // byte location of the to traffic link id
    private static final int LOCATION_BACKWARD_TRAFFIC = 16;         // byte location of the to traffic link id

    // road types
    public static final byte IGNORE = 0; // For unimportant edges that are below relevant street types (residential etc.)
    public static final byte MOTORWAY = 1;
    public static final byte MOTORWAY_LINK = 2;
    public static final byte MOTORROAD = 3;
    public static final byte TRUNK = 4;
    public static final byte TRUNK_LINK = 5;
    public static final byte PRIMARY = 6;
    public static final byte PRIMARY_LINK = 7;
    public static final byte SECONDARY = 8;
    public static final byte SECONDARY_LINK = 9;
    public static final byte TERTIARY = 10;
    public static final byte TERTIARY_LINK = 11;
    public static final byte RESIDENTIAL = 12; // Really really seldom this is needed!
    public static final byte UNCLASSIFIED = 13;

    public static final int PROPERTY_BYTE_COUNT = 1;
    public static final int LINK_LOOKUP_BYTE_COUNT = 29; // 2 bytes per day. 7 days per Week. One week forward. One week backwards. + 1 byte per week for value priority = 2 * 7 * 2 + 2 = 30
    public static final int WEEKLY_TRAFFIC_PATTERNS_BYTE_COUNT = 13; // The max pattern id fits in a short = 2 bytes * 7 days
    public static final int DAILY_TRAFFIC_PATTERNS_BYTE_COUNT = 95; // The pattern value is transferred to mph to allow byte storage. 1 byte * 4 (15min per Hour) * 24 hours

    private DataAccess orsEdgesProperties;
    private DataAccess orsEdgesTrafficLinkLookup;
    private DataAccess orsLinkTrafficSpeedPatternLookup;
    private DataAccess orsSpeedPatternLookup;

    private int edgePropertyEntryBytes;
    private int edgeLinkLookupEntryBytes;
    private int linkPatternEntryBytes;
    private int patternEntryBytes;
    private int edgesCount; // number of edges with custom values
    private int linkCount; // number of traffic links
    private int patternCount; // number of traffic patterns
    private byte[] propertyValue;
    private byte[] speedValue;
    private byte[] priorityValue;

    public TrafficGraphStorage() {
        int edgeEntryIndex = 0;
        edgePropertyEntryBytes = edgeEntryIndex + PROPERTY_BYTE_COUNT;
        edgeLinkLookupEntryBytes = edgeEntryIndex + LINK_LOOKUP_BYTE_COUNT;
        linkPatternEntryBytes = edgeEntryIndex + WEEKLY_TRAFFIC_PATTERNS_BYTE_COUNT;
        patternEntryBytes = edgeEntryIndex + DAILY_TRAFFIC_PATTERNS_BYTE_COUNT;
        propertyValue = new byte[1];
        speedValue = new byte[1];
        priorityValue = new byte[1];
        edgesCount = 0;
    }

    public static byte getWayTypeFromString(String highway) {
        switch (highway.toLowerCase()) {
            case "motorway":
                return TrafficGraphStorage.MOTORWAY;
            case "motorway_link":
                return TrafficGraphStorage.MOTORWAY_LINK;
            case "motorroad":
                return TrafficGraphStorage.MOTORROAD;
            case "trunk":
                return TrafficGraphStorage.TRUNK;
            case "trunk_link":
                return TrafficGraphStorage.TRUNK_LINK;
            case "primary":
                return TrafficGraphStorage.PRIMARY;
            case "primary_link":
                return TrafficGraphStorage.PRIMARY_LINK;
            case "secondary":
                return TrafficGraphStorage.SECONDARY;
            case "secondary_link":
                return TrafficGraphStorage.SECONDARY_LINK;
            case "tertiary":
                return TrafficGraphStorage.TERTIARY;
            case "tertiary_link":
                return TrafficGraphStorage.TERTIARY_LINK;
            case "residential":
                return TrafficGraphStorage.RESIDENTIAL;
            case "unclassified":
                return TrafficGraphStorage.UNCLASSIFIED;
            default:
                return TrafficGraphStorage.IGNORE;
        }
    }

    /**
     * Set values to the edge based on custom properties<br/><br/>
     * <p>
     * This method takes the internal ID of the edge and adds the desired value e.g. the way type.
     *
     * @param edgeId Internal ID of the graph edge.
     * @param prop   Property indicating the location to store the value at.
     * @param value  Value containing the information that should be places on the index of the prop variable.
     **/
    public void setOrsRoadProperties(int edgeId, Property prop, short value) {
        edgesCount++;
        ensureEdgesPropertyIndex(edgeId);
        long edgePointer = (long) edgeId * edgePropertyEntryBytes;
        if (prop == Property.ROAD_TYPE)
            propertyValue[0] = (byte) value;
        orsEdgesProperties.setBytes(edgePointer + LOCATION_ROAD_TYPE, propertyValue, 1);
    }

    /**
     * Store the linkID <-> patternId matches for all weekdays (Monday - Sunday).</-><br/><br/>
     * <p>
     * This method takes the ID of the traffic edge and adds the weekday specific pattern Id to the lookup.
     *
     * @param baseNode  Bade id to matchc the pattern on.
     * @param patternId Id of the traffic pattern.
     * @param weekday   Enum value for the weekday the traffic pattern Id refers to.
     **/
    public void setEdgeIdTrafficPatternLookup(int edgeId, int baseNode, int adjNode, int patternId, TrafficEnums.WeekDay weekday, double priority) {
        priority = priority > 255 ? 255 : priority;
        patternId = patternId > 65535 ? 0 : patternId;

        int lastPriority = getEdgeIdTrafficPatternPriority(edgeId, baseNode, adjNode);

        if (patternId <= 0)
            return;

        if (getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, weekday) > 0 && lastPriority > priority)
            return;
        long edgePointer;


        edgePointer = (long) edgeId * edgeLinkLookupEntryBytes;

        ensureEdgesTrafficLinkLookupIndex(edgeId);

        priorityValue[0] = (byte) priority;

        // TODO RAD
        // add entry
        int test1 = 0;
        int test2 = 0;
        // TODO RAD
        if (baseNode < adjNode) {
            orsEdgesTrafficLinkLookup.setBytes(edgePointer + LOCATION_FORWARD_TRAFFIC_PRIORITY, priorityValue, 1);
            orsEdgesTrafficLinkLookup.setShort(edgePointer + LOCATION_FORWARD_TRAFFIC + weekday.getCanonical(), (short) patternId);
            test1 = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, weekday);
            test2 = getEdgeIdTrafficPatternPriority(edgeId, baseNode, adjNode);
        } else {
            int test123 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.MONDAY);
            orsEdgesTrafficLinkLookup.setBytes(edgePointer + LOCATION_BACKWARD_TRAFFIC_PRIORITY, priorityValue, 1);
            orsEdgesTrafficLinkLookup.setShort(edgePointer + LOCATION_BACKWARD_TRAFFIC + weekday.getCanonical(), (short) patternId);
            test1 = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, weekday);
            test2 = getEdgeIdTrafficPatternPriority(edgeId, baseNode, adjNode);
        }
        // TODO RAD
//        assert test1 == patternId;
//        assert test2 == (int) priority;
        int test121 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.MONDAY);
        int test122 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.TUESDAY);
        int test123 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.WEDNESDAY);
        int test124 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.THURSDAY);
        int test125 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.FRIDAY);
        int test126 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.SATURDAY);
        int test127 = getEdgeIdTrafficPatternLookup(16077, 12942, 12941, TrafficEnums.WeekDay.SUNDAY);


        int test121_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.MONDAY);
        int test122_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.TUESDAY);
        int test123_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.WEDNESDAY);
        int test124_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.THURSDAY);
        int test125_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.FRIDAY);
        int test126_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.SATURDAY);
        int test127_ = getEdgeIdTrafficPatternLookup(edgeId, baseNode, adjNode, TrafficEnums.WeekDay.SUNDAY);
        if (test121 == 3808 || test122 == 9712 || test123 == 13544) {
            System.out.println("");
        }
        // TODO RAD
    }

    /**
     * Store the traffic pattern for each 15 minutes for 24 hours.</-><br/><br/>
     * <p>
     * This method takes the pattern Id and adds the speed value to the right quarter to the right hour.
     *
     * @param patternId     Id of the traffic pattern.
     * @param patternValues Speed values in mph or kph.
     **/
    public void setTrafficPatterns(int patternId, short[] patternValues) {
        patternCount++;
        ensureSpeedPatternLookupIndex(patternId);
        // add entry
        int minuteCounter = 0;
        for (int i = 0; i < patternValues.length; i++) {
            if (minuteCounter > 3) {
                minuteCounter = 0;
            }
            short patternValue = patternValues[i];
            setTrafficSpeed(patternId, patternValue, i / 4, (minuteCounter * 15));
            minuteCounter++;
        }
    }

    /**
     * Store the traffic pattern for each 15 minutes for 24 hours.</-><br/><br/>
     * <p>
     * This method takes the pattern Id and adds the speed value to the right quarter to the right hour.
     *
     * @param patternId  Id of the traffic pattern.
     * @param speedValue Speed value in mph or kph.
     * @param hour       Hour to add the speed value to.
     * @param minute     Minute to add the speed value to. This is equalized into 15 minutes steps!
     **/
    private void setTrafficSpeed(int patternId, short speedValue, int hour, int minute) {
        int minutePointer = generateMinutePointer(minute);
        long patternPointer = (long) patternId * patternEntryBytes;
        ensureSpeedPatternLookupIndex(patternId);
        speedValue = speedValue > 255 ? 255 : speedValue;
        this.speedValue[0] = (byte) speedValue;
        byte[] testByteValues = new byte[1];
        orsSpeedPatternLookup.setBytes(patternPointer + ((hour * 4) + minutePointer), this.speedValue, 1);
        // TODO RAD
//        int test = Byte.toUnsignedInt(byteValues[0]);
//        orsSpeedPatternLookup.getBytes(patternPointer + ((hour * 4) + minutePointer), testByteValues, 1);
//        orsSpeedPatternLookup.getBytes(patternPointer + 0 + 0, testByteValues, 1);
//        orsSpeedPatternLookup.getBytes(patternPointer + 0 + 1, testByteValues, 1);
//        orsSpeedPatternLookup.getBytes(patternPointer + 0 + 2, testByteValues, 1);
//        orsSpeedPatternLookup.getBytes(patternPointer + 0 + 3, testByteValues, 1);
//        int firstShort = getTrafficSpeed(patternId, hour, 0);
//        int secondShort = getTrafficSpeed(patternId, hour, 15);
//        int thirdShort = getTrafficSpeed(patternId, hour, 30);
//        int furthShort = getTrafficSpeed(patternId, hour, 45);
//        int aShort = getTrafficSpeed(patternPointer, hour, 0);
//        int bShort = getTrafficSpeed(patternPointer, hour, 15);
//        int cShort = getTrafficSpeed(patternPointer, hour, 30);
//        int dShort = getTrafficSpeed(patternPointer, hour, 45);
//        System.out.println(",,,");
        // TODO RAD
    }

    /**
     * Get the specified custom value of the edge that was assigned to it in the setValueEdge method<br/><br/>
     * <p>
     * The method takes an identifier to the edge and then gets the requested value for the edge from the storage
     *
     * @param edgeId Internal ID of the edge to get values for
     * @param prop   The property of the edge to get (TYPE - border type (0,1,2), START - the ID of the country
     *               the edge starts in, END - the ID of the country the edge ends in.
     * @return The value of the requested property
     */
    public int getOrsRoadProperties(int edgeId, Property prop) {
        byte[] propertyValue = new byte[1];
        long edgePointer = (long) edgeId * edgePropertyEntryBytes;
        if (prop == Property.ROAD_TYPE) {
            orsEdgesProperties.getBytes(edgePointer + LOCATION_ROAD_TYPE, propertyValue, 1);
        }
        return Byte.toUnsignedInt(propertyValue[0]);
    }

    /**
     * Receive the matched edgeID <-> linkID matches for both directions.</-><br/><br/>
     * <p>
     * This method returns the linkID matched on the internal edge ID in both directions if present.
     *
     * @param edgeId   Internal ID of the graph edge.
     * @param baseNode Value of the base Node of the edge.
     * @param adjNode  Value of the adjacent Node of the edge.
     * @param weekday  Enum of Weekday to get the pattern for.
     **/
    public int getEdgeIdTrafficPatternLookup(int edgeId, int baseNode, int adjNode, TrafficEnums.WeekDay weekday) {
        long edgePointer = (long) edgeId * edgeLinkLookupEntryBytes;
        if (baseNode < adjNode)
            return Short.toUnsignedInt(orsEdgesTrafficLinkLookup.getShort(edgePointer + LOCATION_FORWARD_TRAFFIC + weekday.getCanonical()));
        else
            return Short.toUnsignedInt(orsEdgesTrafficLinkLookup.getShort(edgePointer + LOCATION_BACKWARD_TRAFFIC + weekday.getCanonical()));
    }

    /**
     * Receive the last saved traffic information priority for a certain ors edge id with its direction.</-><br/><br/>
     * <p>
     * This method returns the last stored priority. This is a custom value to decide if present weekly traffic pattern can be overwritten for a certain ors edge.
     * Traffic edges don't necessarily have the same length as the ors edges.
     * Therefore it might happen in the match making process that the same ors edge is matched multiple times on different traffic edges.
     * Since not all of those matches represent the same length of the ors edge it can be assumed that the larger the matches are, the more accurate the result displays reality.
     * <p>
     * e.g. This function can be used to retrieve the stored length of the last ors edge <-> traffic edge match.
     *
     * @param edgeId   Internal ID of the graph edge.
     * @param baseNode Value of the base Node of the edge.
     * @param adjNode  Value of the adjacent Node of the edge.
     **/
    public int getEdgeIdTrafficPatternPriority(int edgeId, int baseNode, int adjNode) {
        long edgePointer = (long) edgeId * edgeLinkLookupEntryBytes;
        byte[] priority = new byte[1];
        if (baseNode < adjNode)
            orsEdgesTrafficLinkLookup.getBytes(edgePointer + LOCATION_FORWARD_TRAFFIC_PRIORITY, priority, 1);
        else
            orsEdgesTrafficLinkLookup.getBytes(edgePointer + LOCATION_BACKWARD_TRAFFIC_PRIORITY, priority, 1);
        return Byte.toUnsignedInt(priority[0]);
    }

    /**
     * Receive the matched linkID <-> patternId matches for the desired weekday.</-><br/><br/>
     * <p>
     * This method returns the patternId for a specific weekday matched on the link ID if present.
     *
     * @param linkId  Internal ID of the graph edge.
     * @param weekday Value of the base Node of the edge.
     **/
    public short getOrsLinkTrafficPattern(int linkId, Weekday weekday) {
        long linkPointer = (long) linkId * linkPatternEntryBytes;
        return orsLinkTrafficSpeedPatternLookup.getShort(linkPointer + (weekday.ordinal() * 2));
    }

    /**
     * Receive the correct traffic pattern for the desired hour.</-><br/><br/>
     * <p>
     * This method returns the traffic patterns for one hour in 15 minutes steps in a byte[].
     *
     * @param patternId Internal ID of the graph edge.
     * @param hour      Hour to get the patterns for.
     * @param minute   Minute to get the patterns for.
     **/
    public int getTrafficSpeed(int patternId, int hour, int minute) {
        byte[] values = new byte[1];
        int minutePointer = generateMinutePointer(minute);
        long patternPointer = (long) patternId * patternEntryBytes;
        orsSpeedPatternLookup.getBytes(patternPointer + ((hour * 4) + minutePointer), values, 1);
        return Byte.toUnsignedInt(values[0]);
    }


    /**
     * Get the specified custom value of the edge that was assigned to it in the setValueEdge method<br/><br/>
     * <p>
     * The method takes an edgeId, the base Node to define its direction, and time constrains (weekday, hour, minute),
     * to find the appropriate
     *
     * @param edgeId   Internal ID of the edge to get values for.
     * @param baseNode The baseNode of the VirtualEdgeIteratorState to define the direction.
     * @param unixTimeInMillies  Time in unix millies.
     */
    public int getSpeedValue(int edgeId, int baseNode, int adjNode, long unixTimeInMillies) {
        return 0;
    }

    private void ensureEdgesPropertyIndex(int edgeId) {
        orsEdgesProperties.ensureCapacity(((long) edgeId + 1) * edgePropertyEntryBytes);
    }

    private void ensureEdgesTrafficLinkLookupIndex(int edgeId) {
        orsEdgesTrafficLinkLookup.ensureCapacity(((long) edgeId + 1) * edgeLinkLookupEntryBytes);
    }

    private void ensureTrafficLinkTrafficSpeedPatternLookupIndex(int linkId) {
        orsLinkTrafficSpeedPatternLookup.ensureCapacity(((long) linkId + 1) * linkPatternEntryBytes);
    }

    private void ensureSpeedPatternLookupIndex(int patternId) {
        orsSpeedPatternLookup.ensureCapacity(((long) patternId + 1) * patternEntryBytes);
    }

    public boolean isMatched() {
        return orsEdgesTrafficLinkLookup.getHeader(8) == 1;
    }

    public void setMatched() {
        orsEdgesTrafficLinkLookup.setHeader(8, 1);
    }

    /**
     * @return true, if and only if, if an additional field at the graphs node storage is required
     */
    @Override
    public boolean isRequireNodeField() {
        return true;
    }

    /**
     * @return true, if and only if, if an additional field at the graphs edge storage is required
     */
    @Override
    public boolean isRequireEdgeField() {
        return true;
    }

    /**
     * @return the default field value which will be set for default when creating nodes
     */
    @Override
    public int getDefaultNodeFieldValue() {
        return -1;
    }

    /**
     * @return the default field value which will be set for default when creating edges
     */
    @Override
    public int getDefaultEdgeFieldValue() {
        return -1;
    }

    /**
     * initializes the extended storage by giving the base graph
     *
     * @param graph Provide the graph object.
     * @param dir The directory where the graph will be initialized.
     */
    @Override
    public void init(Graph graph, Directory dir) {
        if (edgesCount > 0)
            throw new AssertionError("The ORS storage must be initialized only once.");

        this.orsEdgesProperties = dir.find("ext_traffic_edge_properties");
        this.orsEdgesTrafficLinkLookup = dir.find("ext_traffic_edges_traffic_lookup");
        this.orsLinkTrafficSpeedPatternLookup = dir.find("ext_traffic_edges_speed_pattern_lookup");
        this.orsSpeedPatternLookup = dir.find("ext_traffic_pattern_lookup");
    }

    /**
     * initializes the extended storage to be empty - required for testing purposes as the ext_storage aren't created
     * at the time tests are run
     */
    public void init() {
        if (edgesCount > 0)
            throw new AssertionError("The ORS storage must be initialized only once.");
        Directory d = new RAMDirectory();
        this.orsEdgesProperties = d.find("");
        this.orsEdgesTrafficLinkLookup = d.find("");
        this.orsLinkTrafficSpeedPatternLookup = d.find("");
        this.orsSpeedPatternLookup = d.find("");
    }

    /**
     * sets the segment size in all additional data storages
     *
     * @param bytes Size in bytes.
     */
    @Override
    public void setSegmentSize(int bytes) {
        orsEdgesProperties.setSegmentSize(bytes);
        orsEdgesTrafficLinkLookup.setSegmentSize(bytes);
        orsLinkTrafficSpeedPatternLookup.setSegmentSize(bytes);
        orsSpeedPatternLookup.setSegmentSize(bytes);
    }

    /**
     * creates a copy of this extended storage
     *
     * @param clonedStorage The storage to clone.
     */
    @Override
    public GraphExtension copyTo(GraphExtension clonedStorage) {
        if (!(clonedStorage instanceof TrafficGraphStorage)) {
            throw new IllegalStateException("the extended storage to clone must be the same");
        }

        TrafficGraphStorage clonedTC = (TrafficGraphStorage) clonedStorage;

        orsEdgesProperties.copyTo(clonedTC.orsEdgesProperties);
        orsEdgesTrafficLinkLookup.copyTo(clonedTC.orsEdgesTrafficLinkLookup);
        orsLinkTrafficSpeedPatternLookup.copyTo(clonedTC.orsLinkTrafficSpeedPatternLookup);
        orsSpeedPatternLookup.copyTo(clonedTC.orsSpeedPatternLookup);
        clonedTC.edgesCount = edgesCount;

        return clonedStorage;
    }

    /**
     * @return true if successfully loaded from persistent storage.
     */
    @Override
    public boolean loadExisting() {
        if (!orsEdgesProperties.loadExisting())
            throw new IllegalStateException("Unable to load storage 'ext_traffic'. corrupt file or directory?");
        if (!orsEdgesTrafficLinkLookup.loadExisting())
            throw new IllegalStateException("Unable to load storage 'ext_traffic_edges_traffic_lookup'. corrupt file or directory?");
        if (!orsLinkTrafficSpeedPatternLookup.loadExisting())
            throw new IllegalStateException("Unable to load storage 'ext_traffic_edges_speed_pattern_lookup'. corrupt file or directory?");
        if (!orsSpeedPatternLookup.loadExisting())
            throw new IllegalStateException("Unable to load storage 'ext_traffic_pattern_lookup'. corrupt file or directory?");
        edgePropertyEntryBytes = orsEdgesProperties.getHeader(0);
        edgeLinkLookupEntryBytes = orsEdgesTrafficLinkLookup.getHeader(0);
        linkPatternEntryBytes = orsLinkTrafficSpeedPatternLookup.getHeader(0);
        patternEntryBytes = orsSpeedPatternLookup.getHeader(0);
        edgesCount = orsEdgesProperties.getHeader(4);
        return true;
    }

    /**
     * Creates the underlying storage. First operation if it cannot be loaded.
     *
     * @param initBytes Init size in bytes.
     */
    @Override
    public GraphExtension create(long initBytes) {
        orsEdgesProperties.create(initBytes * edgePropertyEntryBytes);
        orsEdgesTrafficLinkLookup.create(initBytes * edgeLinkLookupEntryBytes);
        orsLinkTrafficSpeedPatternLookup.create(initBytes * linkPatternEntryBytes);
        orsSpeedPatternLookup.create(initBytes * patternEntryBytes);
        return this;
    }

    /**
     * This method makes sure that the underlying data is written to the storage. Keep in mind that
     * a disc normally has an IO cache so that flush() is (less) probably not save against power
     * loses.
     */
    @Override
    public void flush() {
        orsEdgesProperties.setHeader(0, edgePropertyEntryBytes);
        orsEdgesTrafficLinkLookup.setHeader(0, edgeLinkLookupEntryBytes);
        orsLinkTrafficSpeedPatternLookup.setHeader(0, linkPatternEntryBytes);
        orsSpeedPatternLookup.setHeader(0, patternEntryBytes);
        orsEdgesProperties.setHeader(4, edgesCount);
        orsEdgesTrafficLinkLookup.setHeader(4, edgesCount);
        orsLinkTrafficSpeedPatternLookup.setHeader(4, linkCount);
        orsSpeedPatternLookup.setHeader(4, patternCount);
        orsEdgesProperties.flush();
        orsEdgesTrafficLinkLookup.flush();
        orsLinkTrafficSpeedPatternLookup.flush();
        orsSpeedPatternLookup.flush();
    }

    /**
     * This method makes sure that the underlying used resources are released. WARNING: it does NOT
     * flush on close!
     */
    @Override
    public void close() {
        orsEdgesProperties.close();
        orsEdgesTrafficLinkLookup.close();
        orsLinkTrafficSpeedPatternLookup.close();
        orsSpeedPatternLookup.close();
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    /**
     * @return the allocated storage size in bytes
     */
    @Override
    public long getCapacity() {
        return orsEdgesProperties.getCapacity() + orsEdgesTrafficLinkLookup.getCapacity() + orsLinkTrafficSpeedPatternLookup.getCapacity() + orsSpeedPatternLookup.getCapacity();
    }


    private int generateMinutePointer(int minute) {
        if (minute < 15) {
            return 0;
        } else if (minute < 30) {
            return 1;
        } else if (minute < 45) {
            return 2;
        } else {
            return 3;
        }
    }

}