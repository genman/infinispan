package org.infinispan.server.memcached

import org.infinispan.server.core.AbstractProtocolServer
import org.infinispan.server.core.transport.{Decoder, Encoder}
import java.util.concurrent.Executors
import org.infinispan.manager.EmbeddedCacheManager
import java.util.Properties

/**
 * Memcached server defining its decoder/encoder settings. In fact, Memcached does not use an encoder since there's
 * no really common headers between protocol operations.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
class MemcachedServer extends AbstractProtocolServer("Memcached") {

   protected lazy val scheduler = Executors.newScheduledThreadPool(1)

   override def start(p: Properties, cacheManager: EmbeddedCacheManager) {
      val properties = if (p == null) new Properties else p
      super.start(properties, cacheManager, 11211)
   }

   override def getEncoder: Encoder = null

   override def getDecoder: Decoder = new MemcachedDecoder(getCacheManager.getCache[String, MemcachedValue], scheduler)

   override def stop {
      super.stop
      scheduler.shutdown
   }
}