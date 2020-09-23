/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.shard;

import static org.elasticsearch.cluster.routing.TestShardRouting.newShardRouting;
import static org.elasticsearch.index.translog.Translog.UNSET_AUTO_GENERATED_TIMESTAMP;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import io.crate.common.io.IOUtils;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeRequest;
import org.elasticsearch.action.admin.indices.stats.CommonStats;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingHelper;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.CommitStats;
import org.elasticsearch.index.engine.DocIdSeqNoAndSource;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineTestCase;
import org.elasticsearch.index.engine.InternalEngine;
import org.elasticsearch.index.engine.InternalEngineFactory;
import org.elasticsearch.index.engine.ReadOnlyEngine;
import org.elasticsearch.index.engine.Segment;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.VersionFieldMapper;
import org.elasticsearch.index.seqno.SeqNoStats;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.store.StoreStats;
import org.elasticsearch.index.translog.TestTranslog;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.index.translog.TranslogStats;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.test.store.MockFSDirectoryFactory;
import org.elasticsearch.threadpool.ThreadPool;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import io.crate.common.collections.Tuple;
import io.crate.common.unit.TimeValue;

/**
 * Simple unit-test IndexShard related operations.
 */
public class IndexShardTests extends IndexShardTestCase {

    @Test
    public void testRecordsForceMerges() throws IOException {
        IndexShard shard = newStartedShard(true);
        final String initialForceMergeUUID = ((InternalEngine) shard.getEngine()).getForceMergeUUID();
        assertThat(initialForceMergeUUID, nullValue());
        final ForceMergeRequest firstForceMergeRequest = new ForceMergeRequest().maxNumSegments(1);
        shard.forceMerge(firstForceMergeRequest);
        final String secondForceMergeUUID = ((InternalEngine) shard.getEngine()).getForceMergeUUID();
        assertThat(secondForceMergeUUID, notNullValue());
        assertThat(secondForceMergeUUID, equalTo(firstForceMergeRequest.forceMergeUUID()));
        final ForceMergeRequest secondForceMergeRequest = new ForceMergeRequest().maxNumSegments(1);
        shard.forceMerge(secondForceMergeRequest);
        final String thirdForceMergeUUID = ((InternalEngine) shard.getEngine()).getForceMergeUUID();
        assertThat(thirdForceMergeUUID, notNullValue());
        assertThat(thirdForceMergeUUID, not(equalTo(secondForceMergeUUID)));
        assertThat(thirdForceMergeUUID, equalTo(secondForceMergeRequest.forceMergeUUID()));
        closeShards(shard);
    }

    @Test
    public void testClosesPreventsNewOperations() throws Exception {
        IndexShard indexShard = newStartedShard();
        closeShards(indexShard);
        assertThat(indexShard.getActiveOperationsCount(), equalTo(0));
        expectThrows(IndexShardClosedException.class,
            () -> indexShard.acquirePrimaryOperationPermit(null, ThreadPool.Names.WRITE, ""));
        expectThrows(IndexShardClosedException.class,
            () -> indexShard.acquireAllPrimaryOperationsPermits(null, TimeValue.timeValueSeconds(30L)));
        expectThrows(IndexShardClosedException.class,
            () -> indexShard.acquireReplicaOperationPermit(indexShard.getPendingPrimaryTerm(), SequenceNumbers.UNASSIGNED_SEQ_NO,
                randomNonNegativeLong(), null, ThreadPool.Names.WRITE, ""));
        expectThrows(IndexShardClosedException.class,
            () -> indexShard.acquireAllReplicaOperationsPermits(indexShard.getPendingPrimaryTerm(), SequenceNumbers.UNASSIGNED_SEQ_NO,
                randomNonNegativeLong(), null, TimeValue.timeValueSeconds(30L)));
    }

    @Test
    public void testRunUnderPrimaryPermitRunsUnderPrimaryPermit() throws IOException {
        final IndexShard indexShard = newStartedShard(true);
        try {
            assertThat(indexShard.getActiveOperationsCount(), equalTo(0));
            indexShard.runUnderPrimaryPermit(
                    () -> assertThat(indexShard.getActiveOperationsCount(), equalTo(1)),
                    e -> fail(e.toString()),
                    ThreadPool.Names.SAME,
                    "test");
                assertThat(indexShard.getActiveOperationsCount(), equalTo(0));
        } finally {
            closeShards(indexShard);
        }
    }

    @Test
    public void testRunUnderPrimaryPermitOnFailure() throws IOException {
        final IndexShard indexShard = newStartedShard(true);
        final AtomicBoolean invoked = new AtomicBoolean();
        try {
            indexShard.runUnderPrimaryPermit(
                    () -> {
                        throw new RuntimeException("failure");
                    },
                    e -> {
                        assertThat(e, instanceOf(RuntimeException.class));
                        assertThat(e.getMessage(), equalTo("failure"));
                        invoked.set(true);
                    },
                    ThreadPool.Names.SAME,
                    "test");
            assertTrue(invoked.get());
        } finally {
            closeShards(indexShard);
        }
    }

