/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat, Inc. and/or its affiliates, and
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
package org.infinispan.remoting.transport.jgroups;

import org.infinispan.CacheException;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.remoting.InboundInvocationHandler;
import org.infinispan.remoting.RpcException;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.ExtendedResponse;
import org.infinispan.remoting.responses.RequestIgnoredResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.jgroups.Address;
import org.jgroups.Channel;
import org.jgroups.Message;
import org.jgroups.blocks.GroupRequest;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.blocks.RspFilter;
import org.jgroups.util.Buffer;
import org.jgroups.util.FutureListener;
import org.jgroups.util.NotifyingFuture;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;

import java.io.NotSerializableException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.infinispan.util.Util.*;

/**
 * A JGroups RPC dispatcher that knows how to deal with {@link ReplicableCommand}s.
 *
 * @author Manik Surtani (<a href="mailto:manik@jboss.org">manik@jboss.org</a>)
 * @since 4.0
 */
public class CommandAwareRpcDispatcher extends RpcDispatcher {
   ExecutorService asyncExecutor;
   InboundInvocationHandler inboundInvocationHandler;
   JGroupsDistSync distributedSync;
   long distributedSyncTimeout;
   private static final Log log = LogFactory.getLog(CommandAwareRpcDispatcher.class);
   private static final boolean trace = log.isTraceEnabled();
   private static final boolean FORCE_MCAST = Boolean.getBoolean("infinispan.unsafe.force_multicast");

   public CommandAwareRpcDispatcher(Channel channel,
                                    JGroupsTransport transport,
                                    ExecutorService asyncExecutor,
                                    InboundInvocationHandler inboundInvocationHandler,
                                    JGroupsDistSync distributedSync, long distributedSyncTimeout) {
      super(channel, transport, transport, transport);
      this.asyncExecutor = asyncExecutor;
      this.inboundInvocationHandler = inboundInvocationHandler;
      this.distributedSync = distributedSync;
      this.distributedSyncTimeout = distributedSyncTimeout;
   }

   protected final boolean isValid(Message req) {
      if (req == null || req.getLength() == 0) {
         log.error("Message or message buffer is null or empty.");
         return false;
      }

      return true;
   }

   public RspList invokeRemoteCommands(Vector<Address> dests, ReplicableCommand command, int mode, long timeout,
                                       boolean anycasting, boolean oob, RspFilter filter, boolean supportReplay, boolean asyncMarshalling,
                                       boolean broadcast)
         throws NotSerializableException, ExecutionException, InterruptedException {

      ReplicationTask task = new ReplicationTask(command, oob, dests, mode, timeout, anycasting, filter, supportReplay, broadcast);

      if (asyncMarshalling) {
         asyncExecutor.submit(task);
         return null; // don't wait for a response!
      } else {
         RspList response;
         try {
            response = task.call();
         } catch (Exception e) {
            throw rewrapAsCacheException(e);
         }
         if (mode == GroupRequest.GET_NONE) return null; // "Traditional" async.
         if (response.isEmpty() || containsOnlyNulls(response))
            return null;
         else
            return response;
      }
   }

   private boolean containsOnlyNulls(RspList l) {
      for (Rsp r : l.values()) {
         if (r.getValue() != null || !r.wasReceived() || r.wasSuspected()) return false;
      }
      return true;
   }

   /**
    * Message contains a Command. Execute it against *this* object and return result.
    */
   @Override
   public Object handle(Message req) {
      if (isValid(req)) {
         try {
            ReplicableCommand cmd = (ReplicableCommand) req_marshaller.objectFromByteBuffer(req.getBuffer(), req.getOffset(), req.getLength());
            if (cmd instanceof CacheRpcCommand)
               return executeCommand((CacheRpcCommand) cmd, req);
            else
               return cmd.perform(null);
         } catch (Throwable x) {
            if (trace) log.trace("Problems invoking command.", x);
            return new ExceptionResponse(new CacheException("Problems invoking command.", x));
         }
      } else {
         return null;
      }
   }

   private Response executeCommand(CacheRpcCommand cmd, Message req) throws Throwable {
      if (cmd == null) throw new NullPointerException("Unable to execute a null command!  Message was " + req);
      if (trace) log.trace("Attempting to execute command: %s [sender=%s]", cmd, req.getSrc());
      return inboundInvocationHandler.handle(cmd, new JGroupsAddress(req.getSrc()));
   }

   @Override
   public String toString() {
      return getClass().getSimpleName() + "[Outgoing marshaller: " + req_marshaller + "; incoming marshaller: " + rsp_marshaller + "]";
   }

   private class ReplicationTask implements Callable<RspList> {

