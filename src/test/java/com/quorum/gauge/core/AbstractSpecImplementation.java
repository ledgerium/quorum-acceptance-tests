/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.quorum.gauge.core;

import com.quorum.gauge.common.Context;
import com.quorum.gauge.common.QuorumNetworkProperty;
import com.quorum.gauge.services.*;
import com.thoughtworks.gauge.datastore.DataStore;
import com.thoughtworks.gauge.datastore.DataStoreFactory;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;

import java.math.BigInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractSpecImplementation {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSpecImplementation.class);

    @Autowired
    protected ContractService contractService;

    @Autowired
    protected RawContractService rawContractService;

    @Autowired
    protected TransactionService transactionService;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected OkHttpClient okHttpClient;

    @Autowired
    protected UtilService utilService;

    @Autowired
    protected QuorumBootService quorumBootService;

    @Autowired
    private QuorumNetworkProperty networkProperty;

    protected BigInteger currentBlockNumber() {
        return mustHaveValue(DataStoreFactory.getScenarioDataStore(), "blocknumber", BigInteger.class);
    }

    protected void saveCurrentBlockNumber() {
        BigInteger blockNumber = utilService.getCurrentBlockNumber().toBlocking().first().getBlockNumber();
        DataStoreFactory.getScenarioDataStore().put("blocknumber", blockNumber);
    }

    protected <T> T mustHaveValue(DataStore ds, String key, Class<T> clazz) {
        Object v = ds.get(key);
        assertThat(v).as("Value class for key [" + key + "] in Gauge DataStore").isInstanceOf(clazz);
        assertThat(v).as("Value for key [" + key + "] in Gauge DataStore").isNotNull();
        return (T) v;
    }

    protected <T> T haveValue(DataStore ds, String key, Class<T> clazz, T defaultValue) {
        Object v = ds.get(key);
        if (v == null) {
            return defaultValue;
        }
        return mustHaveValue(ds, key, clazz);
    }

    protected void waitForBlockHeight(int currentBlockHeight, int untilBlockHeight) {
        AtomicInteger lastBlockHeight = new AtomicInteger(currentBlockHeight);
        utilService.getCurrentBlockNumber().flatMap(ethBlockNumber -> {
            if (ethBlockNumber.getBlockNumber().intValue() < untilBlockHeight) {
                logger.debug("Current block height is {}, wait until {}", ethBlockNumber.getBlockNumber(), untilBlockHeight);
                lastBlockHeight.set(ethBlockNumber.getBlockNumber().intValue());
                throw new RuntimeException("let's wait and retry");
            }
            return Observable.just(true);
        }).retryWhen(
                attempts -> attempts.zipWith(Observable.range(1, untilBlockHeight), (total, i) -> i)
                        .flatMap(i -> Observable.timer(3, TimeUnit.SECONDS))
                        .doOnCompleted(() -> {
                            throw new RuntimeException("Timed out! Can't wait until block height is " + untilBlockHeight + " higher. Last block height was " + lastBlockHeight.get());
                        })
        ).toBlocking().first();
    }

    // created a fixed thread pool executor and inject quorum connection factory into the scheduled thread
    protected Scheduler networkAwaredScheduler(int threadCount) {
        QuorumNodeConnectionFactory connectionFactory = Context.getConnectionFactory();
        Executor executor = Executors.newFixedThreadPool(Math.min(threadCount, 100), new ThreadFactory() {
            private int count = 0;

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "RxJavaCustom-" + (count++)) {
                    @Override
                    public void run() {
                        Context.setConnectionFactory(connectionFactory);
                        super.run();
                    }
                };
            }
        });
        return Schedulers.from(executor);
    }

    /**
     * @return number of nodes specified in the configuration yml
     */
    protected int numberOfQuorumNodes() {
        return networkProperty.getNodes().size();
    }
}