    @Test
    public void testRunUnderPrimaryPermitDelaysToExecutorWhenBlocked() throws Exception {
        final IndexShard indexShard = newStartedShard(true);
        try {
            final PlainActionFuture<Releasable> onAcquired = new PlainActionFuture<>();
            indexShard.acquireAllPrimaryOperationsPermits(onAcquired, new TimeValue(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
            final Releasable permit = onAcquired.actionGet();
            final CountDownLatch latch = new CountDownLatch(1);
            final String executorOnDelay =
                    randomFrom(ThreadPool.Names.FLUSH, ThreadPool.Names.GENERIC, ThreadPool.Names.MANAGEMENT, ThreadPool.Names.SAME);
            indexShard.runUnderPrimaryPermit(
                    () -> {
                        final String expectedThreadPoolName =
                                executorOnDelay.equals(ThreadPool.Names.SAME) ? "generic" : executorOnDelay.toLowerCase(Locale.ROOT);
                        assertThat(Thread.currentThread().getName(), Matchers.containsString(expectedThreadPoolName));
                        latch.countDown();
                    },
                    e -> fail(e.toString()),
                    executorOnDelay,
                    "test");
            permit.close();
            latch.await();
            // we could race and assert on the count before the permit is returned
            assertBusy(() -> assertThat(indexShard.getActiveOperationsCount(), equalTo(0)));
        } finally {
            closeShards(indexShard);
        }
    }


    @Test
    public void testAcquirePrimaryAllOperationsPermits() throws Exception {
        final IndexShard indexShard = newStartedShard(true);
        assertEquals(0, indexShard.getActiveOperationsCount());

        final CountDownLatch allPermitsAcquired = new CountDownLatch(1);

        final Thread[] threads = new Thread[randomIntBetween(2, 5)];
        final List<PlainActionFuture<Releasable>> futures = new ArrayList<>(threads.length);
        final AtomicArray<Tuple<Boolean, Exception>> results = new AtomicArray<>(threads.length);
        final CountDownLatch allOperationsDone = new CountDownLatch(threads.length);

        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            final boolean singlePermit = randomBoolean();

            final PlainActionFuture<Releasable> future = new PlainActionFuture<Releasable>() {
                @Override
                public void onResponse(final Releasable releasable) {
                    if (singlePermit) {
                        assertThat(indexShard.getActiveOperationsCount(), greaterThan(0));
                    } else {
                        assertThat(indexShard.getActiveOperationsCount(), equalTo(IndexShard.OPERATIONS_BLOCKED));
                    }
                    releasable.close();
                    super.onResponse(releasable);
                    results.setOnce(threadId, Tuple.tuple(Boolean.TRUE, null));
                    allOperationsDone.countDown();
                }

                @Override
                public void onFailure(final Exception e) {
                    results.setOnce(threadId, Tuple.tuple(Boolean.FALSE, e));
                    allOperationsDone.countDown();
                }
            };
            futures.add(threadId, future);

            threads[threadId] = new Thread(() -> {
                try {
                    allPermitsAcquired.await();
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (singlePermit) {
                    indexShard.acquirePrimaryOperationPermit(future, ThreadPool.Names.WRITE, "");
                } else {
                    indexShard.acquireAllPrimaryOperationsPermits(future, TimeValue.timeValueHours(1L));
                }
            });
            threads[threadId].start();
        }

        final AtomicBoolean blocked = new AtomicBoolean();
        final CountDownLatch allPermitsTerminated = new CountDownLatch(1);

        final PlainActionFuture<Releasable> futureAllPermits = new PlainActionFuture<Releasable>() {
            @Override
            public void onResponse(final Releasable releasable) {
                try {
                    blocked.set(true);
                    allPermitsAcquired.countDown();
                    super.onResponse(releasable);
                    allPermitsTerminated.await();
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        indexShard.acquireAllPrimaryOperationsPermits(futureAllPermits, TimeValue.timeValueSeconds(30L));
        allPermitsAcquired.await();
        assertTrue(blocked.get());
        assertEquals(IndexShard.OPERATIONS_BLOCKED, indexShard.getActiveOperationsCount());
        assertTrue("Expected no results, operations are blocked", results.asList().isEmpty());
        futures.forEach(future -> assertFalse(future.isDone()));

        allPermitsTerminated.countDown();

        final Releasable allPermits = futureAllPermits.get();
        assertTrue(futureAllPermits.isDone());

        assertTrue("Expected no results, operations are blocked", results.asList().isEmpty());
        futures.forEach(future -> assertFalse(future.isDone()));

        Releasables.close(allPermits);
        allOperationsDone.await();
        for (Thread thread : threads) {
            thread.join();
        }

        futures.forEach(future -> assertTrue(future.isDone()));
        assertEquals(threads.length, results.asList().size());
        results.asList().forEach(result -> {
            assertTrue(result.v1());
            assertNull(result.v2());
        });

        closeShards(indexShard);
    }

    @Test
    public void testShardStats() throws IOException {
        IndexShard shard = newStartedShard();
        ShardStats stats = new ShardStats(
            shard.routingEntry(),
            shard.shardPath(),
            new CommonStats(),
            shard.commitStats(),
            shard.seqNoStats(),
            shard.getRetentionLeaseStats()
        );
        assertThat(shard.shardPath().getRootDataPath().toString(), is(stats.getDataPath()));
        assertThat(shard.shardPath().getRootStatePath().toString(), is(stats.getStatePath()));
        assertThat(shard.shardPath().isCustomDataPath(), is(stats.isCustomDataPath()));
        assertThat(shard.getRetentionLeaseStats(), is(stats.getRetentionLeaseStats()));

        // try to serialize it to ensure values survive the serialization
        BytesStreamOutput out = new BytesStreamOutput();
        stats.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        stats = new ShardStats(in);

        assertThat(shard.shardPath().getRootDataPath().toString(), is(stats.getDataPath()));
        assertThat(shard.shardPath().getRootStatePath().toString(), is(stats.getStatePath()));
        assertThat(shard.shardPath().isCustomDataPath(), is(stats.isCustomDataPath()));
        assertThat(shard.getRetentionLeaseStats(), is(stats.getRetentionLeaseStats()));

        closeShards(shard);
    }

    @Test
    public void testIsSearchIdle() throws Exception {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metadata = IndexMetadata.builder("test")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        IndexShard primary = newShard(new ShardId(metadata.getIndex(), 0), true, "n1", metadata, null);
        recoverShardFromStore(primary);
        indexDoc(primary, "0", "{\"foo\" : \"bar\"}");
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        assertFalse(primary.isSearchIdle());

        IndexScopedSettings scopedSettings = primary.indexSettings().getScopedSettings();
        settings = Settings.builder().put(settings).put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.ZERO).build();
        scopedSettings.applySettings(settings);
        assertTrue(primary.isSearchIdle());

        settings = Settings.builder().put(settings).put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.timeValueMinutes(1))
            .build();
        scopedSettings.applySettings(settings);
        assertFalse(primary.isSearchIdle());

        settings = Settings.builder().put(settings).put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.timeValueMillis(10))
            .build();
        scopedSettings.applySettings(settings);

        assertBusy(() -> assertTrue(primary.isSearchIdle()));
        do {
            // now loop until we are fast enough... shouldn't take long
            primary.awaitShardSearchActive(aBoolean -> {});
        } while (primary.isSearchIdle());

        assertBusy(() -> assertTrue(primary.isSearchIdle()));
        do {
            // now loop until we are fast enough... shouldn't take long
            primary.acquireSearcher("test").close();
        } while (primary.isSearchIdle());
        closeShards(primary);
    }

    @Test
    public void testScheduledRefresh() throws IOException, InterruptedException {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metadata = IndexMetadata.builder("test")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        IndexShard primary = newShard(new ShardId(metadata.getIndex(), 0), true, "n1", metadata, null);
        recoverShardFromStore(primary);
        indexDoc(primary, "0", "{\"foo\" : \"bar\"}");
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        IndexScopedSettings scopedSettings = primary.indexSettings().getScopedSettings();
        settings = Settings.builder().put(settings).put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.ZERO).build();
        scopedSettings.applySettings(settings);

        assertFalse(primary.getEngine().refreshNeeded());
        indexDoc(primary, "1", "{\"foo\" : \"bar\"}");
        assertTrue(primary.getEngine().refreshNeeded());
        long lastSearchAccess = primary.getLastSearcherAccess();
        assertFalse(primary.scheduledRefresh());
        assertEquals(lastSearchAccess, primary.getLastSearcherAccess());
        // wait until the thread-pool has moved the timestamp otherwise we can't assert on this below
        awaitBusy(() -> primary.getThreadPool().relativeTimeInMillis() > lastSearchAccess);
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            primary.awaitShardSearchActive(refreshed -> {
                assertTrue(refreshed);
                try (Engine.Searcher searcher = primary.acquireSearcher("test")) {
                    assertEquals(2, searcher.getIndexReader().numDocs());
                } finally {
                    latch.countDown();
                }
            });
        }
        assertNotEquals("awaitShardSearchActive must access a searcher to remove search idle state", lastSearchAccess,
            primary.getLastSearcherAccess());
        assertTrue(lastSearchAccess < primary.getLastSearcherAccess());
        try (Engine.Searcher searcher = primary.acquireSearcher("test")) {
            assertEquals(1, searcher.getIndexReader().numDocs());
        }
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        latch.await();
        CountDownLatch latch1 = new CountDownLatch(1);
        primary.awaitShardSearchActive(refreshed -> {
            assertFalse(refreshed);
            try (Engine.Searcher searcher = primary.acquireSearcher("test")) {
                assertEquals(2, searcher.getIndexReader().numDocs());
            } finally {
                latch1.countDown();
            }

        });
        latch1.await();
        closeShards(primary);
    }

