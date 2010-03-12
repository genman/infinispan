/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc. and/or its affiliates, and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of
 * individual contributors.
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

package org.infinispan.server.core.transport.netty;

import org.infinispan.server.core.transport.Channel;
import org.infinispan.server.core.transport.ChannelEvent;
import org.infinispan.server.core.transport.ChannelFuture;

/**
 * // TODO: Document this
 *
 * @author Galder Zamarreño
 */
public class NettyChannelEvent implements ChannelEvent {
   
   final org.jboss.netty.channel.ChannelEvent event;

   NettyChannelEvent(org.jboss.netty.channel.ChannelEvent event) {
      this.event = event;
   }

   @Override
   public Channel getChannel() {
      return new NettyChannel(event.getChannel());
   }

   @Override
   public ChannelFuture getFuture() {
      return new NettyChannelFuture(event.getFuture());
   }

}