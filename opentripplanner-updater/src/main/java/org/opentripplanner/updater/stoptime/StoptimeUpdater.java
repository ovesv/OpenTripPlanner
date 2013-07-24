/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.updater.stoptime;

import java.util.List;

import javax.annotation.PostConstruct;

import lombok.Setter;

import org.opentripplanner.routing.edgetype.TableTripPattern;
import org.opentripplanner.routing.edgetype.TimetableResolver;
import org.opentripplanner.routing.edgetype.TimetableSnapshotSource;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.services.GraphService;
import org.opentripplanner.routing.trippattern.TripUpdate;
import org.opentripplanner.routing.services.TransitIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Update OTP stop time tables from some (realtime) source
 * @author abyrd
 */
public class StoptimeUpdater implements Runnable, TimetableSnapshotSource {

    private static final Logger LOG = LoggerFactory.getLogger(StoptimeUpdater.class);

    @Autowired private GraphService graphService;
    @Setter    private UpdateStreamer updateStreamer;
    @Setter    private static int logFrequency = 2000;

    /** 
     * If a timetable snapshot is requested less than this number of milliseconds after the previous 
     * snapshot, just return the same one. Throttles the potentially resource-consuming task of 
     * duplicating a TripPattern -> Timetable map and indexing the new Timetables.
     */
    @Setter private int maxSnapshotFrequency = 1000; // msec    

    /** 
     * The last committed snapshot that was handed off to a routing thread. This snapshot may be
     * given to more than one routing thread if the maximum snapshot frequency is exceeded. 
     */
    private TimetableResolver snapshot = null;
    
    /** The working copy of the timetable resolver. Should not be visible to routing threads. */
    private TimetableResolver buffer = new TimetableResolver();
    
    /** The TransitIndexService */
    private TransitIndexService transitIndexService;
    
    
    // nothing in the timetable snapshot binds it to one graph. we could use this updater for all
    // graphs at once
    private Graph graph;
    private long lastSnapshotTime = -1;
    
    /**
     * Set the data sources for the target graphs.
     */
    @PostConstruct
    public void setup () {
        graph = graphService.getGraph();
        graph.timetableSnapshotSource = this;
        transitIndexService = graph.getService(TransitIndexService.class);
        if (transitIndexService == null)
            throw new RuntimeException(
                    "Real-time update need a TransitIndexService. Please setup one during graph building.");
    }
    
    public synchronized TimetableResolver getSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotTime > maxSnapshotFrequency) {
            if (buffer.isDirty()) {
                LOG.debug("Committing {}", buffer.toString());
                snapshot = buffer.commit();
            } else {
                LOG.debug("Buffer was unchanged, keeping old snapshot.");
            }
            lastSnapshotTime = System.currentTimeMillis();
        } else {
            LOG.debug("Snapshot frequency exceeded. Reusing snapshot {}", snapshot);
        }
        return snapshot;
    }
    
    /**
     * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates,
     * and applies those updates to scheduled trips.
     */
    @Override
    public void run() {
        int appliedBlockCount = 0;
        while (true) {
            List<TripUpdate> tripUpdates = updateStreamer.getUpdates(); 
            if (tripUpdates == null) {
                LOG.debug("tripUpdates is null");
                continue;
            }

            LOG.debug("message contains {} trip update blocks", tripUpdates.size());
            int uIndex = 0;
            for (TripUpdate tripUpdate : tripUpdates) {
                uIndex += 1;
                LOG.debug("trip update block #{} ({} updates) :", uIndex, tripUpdate.getUpdates().size());
                LOG.trace("{}", tripUpdate.toString());
                tripUpdate.filter(true, true, true);
                if (! tripUpdate.isCoherent()) {
                    LOG.warn("Incoherent TripUpdate, skipping.");
                    continue;
                }
                if (tripUpdate.getUpdates().size() < 1) {
                    LOG.debug("trip update contains no updates after filtering, skipping.");
                    continue;
                }
                TableTripPattern pattern = transitIndexService.getTripPatternForTrip(tripUpdate.getTripId());
                if (pattern == null) {
                    LOG.debug("No pattern found for tripId {}, skipping trip update.", tripUpdate.getTripId());
                    continue;
                }

                // we have a message we actually want to apply
                boolean applied = buffer.update(pattern, tripUpdate);
                if (applied) {
                    appliedBlockCount += 1;
                    if (appliedBlockCount % logFrequency == 0) {
                        LOG.info("applied {} stoptime update blocks.", appliedBlockCount);
                    }
                    // consider making a snapshot immediately in anticipation of incoming requests 
                    getSnapshot(); 
                }
            }
            LOG.debug("end of update message");
        }
    }

    public String toString() {
        String s = (updateStreamer == null) ? "NONE" : updateStreamer.toString();
        return "Streaming stoptime updater with update streamer = " + s;
    }
    
}