      private ReplicableCommand command;
      private boolean oob;
      private Vector<Address> dests;
      private int mode;
      private long timeout;
      private boolean anycasting;
      private RspFilter filter;
      boolean supportReplay = false;
      boolean broadcast = false;

      private ReplicationTask(ReplicableCommand command, boolean oob, Vector<Address> dests,
                              int mode, long timeout,
                              boolean anycasting, RspFilter filter, boolean supportReplay, boolean broadcast) {
         this.command = command;
         this.oob = oob;
         this.dests = dests;
         this.mode = mode;
         this.timeout = timeout;
         this.anycasting = anycasting;
         this.filter = filter;
         this.supportReplay = supportReplay;
         this.broadcast = broadcast;
      }

      private Message constructMessage(Buffer buf, Address recipient) {
         Message msg = new Message();
         msg.setBuffer(buf);
         if (oob) msg.setFlag(Message.OOB);
         if (mode != GroupRequest.GET_NONE) {
            msg.setFlag(Message.DONT_BUNDLE);
            msg.setFlag(Message.NO_FC);
         }
         if (recipient != null) msg.setDest(recipient);
         return msg;
      }

      private Buffer marshallCall() {
         Buffer buf;
         try {
            buf = req_marshaller.objectToBuffer(command);
         } catch (Exception e) {
            throw new RuntimeException("Failure to marshal argument(s)", e);
         }
         return buf;
      }

      public RspList call() throws Exception {
         if (trace) log.trace("Replication task sending %s to addresses %s", command, dests);

         // Replay capability requires responses from all members!
         int mode = supportReplay ? GroupRequest.GET_ALL : this.mode;

         if (filter != null) mode = GroupRequest.GET_FIRST;

         RspList retval = null;
         Buffer buf;
         if (broadcast || FORCE_MCAST) {
            RequestOptions opts = new RequestOptions();
            opts.setMode(mode);
            opts.setTimeout(timeout);
            opts.setRspFilter(filter);
            opts.setAnycasting(false);
            buf = marshallCall();
            retval = castMessage(dests, constructMessage(buf, null), opts);
         } else {
            Set<Address> targets = new HashSet<Address>(dests); // should sufficiently randomize order.
            RequestOptions opts = new RequestOptions();
            opts.setMode(mode);
            opts.setTimeout(timeout);

            targets.remove(channel.getAddress()); // just in case
            if (targets.isEmpty()) return new RspList();
            buf = marshallCall();

            // if at all possible, try not to use JGroups' ANYCAST for now.  Multiple (parallel) UNICASTs are much faster.
            if (filter != null) {
               // This is possibly a remote GET.
               // These UNICASTs happen in parallel using sendMessageWithFuture.  Each future has a listener attached
               // (see FutureCollator) and the first successful response is used.
               FutureCollator futureCollator = new FutureCollator(filter, targets.size(), timeout);
               for (Address a : targets) {
                  NotifyingFuture<Object> f = sendMessageWithFuture(constructMessage(buf, a), opts);
                  futureCollator.watchFuture(f, a);
               }
               retval = futureCollator.getResponseList();
            } else if (mode == GroupRequest.GET_ALL) {
               // A SYNC call that needs to go everywhere
               Map<Address, Future<Object>> futures = new HashMap<Address, Future<Object>>(targets.size());

               for (Address dest : targets) futures.put(dest, sendMessageWithFuture(constructMessage(buf, dest), opts));

               retval = new RspList();

               // a get() on each future will block till that call completes.
               for (Map.Entry<Address, Future<Object>> entry : futures.entrySet()) {
                  try {
                     retval.addRsp(entry.getKey(), entry.getValue().get(timeout, MILLISECONDS));
                  } catch (java.util.concurrent.TimeoutException te) {
                     throw new TimeoutException(formatString("Timed out after %s waiting for a response from %s",
                                                             prettyPrintTime(timeout), entry.getKey()));
                  }
               }

            } else if (mode == GroupRequest.GET_NONE) {
               // An ASYNC call.  We don't care about responses.
               for (Address dest : targets) sendMessage(constructMessage(buf, dest), opts);
            }
         }

         // we only bother parsing responses if we are not in ASYNC mode.
         if (mode != GroupRequest.GET_NONE) {

            if (trace) log.trace("Responses: %s", retval);

            // a null response is 99% likely to be due to a marshalling problem - we throw a NSE, this needs to be changed when
            // JGroups supports http://jira.jboss.com/jira/browse/JGRP-193
            // the serialization problem could be on the remote end and this is why we cannot catch this above, when marshalling.
            if (retval == null)
               throw new NotSerializableException("RpcDispatcher returned a null.  This is most often caused by args for "
                                                        + command.getClass().getSimpleName() + " not being serializable.");

            if (supportReplay) {
               boolean replay = false;
               Vector<Address> ignorers = new Vector<Address>();
               for (Map.Entry<Address, Rsp> entry : retval.entrySet()) {
                  Object value = entry.getValue().getValue();
                  if (value instanceof RequestIgnoredResponse) {
                     ignorers.add(entry.getKey());
                  } else if (value instanceof ExtendedResponse) {
                     ExtendedResponse extended = (ExtendedResponse) value;
                     replay |= extended.isReplayIgnoredRequests();
                     entry.getValue().setValue(extended.getResponse());
                  }
               }

               if (replay && !ignorers.isEmpty()) {
                  Message msg = constructMessage(buf, null);
                  //Since we are making a sync call make sure we don't bundle
                  //See ISPN-192 for more details
                  msg.setFlag(Message.DONT_BUNDLE);

                  if (trace)
                     log.trace("Replaying message to ignoring senders: " + ignorers);
                  RequestOptions opts = new RequestOptions();
                  opts.setMode(GroupRequest.GET_ALL);
                  opts.setTimeout(timeout);
                  opts.setAnycasting(anycasting);
                  opts.setRspFilter(filter);
                  RspList responses = castMessage(ignorers, msg, opts);
                  if (responses != null)
                     retval.putAll(responses);
               }
            }
         }

         return retval;
      }
   }

