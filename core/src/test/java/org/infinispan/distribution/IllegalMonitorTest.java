/* 
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.infinispan.distribution;

import org.infinispan.AdvancedCache;
import org.infinispan.context.Flag;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;


/**
 * See ISPN-919 : It's possible we try to release a lock we didn't acquire.
 * This is by design, so that we don't have to keep track of them:
 * @see org.infinispan.util.concurrent.locks.LockManager#possiblyLocked(org.infinispan.container.entries.CacheEntry) 
 * 
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2011 Red Hat Inc.
 * @since 5.0
 */
@Test(groups = "functional", testName = IllegalMonitorTest.TEST_NAME)
public class IllegalMonitorTest extends BaseDistFunctionalTest {

   protected static final String TEST_NAME = "distribution.IllegalMonitorTest";
   private static final AtomicInteger sequencer = new AtomicInteger();
   private final String key = TEST_NAME;

   public IllegalMonitorTest() {
      sync = true;
      tx = false;
      testRetVals = true;
      l1CacheEnabled = true;
   }

   /**
    * This test would throw many IllegalMonitorStateException if they where not hidden by the
    * implementation of the LockManager
    * 
    * @throws InterruptedException
    */
   @Test(threadPoolSize = 7, invocationCount = 21)
   public void testScenario() throws InterruptedException {
      int myId = sequencer.incrementAndGet();
      AdvancedCache cache = this.caches.get(myId % this.INIT_CLUSTER_SIZE).getAdvancedCache();
      for (int i = 0; i < 100; i++) {
         if (i % 4 == 0)
            cache.withFlags(Flag.SKIP_LOCKING).put(key, "value");
         cache.withFlags(Flag.SKIP_LOCKING).remove(key);
      }
      cache.clear();
   }

}
