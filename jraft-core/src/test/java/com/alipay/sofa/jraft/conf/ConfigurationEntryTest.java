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
package com.alipay.sofa.jraft.conf;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.test.TestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigurationEntryTest {
    @Test
    public void testStuffMethods() {
        ConfigurationEntry entry = TestUtils.getConfEntry("localhost:8081,localhost:8082,localhost:8083", null);
        assertTrue(entry.isStable());
        assertFalse(entry.isEmpty());
        assertTrue(entry.contains(new PeerId("localhost", 8081)));
        assertTrue(entry.contains(new PeerId("localhost", 8082)));
        assertTrue(entry.contains(new PeerId("localhost", 8083)));
        assertEquals(entry.listPeers(), new HashSet<>(Arrays.asList(new PeerId("localhost", 8081),
            new PeerId("localhost", 8082), new PeerId("localhost", 8083))));

    }

    @Test
    public void testIsStable() {
        ConfigurationEntry entry = TestUtils.getConfEntry("localhost:8081,localhost:8082,localhost:8083",
            "localhost:8080,localhost:8081,localhost:8082");
        assertFalse(entry.isStable());
        assertEquals(4, entry.listPeers().size());
        assertTrue(entry.contains(new PeerId("localhost", 8080)));
        assertTrue(entry.contains(new PeerId("localhost", 8081)));
        assertTrue(entry.contains(new PeerId("localhost", 8082)));
        assertTrue(entry.contains(new PeerId("localhost", 8083)));
    }
}