   static class SenderContainer {
      final Address address;
      volatile boolean processed = false;

      SenderContainer(Address address) {
         this.address = address;
      }

      @Override
      public String toString() {
         return "Sender{" +
               "address=" + address +
               ", responded=" + processed +
               '}';
      }
   }

   class FutureCollator implements FutureListener<Object> {
      final RspFilter filter;
      volatile RspList retval;
      final Map<Future<Object>, SenderContainer> futures = new HashMap<Future<Object>, SenderContainer>(4);
      volatile Exception exception;
      volatile int expectedResponses;
      final long timeout;

      FutureCollator(RspFilter filter, int expectedResponses, long timeout) {
         this.filter = filter;
         this.expectedResponses = expectedResponses;
         this.timeout = timeout;
      }

      public void watchFuture(NotifyingFuture<Object> f, Address address) {
         futures.put(f, new SenderContainer(address));
         f.setListener(this);
      }

      public RspList getResponseList() throws Exception {
         long giveupTime = System.currentTimeMillis() + timeout;
         boolean notTimedOut = true;
         synchronized (this) {
            notTimedOut = giveupTime > System.currentTimeMillis();
            while (notTimedOut && expectedResponses > 0 && retval == null)
               this.wait(timeout);
         }

         // if we've got here, we either have the response we need or aren't expecting any more responses - or have run out of time.
         if (retval != null)
            return retval;
         else if (exception != null)
            throw exception;
         else if (notTimedOut)
            throw new RpcException(format("No more valid responses.  Received invalid responses from all of %s", futures.values()));
         else
            throw new TimeoutException(format("Timed out waiting for %s for valid responses from any of %s.", Util.prettyPrintTime(timeout), futures.values()));
      }

      @Override
      @SuppressWarnings("unchecked")
      public void futureDone(Future<Object> objectFuture) {
         synchronized (this) {
            SenderContainer sc = futures.get(objectFuture);
            if (sc.processed) {
               // This can happen - it is a race condition in JGroups' NotifyingFuture.setListener() where a listener
               // could be notified twice.
               if (trace) log.trace("Not processing callback; already processed callback for sender %s", sc.address);
            } else {
               sc.processed = true;
               Address sender = sc.address;
               try {
                  if (retval == null) {
                     Object response = objectFuture.get();
                     if (trace) log.trace("Received response: %s from %s", response, sender);
                     filter.isAcceptable(response, sender);
                     if (!filter.needMoreResponses())
                        retval = new RspList(Collections.singleton(new Rsp(sender, response)));
                  } else {
                     if (log.isDebugEnabled())
                        log.debug("Skipping response from %s since a valid response for this request has already been received", sender);
                  }
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
               } catch (ExecutionException e) {
                  if (e.getCause() instanceof org.jgroups.TimeoutException)
                     exception = new TimeoutException("Timeout!", e);
                  else if (e.getCause() instanceof Exception)
                     exception = (Exception) e.getCause();
                  else
                     log.info("Caught a Throwable.", e.getCause());

                  if (log.isDebugEnabled())
                     log.debug("Caught exception %s from sender %s.  Will skip this response.", exception.getClass().getName(), sender);
                  if (trace) log.trace("Exception caught: ", exception);
               } finally {
                  expectedResponses--;
                  this.notify();
               }
            }
         }
      }

   }
}

