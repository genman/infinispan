package org.infinispan.factories;

import org.infinispan.CacheException;
import org.infinispan.commands.module.ModuleCommandFactory;
import org.infinispan.config.GlobalConfiguration;
import org.infinispan.factories.annotations.SurvivesRestarts;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.jmx.CacheManagerJmxRegistration;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.lifecycle.ModuleLifecycle;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.manager.ReflectionCache;
import org.infinispan.notifications.cachemanagerlistener.CacheManagerNotifier;
import org.infinispan.notifications.cachemanagerlistener.CacheManagerNotifierImpl;
import org.infinispan.util.ModuleProperties;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.management.MBeanServerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.infinispan.config.GlobalConfiguration.ShutdownHookBehavior.DEFAULT;
import static org.infinispan.config.GlobalConfiguration.ShutdownHookBehavior.REGISTER;

import static org.infinispan.config.GlobalConfiguration.ShutdownHookBehavior.DEFAULT;
import static org.infinispan.config.GlobalConfiguration.ShutdownHookBehavior.REGISTER;

/**
 * A global component registry where shared components are stored.
 *
 * @author Manik Surtani
 * @since 4.0
 */
@Scope(Scopes.GLOBAL)
@SurvivesRestarts
public class GlobalComponentRegistry extends AbstractComponentRegistry {

   private static final Log log = LogFactory.getLog(GlobalComponentRegistry.class);
   /**
    * Hook to shut down the cache when the JVM exits.
    */
   private Thread shutdownHook;
   /**
    * A flag that the shutdown hook sets before calling cache.stop().  Allows stop() to identify if it has been called
    * from a shutdown hook.
    */
   private boolean invokedFromShutdownHook;

   private final GlobalConfiguration globalConfiguration;

   /**
    * Tracking set of created caches in order to make it easy to
    * remove a cache on remote nodes.
    */
   private final Set<String> createdCaches;

   /**
    * Creates an instance of the component registry.  The configuration passed in is automatically registered.
    *
    * @param configuration configuration with which this is created
    */
   public GlobalComponentRegistry(GlobalConfiguration configuration, EmbeddedCacheManager cacheManager,
                                  ReflectionCache reflectionCache, Set<String> createdCaches) {
      super(reflectionCache);
      if (configuration == null) throw new NullPointerException("GlobalConfiguration cannot be null!");
      try {
         // this order is important ... 
         globalConfiguration = configuration;
         registerDefaultClassLoader(null);
         registerComponent(this, GlobalComponentRegistry.class);
         registerComponent(cacheManager, EmbeddedCacheManager.class);
         registerComponent(configuration, GlobalConfiguration.class);
         registerComponent(new CacheManagerJmxRegistration(), CacheManagerJmxRegistration.class);
         registerComponent(new CacheManagerNotifierImpl(), CacheManagerNotifier.class);
         Map<Byte, ModuleCommandFactory> factories = ModuleProperties.moduleCommandFactories();
         if (factories != null && !factories.isEmpty())
            registerNonVolatileComponent(factories, KnownComponentNames.MODULE_COMMAND_FACTORIES);
         else
            registerNonVolatileComponent(Collections.<Object, Object>emptyMap(), KnownComponentNames.MODULE_COMMAND_FACTORIES);
         this.createdCaches = createdCaches;
      } catch (Exception e) {
         throw new CacheException("Unable to construct a GlobalComponentRegistry!", e);
      }
   }

   protected Log getLog() {
      return log;
   }

   @Override
   protected void removeShutdownHook() {
      // if this is called from a source other than the shutdown hook, deregister the shutdown hook.
      if (!invokedFromShutdownHook && shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook);
   }

   @Override
   protected void addShutdownHook() {
      ArrayList al = MBeanServerFactory.findMBeanServer(null);
      boolean registerShutdownHook = (globalConfiguration.getShutdownHookBehavior() == DEFAULT && al.isEmpty())
            || globalConfiguration.getShutdownHookBehavior() == REGISTER;

      if (registerShutdownHook) {
         log.trace("Registering a shutdown hook.  Configured behavior = %s", globalConfiguration.getShutdownHookBehavior());
         shutdownHook = new Thread() {
            @Override
            public void run() {
               try {
                  invokedFromShutdownHook = true;
                  GlobalComponentRegistry.this.stop();
               }
               finally {
                  invokedFromShutdownHook = false;
               }
            }
         };

         Runtime.getRuntime().addShutdownHook(shutdownHook);
      } else {

         log.trace("Not registering a shutdown hook.  Configured behavior = %s", globalConfiguration.getShutdownHookBehavior());
      }
   }

   Map<String, ComponentRegistry> namedComponents = new HashMap<String, ComponentRegistry>();

   public final ComponentRegistry getNamedComponentRegistry(String name) {
      return namedComponents.get(name);
   }

   public final void registerNamedComponentRegistry(ComponentRegistry componentRegistry, String name) {
      namedComponents.put(name, componentRegistry);
   }

   public final void unregisterNamedComponentRegistry(String name) {
      namedComponents.remove(name);
   }

   public final void rewireNamedRegistries() {
      for (ComponentRegistry cr : namedComponents.values())
         cr.rewire();
   }

   public void start() {
      boolean needToNotify = state != ComponentStatus.RUNNING && state != ComponentStatus.INITIALIZING;
      if (needToNotify) {
         for (ModuleLifecycle l : moduleLifecycles) {
            l.cacheManagerStarting(this, globalConfiguration);
         }
      }
      super.start();
      if (needToNotify && state == ComponentStatus.RUNNING) {
         for (ModuleLifecycle l : moduleLifecycles) {
            l.cacheManagerStarted(this);
         }
      }
   }
   
   public void stop() {
      boolean needToNotify = state == ComponentStatus.RUNNING || state == ComponentStatus.INITIALIZING;
      if (needToNotify) {
         for (ModuleLifecycle l : moduleLifecycles) {
            l.cacheManagerStopping(this);
         }
      }
      super.stop();
      if (state == ComponentStatus.TERMINATED && needToNotify) {
         for (ModuleLifecycle l : moduleLifecycles) {
            l.cacheManagerStopped(this);
         }
      }
   }

   public final GlobalConfiguration getGlobalConfiguration() {
      return globalConfiguration;
   }

   /**
    * Removes a cache with the given name, returning true if the cache was removed.
    */
   public boolean removeCache(String cacheName) {
      return createdCaches.remove(cacheName);
   }
}
