/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.raft.client;

import org.apache.raft.RaftConstants;
import org.apache.raft.protocol.*;
import org.apache.raft.util.RaftUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** A client who sends requests to a raft service. */
public class RaftClient {
  public static final Logger LOG = LoggerFactory.getLogger(RaftClient.class);

  private final String clientId;
  private final RaftClientRequestSender requestSender;
  private final Map<String, RaftPeer> peers;

  private volatile String leaderId;

  public RaftClient(String clientId, Collection<RaftPeer> peers,
      RaftClientRequestSender requestSender, String leaderId) {
    this.clientId = clientId;
    this.requestSender = requestSender;
    this.peers = peers.stream().collect(
        Collectors.toMap(RaftPeer::getId, Function.identity()));
    this.leaderId = leaderId != null? leaderId : peers.iterator().next().getId();
  }

  public String getId() {
    return clientId;
  }

  /** @return the next peer after the given leader. */
  static String nextLeader(final String leaderId, final Iterator<String> i) {
    final String first = i.next();
    for(String previous = first; i.hasNext(); ) {
      final String current = i.next();
      if (leaderId.equals(previous)) {
        return current;
      }
      previous = current;
    }
    return first;
  }

  private void refreshPeers(RaftPeer[] newPeers) {
    if (newPeers != null && newPeers.length > 0) {
      peers.clear();
      for (RaftPeer p : newPeers) {
        peers.put(p.getId(), p);
      }
      // also refresh the rpc proxies for these peers
      requestSender.addServers(Arrays.asList(newPeers));
    }
  }

  /** Send the given message to the raft service. */
  public RaftClientReply send(Message message) throws IOException {
    return sendRequestWithRetry(
        () -> new RaftClientRequest(clientId, leaderId, message));
  }

  /** Send set configuration request to the raft service. */
  public RaftClientReply setConfiguration(RaftPeer[] peersInNewConf)
      throws IOException {
    return sendRequestWithRetry(()
        -> new SetConfigurationRequest(clientId, leaderId, peersInNewConf));
  }

  private RaftClientReply sendRequestWithRetry(
      Supplier<RaftClientRequest> supplier) throws InterruptedIOException {
    for(;;) {
      final RaftClientRequest request = supplier.get();
      LOG.debug("{}: {}", clientId, request);
      final RaftClientReply reply = sendRequest(request);
      if (reply != null) {
        LOG.debug("{}: {}", clientId, reply);
        return reply;
      }

      // sleep and then retry
      try {
        Thread.sleep(RaftConstants.RPC_TIMEOUT_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw RaftUtils.toInterruptedIOException(
            "Interrupted when sending " + request, ie);
      }
    }
  }

  private RaftClientReply sendRequest(RaftClientRequest request) {
    try {
      RaftClientReply reply = requestSender.sendRequest(request);
      if (reply.isNotLeader()) {
        handleNotLeaderException(request, reply.getNotLeaderException());
        return null;
      } else {
        return reply;
      }
    } catch (IOException ioe) {
      // TODO No retry if the exception is thrown from the state machine
      handleIOException(request, ioe, null);
    }
    return null;
  }

  private void handleNotLeaderException(RaftClientRequest request, NotLeaderException nle) {
    refreshPeers(nle.getPeers());
    final String newLeader = nle.getSuggestedLeader() == null? null
        : nle.getSuggestedLeader().getId();
    handleIOException(request, nle, newLeader);
  }

  private void handleIOException(RaftClientRequest request, IOException ioe, String newLeader) {
    LOG.debug("{}: Failed with {}", clientId, ioe);
    final String oldLeader = request.getReplierId();
    if (newLeader == null && oldLeader.equals(leaderId)) {
      newLeader = nextLeader(oldLeader, peers.keySet().iterator());
    }
    if (newLeader != null && oldLeader.equals(leaderId)) {
      LOG.debug("{}: change Leader from {} to {}", clientId, oldLeader, newLeader);
      this.leaderId = newLeader;
    }
  }
}