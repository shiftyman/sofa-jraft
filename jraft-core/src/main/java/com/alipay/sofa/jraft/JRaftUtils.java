/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft;

import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;

import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.core.NodeImpl;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.BootstrapOptions;
import com.alipay.sofa.jraft.util.Endpoint;
import com.alipay.sofa.jraft.util.ThreadPoolUtil;

/**
 * Some helper methods for jraft usage.
 *
 * @author boyan (boyan@alibaba-inc.com)
 *
 * 2018-Apr-23 3:48:45 PM
 */
public final class JRaftUtils {

    private JRaftUtils() {
    }

    /**
     * Bootstrap a non-empty raft node.
     *
     * @param opts options of bootstrap
     * @return true if bootstrap success
     */
    public static boolean bootstrap(BootstrapOptions opts) throws InterruptedException {
        final NodeImpl node = new NodeImpl();
        final boolean ret = node.bootstrap(opts);
        node.shutdown();
        node.join();
        return ret;
    }

    /**
     * Create a executor with size.
     *
     * @param prefix thread name prefix
     * @param number thread number
     * @return a new {@link ThreadPoolExecutor} instance
     */
    public static Executor createExecutor(final String prefix, int number) {
        if (number <= 0) {
            return null;
        }
        return ThreadPoolUtil.newThreadPool(prefix, true, number, number, 60L,
                new SynchronousQueue<>(), createThreadFactory(prefix));
    }

    /**
     * Create a thread factory.
     *
     * @since 0.0.3
     * @param prefixName the prefix name of thread
     * @return a new {@link ThreadFactory} instance
     */
    public static ThreadFactory createThreadFactory(final String prefixName) {
        return new ThreadFactory() {
            private final AtomicInteger c = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                final Thread t = new Thread(r);
                t.setName(prefixName + c.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /**
     * Create a configuration from a string in the form of "host1:port1[:idx],host2:port2[:idx]......",
     * returns a empty configuration when string is blank.
     */
    public static Configuration getConfiguration(String s) {
        final Configuration conf = new Configuration();
        if (StringUtils.isBlank(s)) {
            return conf;
        }
        if (conf.parse(s)) {
            return conf;
        }
        throw new IllegalArgumentException("Invalid conf str:" + s);
    }

    /**
     * Create a peer from a string in the form of "host:port[:idx]",
     * returns a empty peer when string is blank.
     */
    public static PeerId getPeerId(String s) {
        final PeerId peer = new PeerId();
        if (StringUtils.isBlank(s)) {
            return peer;
        }
        if (peer.parse(s)) {
            return peer;
        }
        throw new IllegalArgumentException("Invalid peer str:" + s);
    }

    /**
     * Create a Endpoint instance from  a string in the form of "host:port",
     * returns null when string is blank.
     */
    public static Endpoint getEndPoint(String s) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        final String[] tmps = s.split(":");
        if (tmps.length != 2) {
            throw new IllegalArgumentException("Invalid endpoint string: " + s);
        }
        return new Endpoint(tmps[0], Integer.parseInt(tmps[1]));
    }
}