    @Test
    public void testRefreshIsNeededWithRefreshListeners() throws IOException, InterruptedException {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metadata = IndexMetadata.builder("test")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        IndexShard primary = newShard(new ShardId(metadata.getIndex(), 0), true, "n1", metadata, null);
        recoverShardFromStore(primary);
        indexDoc(primary, "0", "{\"foo\" : \"bar\"}");
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        Engine.IndexResult doc = indexDoc(primary, "1", "{\"foo\" : \"bar\"}");
        CountDownLatch latch = new CountDownLatch(1);
        primary.addRefreshListener(doc.getTranslogLocation(), r -> latch.countDown());
        assertEquals(1, latch.getCount());
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        latch.await();

        IndexScopedSettings scopedSettings = primary.indexSettings().getScopedSettings();
        settings = Settings.builder().put(settings).put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.ZERO).build();
        scopedSettings.applySettings(settings);

        doc = indexDoc(primary, "2", "{\"foo\" : \"bar\"}");
        CountDownLatch latch1 = new CountDownLatch(1);
        primary.addRefreshListener(doc.getTranslogLocation(), r -> latch1.countDown());
        assertEquals(1, latch1.getCount());
        assertTrue(primary.getEngine().refreshNeeded());
        assertTrue(primary.scheduledRefresh());
        latch1.await();
        closeShards(primary);
    }

    @Test
    public void testSegmentMemoryTrackedWithRandomSearchers() throws Exception {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metaData = IndexMetadata.builder("test")
            .putMapping("_doc", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        IndexShard primary = newShard(new ShardId(metaData.getIndex(), 0), true, "n1", metaData, null);
        recoverShardFromStore(primary);

        int threadCount = randomIntBetween(2, 6);
        List<Thread> threads = new ArrayList<>(threadCount);
        int iterations = randomIntBetween(50, 100);
        List<Engine.Searcher> searchers = Collections.synchronizedList(new ArrayList<>());

        logger.info("--> running with {} threads and {} iterations each", threadCount, iterations);
        for (int threadId = 0; threadId < threadCount; threadId++) {
            final String threadName = "thread-" + threadId;
            Runnable r = () -> {
                for (int i = 0; i < iterations; i++) {
                    try {
                        if (randomBoolean()) {
                            String id = "id-" + threadName + "-" + i;
                            logger.debug("--> {} indexing {}", threadName, id);
                            indexDoc(primary, id, "{\"foo\" : \"" + randomAlphaOfLength(10) + "\"}");
                        }

                        if (randomBoolean() && i > 10) {
                            String id = "id-" + threadName + "-" + randomIntBetween(0, i - 1);
                            logger.debug("--> {}, deleting {}", threadName, id);
                            deleteDoc(primary, id);
                        }

                        if (randomBoolean()) {
                            logger.debug("--> {} refreshing", threadName);
                            primary.refresh("forced refresh");
                        }

                        if (randomBoolean()) {
                            String searcherName = "searcher-" + threadName + "-" + i;
                            logger.debug("--> {} acquiring new searcher {}", threadName, searcherName);
                            // Acquire a new searcher, adding it to the list
                            searchers.add(primary.acquireSearcher(searcherName));
                        }

                        if (randomBoolean() && searchers.size() > 1) {
                            // Close one of the readers at random
                            synchronized (searchers) {
                                // re-check because it could have decremented after the check
                                if (searchers.size() > 1) {
                                    Engine.Searcher searcher = searchers.remove(0);
                                    logger.debug("--> {} closing searcher {}", threadName, searcher.source());
                                    IOUtils.close(searcher);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("--> got exception: ", e);
                        fail("got an exception we didn't expect");
                    }
                }

            };
            threads.add(new Thread(r, threadName));
        }
        threads.forEach(Thread::start);

        for (Thread t : threads) {
            t.join();
        }

        // We need to wait for all ongoing merges to complete. The reason is that during a merge the
        // IndexWriter holds the core cache key open and causes the memory to be registered in the breaker
        primary.forceMerge(new ForceMergeRequest().maxNumSegments(1).flush(true));

        // Close remaining searchers
        IOUtils.close(searchers);
        primary.refresh("test");

        var ss = primary.segments(randomBoolean());
        CircuitBreaker breaker = primary.circuitBreakerService.getBreaker(CircuitBreaker.ACCOUNTING);
        long segmentMem = ss.stream().mapToLong(s -> s.getSize().getBytes()).sum();
        long breakerMem = breaker.getUsed();
        logger.info("--> comparing segmentMem: {} - breaker: {} => {}", segmentMem, breakerMem, segmentMem == breakerMem);
        assertThat(segmentMem, equalTo(breakerMem));

        // Close shard
        closeShards(primary);

        // Check that the breaker was successfully reset to 0, meaning that all the accounting was correctly applied
        breaker = primary.circuitBreakerService.getBreaker(CircuitBreaker.ACCOUNTING);
        assertThat(breaker.getUsed(), equalTo(0L));
    }

    @Test
    public void testFlushOnInactive() throws Exception {
        Settings settings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metaData = IndexMetadata.builder("test")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        ShardRouting shardRouting =
            TestShardRouting.newShardRouting(
                new ShardId(metaData.getIndex(), 0),
                "n1",
                true,
                ShardRoutingState.INITIALIZING,
                RecoverySource.EmptyStoreRecoverySource.INSTANCE);
        final ShardId shardId = shardRouting.shardId();
        final NodeEnvironment.NodePath nodePath = new NodeEnvironment.NodePath(createTempDir());
        ShardPath shardPath = new ShardPath(false, nodePath.resolve(shardId), nodePath.resolve(shardId), shardId);
        AtomicBoolean markedInactive = new AtomicBoolean();
        AtomicReference<IndexShard> primaryRef = new AtomicReference<>();
        IndexShard primary = newShard(
            shardRouting,
            shardPath,
            metaData,
            null,
            null,
            new InternalEngineFactory(),
            () -> { },
            new IndexEventListener() {
                @Override
                public void onShardInactive(IndexShard indexShard) {
                    markedInactive.set(true);
                    primaryRef.get().flush(new FlushRequest());
                }
            });
        primaryRef.set(primary);
        recoverShardFromStore(primary);
        for (int i = 0; i < 3; i++) {
            indexDoc(primary, String.valueOf(i), "{\"foo\" : \"" + randomAlphaOfLength(10) + "\"}");
            primary.refresh("test"); // produce segments
        }
        List<Segment> segments = primary.segments(false);
        Set<String> names = new HashSet<>();
        for (Segment segment : segments) {
            assertFalse(segment.committed);
            assertTrue(segment.search);
            names.add(segment.getName());
        }
        assertThat(segments.size(), is(3));
        primary.flush(new FlushRequest());
        primary.forceMerge(new ForceMergeRequest().maxNumSegments(1).flush(false));
        primary.refresh("test");
        segments = primary.segments(false);
        for (Segment segment : segments) {
            if (names.contains(segment.getName())) {
                assertTrue(segment.committed);
                assertFalse(segment.search);
            } else {
                assertFalse(segment.committed);
                assertTrue(segment.search);
            }
        }
        assertThat(segments.size(), is(4));

        assertFalse(markedInactive.get());
        assertBusy(() -> {
            primary.checkIdle(0);
            assertFalse(primary.isActive());
        });

        assertTrue(markedInactive.get());
        segments = primary.segments(false);
        assertEquals(1, segments.size());
        for (Segment segment : segments) {
            assertTrue(segment.committed);
            assertTrue(segment.search);
        }
        closeShards(primary);
    }

    @Test
    public void testOnCloseStats() throws IOException {
        IndexShard indexShard = newStartedShard(true);
        updateMappings(indexShard, IndexMetadata.builder(indexShard.indexSettings.getIndexMetadata())
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}").build());
        for (int i = 0; i < 3; i++) {
            indexDoc(indexShard, String.valueOf(i), "{\"foo\" : \"" + randomAlphaOfLength(10) + "\"}");
            indexShard.refresh("test"); // produce segments
        }

        // check stats on closed and on opened shard
        if (randomBoolean()) {
            closeShards(indexShard);

            expectThrows(AlreadyClosedException.class, indexShard::seqNoStats);
            expectThrows(AlreadyClosedException.class, indexShard::commitStats);
            expectThrows(AlreadyClosedException.class, indexShard::storeStats);
        } else {
            SeqNoStats seqNoStats = indexShard.seqNoStats();
            assertThat(seqNoStats.getLocalCheckpoint(), equalTo(2L));

            CommitStats commitStats = indexShard.commitStats();
            assertThat(commitStats.getGeneration(), equalTo(2L));

            StoreStats storeStats = indexShard.storeStats();
            assertThat(storeStats.sizeInBytes(), greaterThan(0L));

            closeShards(indexShard);
        }
    }

    @Test
    public void testSupplyTombstoneDoc() throws Exception {
        IndexShard shard = newStartedShard();
        String id = randomRealisticUnicodeOfLengthBetween(1, 10);
        ParsedDocument deleteTombstone = shard.getEngine().config().getTombstoneDocSupplier().newDeleteTombstoneDoc(id);
        assertThat(deleteTombstone.docs(), hasSize(1));
        ParseContext.Document deleteDoc = deleteTombstone.docs().get(0);
        assertThat(
            deleteDoc.getFields().stream().map(IndexableField::name).collect(Collectors.toList()),
            containsInAnyOrder(
                IdFieldMapper.NAME,
                VersionFieldMapper.NAME,
                SeqNoFieldMapper.NAME,
                SeqNoFieldMapper.NAME,
                SeqNoFieldMapper.PRIMARY_TERM_NAME,
                SeqNoFieldMapper.TOMBSTONE_NAME));
        assertThat(deleteDoc.getField(IdFieldMapper.NAME).binaryValue(), equalTo(Uid.encodeId(id)));
        assertThat(deleteDoc.getField(SeqNoFieldMapper.TOMBSTONE_NAME).numericValue().longValue(), equalTo(1L));

        final String reason = randomUnicodeOfLength(200);
        ParsedDocument noopTombstone = shard.getEngine().config().getTombstoneDocSupplier().newNoopTombstoneDoc(reason);
        assertThat(noopTombstone.docs(), hasSize(1));
        ParseContext.Document noopDoc = noopTombstone.docs().get(0);
        assertThat(
            noopDoc.getFields().stream().map(IndexableField::name).collect(Collectors.toList()),
            containsInAnyOrder(
                VersionFieldMapper.NAME,
                SourceFieldMapper.NAME,
                SeqNoFieldMapper.TOMBSTONE_NAME,
                SeqNoFieldMapper.NAME,
                SeqNoFieldMapper.NAME,
                SeqNoFieldMapper.PRIMARY_TERM_NAME));
        assertThat(noopDoc.getField(SeqNoFieldMapper.TOMBSTONE_NAME).numericValue().longValue(), equalTo(1L));
        assertThat(noopDoc.getField(SourceFieldMapper.NAME).binaryValue(), equalTo(new BytesRef(reason)));

        closeShards(shard);
    }

    @Test
    public void testResetEngine() throws Exception {
        IndexShard shard = newStartedShard(false);
        indexOnReplicaWithGaps(shard, between(0, 1000), Math.toIntExact(shard.getLocalCheckpoint()));
        long maxSeqNoBeforeRollback = shard.seqNoStats().getMaxSeqNo();
        final long globalCheckpoint = randomLongBetween(shard.getLastKnownGlobalCheckpoint(), shard.getLocalCheckpoint());
        shard.updateGlobalCheckpointOnReplica(globalCheckpoint, "test");
        Set<String> docBelowGlobalCheckpoint = getShardDocUIDs(shard).stream()
            .filter(id -> Long.parseLong(id) <= globalCheckpoint).collect(Collectors.toSet());
        TranslogStats translogStats = shard.translogStats();
        AtomicBoolean done = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            latch.countDown();
            int hitClosedExceptions = 0;
            while (done.get() == false) {
                try {
                    List<String> exposedDocIds = EngineTestCase.getDocIds(getEngine(shard), rarely())
                        .stream().map(DocIdSeqNoAndSource::getId).collect(Collectors.toList());
                    assertThat("every operations before the global checkpoint must be reserved",
                               docBelowGlobalCheckpoint, everyItem(isIn(exposedDocIds)));
                } catch (AlreadyClosedException ignored) {
                    hitClosedExceptions++;
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            // engine reference was switched twice: current read/write engine -> ready-only engine -> new read/write engine
            assertThat(hitClosedExceptions, lessThanOrEqualTo(2));
        });
        thread.start();
        latch.await();

        final CountDownLatch engineResetLatch = new CountDownLatch(1);
        shard.acquireAllReplicaOperationsPermits(shard.getOperationPrimaryTerm(), globalCheckpoint, 0L, ActionListener.wrap(r -> {
            try {
                shard.resetEngineToGlobalCheckpoint();
            } finally {
                r.close();
                engineResetLatch.countDown();
            }
        }, Assert::assertNotNull), TimeValue.timeValueMinutes(1L));
        engineResetLatch.await();
        assertThat(getShardDocUIDs(shard), equalTo(docBelowGlobalCheckpoint));
        assertThat(shard.seqNoStats().getMaxSeqNo(), equalTo(globalCheckpoint));
        if (shard.indexSettings.isSoftDeleteEnabled()) {
            // we might have trimmed some operations if the translog retention policy is ignored (when soft-deletes enabled).
            assertThat(shard.translogStats().estimatedNumberOfOperations(),
                       lessThanOrEqualTo(translogStats.estimatedNumberOfOperations()));
        } else {
            assertThat(shard.translogStats().estimatedNumberOfOperations(), equalTo(translogStats.estimatedNumberOfOperations()));
        }
        assertThat(shard.getMaxSeqNoOfUpdatesOrDeletes(), equalTo(maxSeqNoBeforeRollback));
        done.set(true);
        thread.join();
        closeShard(shard, false);
    }

    /**
     * This test simulates a scenario seen rarely in ConcurrentSeqNoVersioningIT. Closing a shard while engine is inside
     * resetEngineToGlobalCheckpoint can lead to check index failure in integration tests.
     */
    @Test
    public void testCloseShardWhileResettingEngine() throws Exception {
        CountDownLatch readyToCloseLatch = new CountDownLatch(1);
        CountDownLatch closeDoneLatch = new CountDownLatch(1);
        IndexShard shard = newStartedShard(false, Settings.EMPTY, config -> new InternalEngine(config) {
            @Override
            public InternalEngine recoverFromTranslog(TranslogRecoveryRunner translogRecoveryRunner,
                                                      long recoverUpToSeqNo) throws IOException {
                readyToCloseLatch.countDown();
                try {
                    closeDoneLatch.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return super.recoverFromTranslog(translogRecoveryRunner, recoverUpToSeqNo);
            }
        });

        Thread closeShardThread = new Thread(() -> {
            try {
                readyToCloseLatch.await();
                shard.close("testing", false);
                // in integration tests, this is done as a listener on IndexService.
                MockFSDirectoryFactory.checkIndex(logger, shard.store(), shard.shardId);
            } catch (InterruptedException | IOException e) {
                throw new AssertionError(e);
            } finally {
                closeDoneLatch.countDown();
            }
        });

        closeShardThread.start();

        final CountDownLatch engineResetLatch = new CountDownLatch(1);
        shard.acquireAllReplicaOperationsPermits(shard.getOperationPrimaryTerm(), shard.getLastKnownGlobalCheckpoint(), 0L,
            ActionListener.wrap(r -> {
                try (r) {
                    shard.resetEngineToGlobalCheckpoint();
                } finally {
                    engineResetLatch.countDown();
                }
            }, Assert::assertNotNull), TimeValue.timeValueMinutes(1L));

        engineResetLatch.await();

        closeShardThread.join();

        // close store.
        closeShard(shard, false);
    }

    @Test
    public void testResetEngineWithBrokenTranslog() throws Exception {
        IndexShard shard = newStartedShard(false);
        updateMappings(shard, IndexMetadata.builder(shard.indexSettings.getIndexMetadata())
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}").build());
        final List<Translog.Operation> operations = Stream.concat(
            IntStream.range(0, randomIntBetween(0, 10)).mapToObj(n -> new Translog.Index(
                "1",
                0,
                shard.getPendingPrimaryTerm(),
                1,
                "{\"foo\" : \"bar\"}".getBytes(StandardCharsets.UTF_8),
                null,
                -1)
            ),
            // entries with corrupted source
            IntStream.range(0, randomIntBetween(1, 10)).mapToObj(n -> new Translog.Index(
                "1",
                0,
                shard.getPendingPrimaryTerm(),
                1,
                "{\"foo\" : \"bar}".getBytes(StandardCharsets.UTF_8),
                null,
                -1)
            )).collect(Collectors.toList());
        Randomness.shuffle(operations);
        final CountDownLatch engineResetLatch = new CountDownLatch(1);
        shard.acquireAllReplicaOperationsPermits(shard.getOperationPrimaryTerm(), shard.getLastKnownGlobalCheckpoint(), 0L,
            ActionListener.wrap(
                r -> {
                    try (r) {
                        Translog.Snapshot snapshot = TestTranslog.newSnapshotFromOperations(operations);
                        final MapperParsingException error = expectThrows(MapperParsingException.class,
                            () -> shard.runTranslogRecovery(shard.getEngine(), snapshot, Engine.Operation.Origin.LOCAL_RESET, () -> {}));
                        assertThat(error.getMessage(), containsString("failed to parse field [foo] of type [text]"));
                    } finally {
                        engineResetLatch.countDown();
                    }
                },
                e -> {
                    throw new AssertionError(e);
                }), TimeValue.timeValueMinutes(1));
        engineResetLatch.await();
        closeShards(shard);
    }

    /**
     * This test simulates a scenario seen rarely in ConcurrentSeqNoVersioningIT. While engine is inside
     * resetEngineToGlobalCheckpoint snapshot metadata could fail
     */
    @Test
    public void testSnapshotWhileResettingEngine() throws Exception {
        CountDownLatch readyToSnapshotLatch = new CountDownLatch(1);
        CountDownLatch snapshotDoneLatch = new CountDownLatch(1);
        IndexShard shard = newStartedShard(false, Settings.EMPTY, config -> new InternalEngine(config) {
            @Override
            public InternalEngine recoverFromTranslog(TranslogRecoveryRunner translogRecoveryRunner,
                                                      long recoverUpToSeqNo) throws IOException {
                InternalEngine internalEngine = super.recoverFromTranslog(translogRecoveryRunner, recoverUpToSeqNo);
                readyToSnapshotLatch.countDown();
                try {
                    snapshotDoneLatch.await();
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return internalEngine;
            }
        });

        indexOnReplicaWithGaps(shard, between(0, 1000), Math.toIntExact(shard.getLocalCheckpoint()));
        final long globalCheckpoint = randomLongBetween(shard.getLastKnownGlobalCheckpoint(), shard.getLocalCheckpoint());
        shard.updateGlobalCheckpointOnReplica(globalCheckpoint, "test");

        Thread snapshotThread = new Thread(() -> {
            try {
                readyToSnapshotLatch.await();
                shard.snapshotStoreMetadata();
                try (Engine.IndexCommitRef indexCommitRef = shard.acquireLastIndexCommit(randomBoolean())) {
                    shard.store().getMetadata(indexCommitRef.getIndexCommit());
                }
                try (Engine.IndexCommitRef indexCommitRef = shard.acquireSafeIndexCommit()) {
                    shard.store().getMetadata(indexCommitRef.getIndexCommit());
                }
            } catch (InterruptedException | IOException e) {
                throw new AssertionError(e);
            } finally {
                snapshotDoneLatch.countDown();
            }
        });

        snapshotThread.start();

        final CountDownLatch engineResetLatch = new CountDownLatch(1);
        shard.acquireAllReplicaOperationsPermits(shard.getOperationPrimaryTerm(), shard.getLastKnownGlobalCheckpoint(), 0L,
            ActionListener.wrap(r -> {
                try (r) {
                    shard.resetEngineToGlobalCheckpoint();
                } finally {
                    engineResetLatch.countDown();
                }
            }, Assert::assertNotNull), TimeValue.timeValueMinutes(1L));

        engineResetLatch.await();

        snapshotThread.join();

        closeShard(shard, false);
    }

    @Test
    public void testClosedIndicesSkipSyncGlobalCheckpoint() throws Exception {
        ShardId shardId = new ShardId("index", "_na_", 0);
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder("index")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(Settings.builder()
                .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 2)
            )
            .state(IndexMetadata.State.CLOSE).primaryTerm(0, 1);
        ShardRouting shardRouting = TestShardRouting.newShardRouting(
            shardId,
            randomAlphaOfLength(8),
            true,
            ShardRoutingState.INITIALIZING,
            RecoverySource.EmptyStoreRecoverySource.INSTANCE
        );
        AtomicBoolean synced = new AtomicBoolean();
        IndexShard primaryShard = newShard(
            shardRouting,
            indexMetadata.build(),
            null,
            new InternalEngineFactory(),
            () -> synced.set(true)
        );
        recoverShardFromStore(primaryShard);
        IndexShard replicaShard = newShard(shardId, false);
        recoverReplica(replicaShard, primaryShard, true);
        int numDocs = between(1, 10);
        for (int i = 0; i < numDocs; i++) {
            indexDoc(primaryShard, Integer.toString(i));
        }
        assertThat(primaryShard.getLocalCheckpoint(), equalTo(numDocs - 1L));
        primaryShard.updateLocalCheckpointForShard(replicaShard.shardRouting.allocationId().getId(), primaryShard.getLocalCheckpoint());
        long globalCheckpointOnReplica = randomLongBetween(SequenceNumbers.NO_OPS_PERFORMED, primaryShard.getLocalCheckpoint());
        primaryShard.updateGlobalCheckpointForShard(replicaShard.shardRouting.allocationId().getId(), globalCheckpointOnReplica);
        primaryShard.maybeSyncGlobalCheckpoint("test");
        assertFalse("closed indices should skip global checkpoint sync", synced.get());
        closeShards(primaryShard, replicaShard);
    }


    @Test
    public void testRelocateMissingTarget() throws Exception {
        final IndexShard shard = newStartedShard(true);
        final ShardRouting original = shard.routingEntry();
        final ShardRouting toNode1 = ShardRoutingHelper.relocate(original, "node_1");
        IndexShardTestCase.updateRoutingEntry(shard, toNode1);
        IndexShardTestCase.updateRoutingEntry(shard, original);
        final ShardRouting toNode2 = ShardRoutingHelper.relocate(original, "node_2");
        IndexShardTestCase.updateRoutingEntry(shard, toNode2);
        final AtomicBoolean relocated = new AtomicBoolean();
        final IllegalStateException error = expectThrows(IllegalStateException.class,
            () -> shard.relocated(toNode1.getTargetRelocatingShard().allocationId().getId(), ctx -> relocated.set(true)));
        assertThat(error.getMessage(), equalTo("relocation target [" + toNode1.getTargetRelocatingShard().allocationId().getId()
            + "] is no longer part of the replication group"));
        assertFalse(relocated.get());
        shard.relocated(toNode2.getTargetRelocatingShard().allocationId().getId(), ctx -> relocated.set(true));
        assertTrue(relocated.get());
        closeShards(shard);
    }

    @Test
    public void testConcurrentAcquireAllReplicaOperationsPermitsWithPrimaryTermUpdate() throws Exception {
        final IndexShard replica = newStartedShard(false);
        indexOnReplicaWithGaps(replica, between(0, 1000), Math.toIntExact(replica.getLocalCheckpoint()));

        final int nbTermUpdates = randomIntBetween(1, 5);

        for (int i = 0; i < nbTermUpdates; i++) {
            long opPrimaryTerm = replica.getOperationPrimaryTerm() + 1;
            final long globalCheckpoint = replica.getLastKnownGlobalCheckpoint();
            final long maxSeqNoOfUpdatesOrDeletes = replica.getMaxSeqNoOfUpdatesOrDeletes();

            final int operations = scaledRandomIntBetween(5, 32);
            final CyclicBarrier barrier = new CyclicBarrier(1 + operations);
            final CountDownLatch latch = new CountDownLatch(operations);

            final Thread[] threads = new Thread[operations];
            for (int j = 0; j < operations; j++) {
                threads[j] = new Thread(() -> {
                    try {
                        barrier.await();
                    } catch (final BrokenBarrierException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    replica.acquireAllReplicaOperationsPermits(
                        opPrimaryTerm,
                        globalCheckpoint,
                        maxSeqNoOfUpdatesOrDeletes,
                        new ActionListener<Releasable>() {
                            @Override
                            public void onResponse(final Releasable releasable) {
                                try (Releasable ignored = releasable) {
                                    assertThat(replica.getPendingPrimaryTerm(), greaterThanOrEqualTo(opPrimaryTerm));
                                    assertThat(replica.getOperationPrimaryTerm(), equalTo(opPrimaryTerm));
                                } finally {
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void onFailure(final Exception e) {
                                try {
                                    throw new RuntimeException(e);
                                } finally {
                                    latch.countDown();
                                }
                            }
                        }, TimeValue.timeValueMinutes(30L));
                });
                threads[j].start();
            }
            barrier.await();
            latch.await();

            for (Thread thread : threads) {
                thread.join();
            }
        }

        closeShard(replica, false);
    }

    class Result {
        private final int localCheckpoint;
        private final int maxSeqNo;

        Result(final int localCheckpoint, final int maxSeqNo) {
            this.localCheckpoint = localCheckpoint;
            this.maxSeqNo = maxSeqNo;
        }
    }

    /**
     * Index on the specified shard while introducing sequence number gaps.
     *
     * @param indexShard the shard
     * @param operations the number of operations
     * @param offset     the starting sequence number
     * @return a pair of the maximum sequence number and whether or not a gap was introduced
     * @throws IOException if an I/O exception occurs while indexing on the shard
     */
    private Result indexOnReplicaWithGaps(
            final IndexShard indexShard,
            final int operations,
            final int offset) throws IOException {
        int localCheckpoint = offset;
        int max = offset;
        boolean gap = false;
        Set<String> ids = new HashSet<>();
        for (int i = offset + 1; i < operations; i++) {
            if (!rarely() || i == operations - 1) { // last operation can't be a gap as it's not a gap anymore
                final String id = ids.isEmpty() || randomBoolean() ? Integer.toString(i) : randomFrom(ids);
                if (ids.add(id) == false) { // this is an update
                    indexShard.advanceMaxSeqNoOfUpdatesOrDeletes(i);
                }
                SourceToParse sourceToParse = new SourceToParse(indexShard.shardId().getIndexName(), id,
                        new BytesArray("{}"), XContentType.JSON);
                indexShard.applyIndexOperationOnReplica(
                    i,
                    1,
                    -1,
                    false,
                    sourceToParse
                );
                if (!gap && i == localCheckpoint + 1) {
                    localCheckpoint++;
                }
                max = i;
            } else {
                gap = true;
            }
            if (rarely()) {
                indexShard.flush(new FlushRequest());
            }
        }
        indexShard.sync(); // advance local checkpoint
        assert localCheckpoint == indexShard.getLocalCheckpoint();
        assert !gap || (localCheckpoint != max);
        return new Result(localCheckpoint, max);
    }

    @Test
    public void testTypelessGet() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .build();
        IndexMetadata metaData = IndexMetadata.builder("index")
            .putMapping("default", "{ \"properties\": { \"foo\":  { \"type\": \"text\"}}}")
            .settings(settings)
            .primaryTerm(0, 1).build();
        IndexShard shard = newShard(new ShardId(metaData.getIndex(), 0), true, "n1", metaData, null);
        recoverShardFromStore(shard);
        Engine.IndexResult indexResult = indexDoc(shard, "0", "{\"foo\" : \"bar\"}");
        assertTrue(indexResult.isCreated());

        org.elasticsearch.index.engine.Engine.GetResult getResult
            = shard.get(new Engine.Get("0", new Term("_id", Uid.encodeId("0"))));
        assertThat(getResult, is(not(Engine.GetResult.NOT_EXISTS)));
        getResult.close();

        closeShards(shard);
    }

    @Test
    public void testDoNotTrimCommitsWhenOpenReadOnlyEngine() throws Exception {
        IndexShard shard = newStartedShard(false, Settings.EMPTY, new InternalEngineFactory());
        long numDocs = randomLongBetween(1, 20);
        long seqNo = 0;
        for (long i = 0; i < numDocs; i++) {
            if (rarely()) {
                seqNo++; // create gaps in sequence numbers
            }
            shard.applyIndexOperationOnReplica(
                seqNo, 1, UNSET_AUTO_GENERATED_TIMESTAMP, false,
                new SourceToParse(
                    shard.shardId.getIndexName(),
                    Long.toString(i),
                    new BytesArray("{}"),
                    XContentType.JSON)
            );
            shard.updateGlobalCheckpointOnReplica(shard.getLocalCheckpoint(), "test");
            if (randomInt(100) < 10) {
                shard.flush(new FlushRequest());
            }
            seqNo++;
        }
        shard.flush(new FlushRequest());
        assertThat(shard.docStats().getCount(), equalTo(numDocs));
        ShardRouting replicaRouting = shard.routingEntry();
        ShardRouting readonlyShardRouting = newShardRouting(
            replicaRouting.shardId(),
            replicaRouting.currentNodeId(),
            true,
            ShardRoutingState.INITIALIZING,
            RecoverySource.ExistingStoreRecoverySource.INSTANCE);
        IndexShard readonlyShard = reinitShard(
            shard,
            readonlyShardRouting,
            shard.indexSettings.getIndexMetadata(),
            engineConfig -> new ReadOnlyEngine(engineConfig, null, null, true, Function.identity()) {

                @Override
                protected void ensureMaxSeqNoEqualsToGlobalCheckpoint(SeqNoStats seqNoStats) {
                    // just like a following shard, we need to skip this check for now.
                }
            }
        );
        DiscoveryNode localNode = new DiscoveryNode(
            "foo",
            buildNewFakeTransportAddress(),
            Map.of(),
            Set.of(),
            Version.CURRENT);
        readonlyShard.markAsRecovering("store", new RecoveryState(readonlyShard.routingEntry(), localNode, null));
        assertTrue(readonlyShard.recoverFromStore());
        assertThat(readonlyShard.docStats().getCount(), equalTo(numDocs));
        closeShards(readonlyShard);
    }
}
