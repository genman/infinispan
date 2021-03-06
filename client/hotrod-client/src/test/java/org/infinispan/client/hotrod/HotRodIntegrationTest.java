package org.infinispan.client.hotrod;

import org.infinispan.Cache;
import org.infinispan.config.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.marshall.Marshaller;
import org.infinispan.marshall.jboss.JBossMarshaller;
import org.infinispan.server.core.CacheValue;
import org.infinispan.server.hotrod.HotRodServer;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.util.ByteArrayKey;            
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import static junit.framework.Assert.assertEquals;
import static org.testng.Assert.assertNull;


/**
 * @author mmarkus
 * @since 4.1
 */
@Test (testName = "client.hotrod.HotRodClientIntegrationTest", groups = "functional" )
public class HotRodIntegrationTest extends SingleCacheManagerTest {

   private static final Log log = LogFactory.getLog(HotRodIntegrationTest.class);

   private static final String CACHE_NAME = "replSync";
   private Cache cache;
   private Cache defaultCache;

   RemoteCache defaultRemote;
   RemoteCache remoteCache;
   private RemoteCacheManager remoteCacheManager;

   protected HotRodServer hotrodServer;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      Configuration standaloneConfig = getDefaultStandaloneConfig(false);
      cacheManager = TestCacheManagerFactory.createLocalCacheManager();
      cacheManager.defineConfiguration(CACHE_NAME, standaloneConfig);
      defaultCache = cacheManager.getCache();
      cache = cacheManager.getCache(CACHE_NAME);


      //pass the config file to the cache
      hotrodServer = TestHelper.startHotRodServer(cacheManager);
      log.info("Started server on port: " + hotrodServer.getPort());

      remoteCacheManager = getRemoteCacheManager();
      defaultRemote = remoteCacheManager.getCache();
      remoteCache = remoteCacheManager.getCache(CACHE_NAME);
      return cacheManager;
   }

   protected RemoteCacheManager getRemoteCacheManager() {
      Properties config = new Properties();
      config.put("infinispan.client.hotrod.server_list", "127.0.0.1:" + hotrodServer.getPort());
      return new RemoteCacheManager(config);
   }


   @AfterClass 
   public void testDestroyRemoteCacheFactory() {
      remoteCacheManager.stop();
      hotrodServer.stop();
   }

   public void testPut() throws Exception {
      assert null == remoteCache.put("aKey", "aValue");
      assertCacheContains(cache, "aKey", "aValue");
      assert null == defaultRemote.put("otherKey", "otherValue");
      assertCacheContains(defaultCache, "otherKey", "otherValue");
      assert remoteCache.containsKey("aKey");
      assert defaultRemote.containsKey("otherKey");
      assert remoteCache.get("aKey").equals("aValue");
      assert defaultRemote.get("otherKey").equals("otherValue");
   }

   public void testRemove() throws Exception {
      assert null == remoteCache.put("aKey", "aValue");
      assertCacheContains(cache, "aKey", "aValue");

      assert remoteCache.get("aKey").equals("aValue");

      assert null == remoteCache.remove("aKey");
      assertCacheContains(cache, "aKey", null);
      assert !remoteCache.containsKey("aKey");
   }

   public void testContains() {
      assert !remoteCache.containsKey("aKey");
      remoteCache.put("aKey", "aValue");
      assert remoteCache.containsKey("aKey");
   }

   public void testGetVersionedCacheEntry() {
      VersionedValue value = remoteCache.getVersioned("aKey");
      assertNull(remoteCache.getVersioned("aKey"), "expected null but received: " + value);
      remoteCache.put("aKey", "aValue");
      assert remoteCache.get("aKey").equals("aValue");
      VersionedValue valueBinary = remoteCache.getVersioned("aKey");
      assert valueBinary != null;
      assertEquals(valueBinary.getValue(), "aValue");
      log.info("Version is: " + valueBinary.getVersion());

      //now put the same value
      remoteCache.put("aKey", "aValue");
      VersionedValue entry2 = remoteCache.getVersioned("aKey");
      assertEquals(entry2.getValue(), "aValue");

      assert entry2.getVersion() != valueBinary.getVersion();
      assert !valueBinary.equals(entry2);

      //now put a different value
      remoteCache.put("aKey", "anotherValue");
      VersionedValue entry3 = remoteCache.getVersioned("aKey");
      assertEquals(entry3.getValue(), "anotherValue");
      assert entry3.getVersion() != entry2.getVersion();
      assert !entry3.equals(entry2);
   }

   public void testReplace() {
      assert null == remoteCache.replace("aKey", "anotherValue");
      remoteCache.put("aKey", "aValue");
      assert null == remoteCache.replace("aKey", "anotherValue");
      assert remoteCache.get("aKey").equals("anotherValue");
   }

   public void testReplaceIfUnmodified() {
      assert null == remoteCache.replace("aKey", "aValue");


      remoteCache.put("aKey", "aValue");
      VersionedValue valueBinary = remoteCache.getVersioned("aKey");
      assert remoteCache.replaceWithVersion("aKey", "aNewValue", valueBinary.getVersion());

      VersionedValue entry2 = remoteCache.getVersioned("aKey");
      assert entry2.getVersion() != valueBinary.getVersion();
      assertEquals(entry2.getValue(), "aNewValue");

      assert !remoteCache.replaceWithVersion("aKey", "aNewValue", valueBinary.getVersion());
   }

   public void testRemoveIfUnmodified() {
      assert !remoteCache.removeWithVersion("aKey", 12321212l);

      remoteCache.put("aKey", "aValue");
      VersionedValue valueBinary = remoteCache.getVersioned("aKey");
      assert remoteCache.removeWithVersion("aKey", valueBinary.getVersion());
      assert !cache.containsKey("aKey");

      remoteCache.put("aKey", "aNewValue");

      VersionedValue entry2 = remoteCache.getVersioned("aKey");
      assert entry2.getVersion() != valueBinary.getVersion();
      assertEquals(entry2.getValue(), "aNewValue");

      assert  !remoteCache.removeWithVersion("aKey", valueBinary.getVersion());
   }

   public void testPutIfAbsent() {
      remoteCache.put("aKey", "aValue");
      assert null == remoteCache.putIfAbsent("aKey", "anotherValue");
      assertEquals(remoteCache.get("aKey"),"aValue");

      assertEquals(remoteCache.get("aKey"),"aValue");
      assert remoteCache.containsKey("aKey");

      assert true : remoteCache.replace("aKey", "anotherValue");
   }

   public void testClear() {
      remoteCache.put("aKey", "aValue");
      remoteCache.put("aKey2", "aValue");
      remoteCache.clear();
      assert !remoteCache.containsKey("aKey");
      assert !remoteCache.containsKey("aKey2");
      assert cache.isEmpty();
   }

   private void assertCacheContains(Cache cache, String key, String value) throws Exception {
      Marshaller marshaller = new JBossMarshaller();
      byte[] keyBytes = marshaller.objectToByteBuffer(key, 64);
      byte[] valueBytes = marshaller.objectToByteBuffer(value, 64);
      ByteArrayKey cacheKey = new ByteArrayKey(keyBytes);
      CacheValue cacheValue = (CacheValue) cache.get(cacheKey);
      if (value == null) {
         assert cacheValue == null : "Expected null value but received: " + cacheValue;
      } else {
         assert Arrays.equals(valueBytes, (byte[])cacheValue.data());
      }
   }
}
