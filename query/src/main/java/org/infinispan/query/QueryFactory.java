/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.query;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;
import org.hibernate.search.spi.SearchFactoryIntegrator;
import org.infinispan.Cache;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.query.backend.QueryInterceptor;
import org.infinispan.query.impl.CacheQueryImpl;

/**
 * Class that is used to build {@link org.infinispan.query.CacheQuery}
 *
 * @author Navin Surtani
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2011 Red Hat Inc.
 * @since 4.0
 */
public class QueryFactory {

   private final Cache cache;
   private final SearchFactoryIntegrator searchFactory;
   private final QueryInterceptor queryInterceptor;
   private final Version luceneVersion;
   
   public QueryFactory(Cache cache) {
      this(cache, null);
   }

   public QueryFactory(Cache cache, Version luceneVersion) {
      this.luceneVersion = luceneVersion == null ? Version.LUCENE_CURRENT : luceneVersion;
      if (cache==null) {
         throw new IllegalArgumentException("cache parameter shall not be null");
      }
      this.cache = cache;
      this.searchFactory = extractType(cache, SearchFactoryIntegrator.class);
      this.queryInterceptor = extractType(cache, QueryInterceptor.class);
   }

   private static <T> T extractType(Cache cache, Class<T> class1) {
      ComponentRegistry componentRegistry = cache.getAdvancedCache().getComponentRegistry();
      T component = componentRegistry.getComponent(class1);
      if (component==null) {
         throw new IllegalArgumentException("Indexing was not enabled on this cache. " + class1 + " not found in registry");
      }
      return component;
   }

   /**
    * This is a simple method that will just return a {@link CacheQuery}, filtered according to a set of classes passed
    * in.  If no classes are passed in, it is assumed that no type filtering is performed.
    *
    * @param luceneQuery - {@link org.apache.lucene.search.Query}
    * @param classes - only return results of type that matches this list of acceptable types
    * @return the query object which can be used to iterate through results
    */
   public CacheQuery getQuery(Query luceneQuery, Class<?>... classes) {
      queryInterceptor.enableClasses(classes);
      return new CacheQueryImpl(luceneQuery, searchFactory, cache, classes);
   }

   /**
    * This method is a basic query. The user provides 2 strings and internally the {@link
    * org.apache.lucene.search.Query} is built.
    * <p/>
    * The first string is the field that they are searching and the second one is the search that they want to run.
    * <p/>
    * For example: -
    * <p/>
    * {@link org.infinispan.query.CacheQuery} cq = new QueryFactory
    * <p/>
    * The query is built by use of a {@link org.apache.lucene.queryParser.QueryParser} and a {@link
    * org.apache.lucene.analysis.standard.StandardAnalyzer} </p>
    *
    * @param field  - the field on the class that you are searching
    * @param search - the String that you want to be using to search
    * @return {@link org.infinispan.query.CacheQuery} result
    */
   public CacheQuery getBasicQuery(String field, String search) throws org.apache.lucene.queryParser.ParseException {
      QueryParser parser = new QueryParser(luceneVersion, field, new StandardAnalyzer(luceneVersion));
      org.apache.lucene.search.Query luceneQuery = parser.parse(search);
      return new CacheQueryImpl(luceneQuery, searchFactory, cache);
   }
   
   public CacheQuery getBasicQuery(String field, String search, Class<?>... classes) throws org.apache.lucene.queryParser.ParseException {
      queryInterceptor.enableClasses(classes);
      QueryParser parser = new QueryParser(luceneVersion, field, new StandardAnalyzer(luceneVersion));
      org.apache.lucene.search.Query luceneQuery = parser.parse(search);
      return new CacheQueryImpl(luceneQuery, searchFactory, cache);
   }
   
}
