/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.persistence.index.impl;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.usergrid.persistence.core.future.BetterFuture;
import org.apache.usergrid.persistence.core.metrics.MetricsFactory;
import org.apache.usergrid.persistence.index.IndexBatchBuffer;
import org.apache.usergrid.persistence.index.IndexFig;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.support.replication.ShardReplicationOperationRequestBuilder;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;


/**
 * Buffer index requests into sets to send.
 */
@Singleton
public class IndexBatchBufferImpl implements IndexBatchBuffer {

    private static final Logger log = LoggerFactory.getLogger(IndexBatchBufferImpl.class);
    private final MetricsFactory metricsFactory;
    private final Counter indexSizeCounter;
    private final Client client;
    private final FailureMonitorImpl failureMonitor;
    private final IndexFig config;
    private final Timer flushTimer;
    private final ArrayBlockingQueue<RequestBuilderContainer> blockingQueue;
    private Observable<List<RequestBuilderContainer>> consumer;
    private Producer producer;

    @Inject
    public IndexBatchBufferImpl(final IndexFig config, final EsProvider provider, final MetricsFactory metricsFactory){
        this.metricsFactory = metricsFactory;
        this.flushTimer = metricsFactory.getTimer(IndexBatchBuffer.class, "index.buffer.flush");
        this.indexSizeCounter =  metricsFactory.getCounter(IndexBatchBuffer.class, "index.buffer.size");
        this.config = config;
        this.blockingQueue = new ArrayBlockingQueue<>(config.getIndexBatchSize());
        this.failureMonitor = new FailureMonitorImpl(config,provider);
        this.producer = new Producer();
        this.client = provider.getClient();

        consumer();
    }

    private void consumer() {
        //batch up sets of some size and send them in batch
        this.consumer = Observable.create(producer)
                .buffer(config.getIndexBufferTimeout(), TimeUnit.MILLISECONDS, config.getIndexBufferSize())
                .doOnNext(new Action1<List<RequestBuilderContainer>>() {
                    @Override
                    public void call(List<RequestBuilderContainer> containerList) {
                        for (RequestBuilderContainer container : containerList) {
                            blockingQueue.add(container);
                        }
                        flushTimer.time();
                        indexSizeCounter.dec(containerList.size());
                        execute(config.isForcedRefresh());
                    }
                });
        consumer.subscribe();
    }

    @Override
    public BetterFuture<ShardReplicationOperationRequestBuilder> put(IndexRequestBuilder builder){
        RequestBuilderContainer container = new RequestBuilderContainer(builder);
        metricsFactory.getCounter(IndexBatchBuffer.class,"index.buffer.size").inc();
        producer.put(container);
        return container.getFuture();
    }

    @Override
    public BetterFuture<ShardReplicationOperationRequestBuilder> put(DeleteRequestBuilder builder){
        RequestBuilderContainer container = new RequestBuilderContainer(builder);
        metricsFactory.getCounter(IndexBatchBuffer.class,"index.buffer.size").inc();
        producer.put(container);
        return container.getFuture();
    }



    private static class RequestBuilderContainer{
        private final ShardReplicationOperationRequestBuilder builder;
        private final BetterFuture<ShardReplicationOperationRequestBuilder> containerFuture;

        public RequestBuilderContainer(ShardReplicationOperationRequestBuilder builder){
            final RequestBuilderContainer parent = this;
            this.builder = builder;
            this.containerFuture = new BetterFuture<>(new Callable<ShardReplicationOperationRequestBuilder>() {
                @Override
                public ShardReplicationOperationRequestBuilder call() throws Exception {
                    return parent.getBuilder();
                }
            });
        }

        public ShardReplicationOperationRequestBuilder getBuilder(){
            return builder;
        }
        private void done(){
            containerFuture.done();
        }
        public BetterFuture<ShardReplicationOperationRequestBuilder> getFuture(){
            return containerFuture;
        }
    }
    /**
     * Execute the request, check for errors, then re-init the batch for future use
     */
    private void execute( boolean refresh) {

        if (blockingQueue.size() == 0) {
            return;
        }

        BulkRequestBuilder bulkRequest = initRequest(refresh);

        Collection<RequestBuilderContainer> containerCollection = new ArrayList<>(config.getIndexBatchSize());
        blockingQueue.drainTo(containerCollection);
        int count = 0;
        //clear the queue or proceed to buffersize
        for (RequestBuilderContainer container : containerCollection) {

            ShardReplicationOperationRequestBuilder builder = container.getBuilder();
            //only handle two types of requests for now, annoyingly there is no base class implementation on BulkRequest
            if (builder instanceof IndexRequestBuilder) {
                bulkRequest.add((IndexRequestBuilder) builder);
            }
            if (builder instanceof DeleteRequestBuilder) {
                bulkRequest.add((DeleteRequestBuilder) builder);
            }

            if (count++ == config.getIndexBatchSize()) {
                sendRequest(bulkRequest);
                bulkRequest = initRequest(refresh);

            }
        }
        sendRequest(bulkRequest);
        for (RequestBuilderContainer container : containerCollection) {
            container.done();
        }
    }

    private BulkRequestBuilder initRequest(boolean refresh) {
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        bulkRequest.setConsistencyLevel(WriteConsistencyLevel.fromString(config.getWriteConsistencyLevel()));
        bulkRequest.setRefresh(refresh);
        return bulkRequest;
    }

    private void sendRequest(BulkRequestBuilder bulkRequest) {
        //nothing to do, we haven't added anthing to the index
        if (bulkRequest.numberOfActions() == 0) {
            return;
        }

        final BulkResponse responses;

        try {
            responses = bulkRequest.execute().actionGet();
        } catch (Throwable t) {
            log.error("Unable to communicate with elasticsearch");
            failureMonitor.fail("Unable to execute batch", t);
            throw t;
        }

        failureMonitor.success();

        for (BulkItemResponse response : responses) {
            if (response.isFailed()) {
                throw new RuntimeException("Unable to index documents.  Errors are :"
                        + response.getFailure().getMessage());
            }
        }
    }


    private static class Producer implements Observable.OnSubscribe<RequestBuilderContainer> {

        private Subscriber<? super RequestBuilderContainer> subscriber;

        @Override
        public void call(Subscriber<? super RequestBuilderContainer> subscriber) {
            this.subscriber = subscriber;
        }

        public void put(RequestBuilderContainer r){
            subscriber.onNext(r);
        }
    }

}