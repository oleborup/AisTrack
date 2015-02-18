/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.dma.ais.track.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.dma.ais.track.AisTrackConfiguration;
import dk.dma.ais.track.model.Target;

public class MapDbTargetStore<T extends Target> implements TargetStore<T>, Runnable {

    static final Logger LOG = LoggerFactory.getLogger(MapDbTargetStore.class);

    private boolean stopped;
    private final Map<Integer, T> map;
    private final MapDb<Integer, T> db;
    private final ScheduledExecutorService expireExecutor;
    private final long expiryTime;

    @Inject
    public MapDbTargetStore(AisTrackConfiguration cfg) throws IOException {
        LOG.info("Loading target database using backup dir: " + cfg.backup());
        Files.createDirectories(Paths.get(cfg.backup()));
        expiryTime = cfg.targetExpire().toMillis();
        final long cleanupInterval = cfg.cleanupInterval().toMillis();
        db = MapDb.create(cfg.backup(), "targetdb");
        if (db == null) {
            System.exit(-1);
        }
        map = db.getMap();
        LOG.info(map.size() + " targets loaded");
        expireExecutor = Executors.newSingleThreadScheduledExecutor();
        expireExecutor.scheduleWithFixedDelay(this, cleanupInterval, cleanupInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Expires stale data
     */
    @Override
    public void run() {
        try {
            long now = System.currentTimeMillis();
            long removed = 0;
            for (T target : map.values()) {
                if (stopped) {
                    return;
                }

                Date lastReport = target.getLastReport();
                long age = now - lastReport.getTime();
                if (age > expiryTime) {
                    map.remove(target.getMmsi());
                    removed++;
                }
            }
            if (removed > 0) {
                LOG.info("Targets removed: " + removed);
            }
            if (!stopped) {
                db.getDb().compact();
            }
            LOG.info("Stale data cleaned up in " + (System.currentTimeMillis() - now) + " ms");
        } catch (Exception e) {
            LOG.error("Error cleaning up stale data", e);
        }
    }

    @Override
    public T get(int mmsi) {
        return map.get(mmsi);
    }

    @Override
    public void put(T target) {
        if (!stopped) {
            map.put(target.getMmsi(), target);
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Collection<T> list() {
        return map.values();
    }

    /**
     * Start the process of closing this service
     */
    @Override
    public void prepareStop() {
        stopped = true;
    }

    @Override
    public void close() {
        LOG.info("Stopping target store expiry thread");
        expireExecutor.shutdownNow();
        try {
            expireExecutor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        LOG.info("Closing database");
        db.close();
        LOG.info("Database closed");
    }

}
