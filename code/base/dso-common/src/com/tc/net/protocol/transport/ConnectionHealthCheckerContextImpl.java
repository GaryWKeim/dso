/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.SynchronizedLong;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.TCConnection;
import com.tc.net.core.TCConnectionManager;
import com.tc.net.core.event.TCConnectionEvent;
import com.tc.net.protocol.NullProtocolAdaptor;
import com.tc.util.State;

/**
 * A HealthChecker Context takes care of sending and receiving probe signals, book-keeping, sending additional probes
 * and all the logic to monitor peers health. One Context per Transport is assigned as soon as a TC Connection is
 * Established.
 * 
 * @author Manoj
 */

class ConnectionHealthCheckerContextImpl implements ConnectionHealthCheckerContext,
    HealthCheckerSocketConnectEventListener {

  // Probe State-Flow
  private static final State                     INIT                       = new State("INIT");
  private static final State                     START                      = new State("START");
  private static final State                     ALIVE                      = new State("ALIVE");
  private static final State                     AWAIT_PINGREPLY            = new State("AWAIT_PINGREPLY");
  private static final State                     SOCKET_CONNECT             = new State("SOCKET_CONNECT");
  private static final State                     DEAD                       = new State("DEAD");

  // Basic Ping probes
  private State                                  currentState;
  private final TCLogger                         logger;
  private final MessageTransportBase             transport;
  private final HealthCheckerProbeMessageFactory messageFactory;
  private final TCConnectionManager              connectionManager;
  private final int                              maxProbeCountWithoutReply;
  private final SynchronizedLong                 probeReplyNotRecievedCount = new SynchronizedLong(0);

  // Context info
  private final HealthCheckerConfig              config;
  private final int                              callbackPort;
  private final String                           remoteNodeDesc;

  // Socket Connect probes
  private int                                    socketConnectSuccessCount  = 0;
  private TCConnection                           presentConnection          = null;
  private HealthCheckerSocketConnect             sockectConnect             = new NullHealthCheckerSocketConnectImpl();

  // stats
  private final SynchronizedLong                 pingProbeSentCount         = new SynchronizedLong(0);

  public ConnectionHealthCheckerContextImpl(MessageTransportBase mtb, HealthCheckerConfig config,
                                            TCConnectionManager connMgr) {
    this.transport = mtb;
    this.messageFactory = new TransportMessageFactoryImpl();
    this.maxProbeCountWithoutReply = config.getPingProbes();
    this.config = config;
    this.connectionManager = connMgr;
    this.logger = TCLogging.getLogger(ConnectionHealthCheckerImpl.class.getName() + ". "
                                      + config.getHealthCheckerName());
    this.remoteNodeDesc = mtb.getRemoteAddress().getCanonicalStringForm();
    logger.info("Health monitoring agent started for " + remoteNodeDesc);
    currentState = INIT;
    callbackPort = transport.getRemoteCallbackPort();
    initCallbackPortVerification();
  }

  // RMP-XXX
  private void initCallbackPortVerification() {
    if (config.isSocketConnectOnPingFail()) {
      if (!initSocketConnectProbe()) {
        // 1. may be callback port not handshaked or
        // 2. callback port not reachable
        // error logging already done.
        changeState(START);
      } else {
        // async socket connect to callback port has started. HC state remains at INIT. state change happens on
        // connection events
      }
    } else {
      logger
          .info("HealthCheck SocketConnect disabled for " + remoteNodeDesc + ". HealthCheckCallbackPort not verified");
      changeState(START);
    }
  }

  /* all callers of this method are already synchronized */
  private void changeState(State newState) {
    if (logger.isDebugEnabled() && currentState != newState) {
      logger.debug("Context state change for " + remoteNodeDesc + " : " + currentState.toString() + " ===> "
                   + newState.toString());
    }
    currentState = newState;
  }

  private boolean canPingProbe() {
    if (logger.isDebugEnabled()) {
      if (this.probeReplyNotRecievedCount.get() > 0) logger.debug("PING_REPLY not received from " + remoteNodeDesc
                                                                  + " for " + this.probeReplyNotRecievedCount
                                                                  + " times (max allowed:"
                                                                  + this.maxProbeCountWithoutReply + ").");
    }

    return ((this.probeReplyNotRecievedCount.get() < this.maxProbeCountWithoutReply));
  }

  private boolean initSocketConnectProbe() {
    // trigger the socket connect
    presentConnection = getNewConnection(connectionManager);
    sockectConnect = getHealthCheckerSocketConnector(presentConnection, transport, logger, config);
    sockectConnect.addSocketConnectEventListener(this);
    if (sockectConnect.start()) {
      return true;
    } else {
      clearPresentConnection();
      return false;
    }
  }

  protected TCConnection getNewConnection(TCConnectionManager connManager) {
    TCConnection connection = connManager.createConnection(new NullProtocolAdaptor());
    return connection;
  }

  protected HealthCheckerSocketConnect getHealthCheckerSocketConnector(TCConnection connection,
                                                                       MessageTransportBase transportBase,
                                                                       TCLogger loger, HealthCheckerConfig cnfg) {
    if (TransportHandshakeMessage.NO_CALLBACK_PORT == callbackPort) {
      logger.info("No HealthCheckCallbackPort handshaked for node " + remoteNodeDesc);
      return new NullHealthCheckerSocketConnectImpl();
    }

    // TODO: do we need to exchange the address as well ??? (since it might be different than the remote IP on this
    // conn)
    TCSocketAddress sa = new TCSocketAddress(transportBase.getRemoteAddress().getAddress(), callbackPort);
    return new HealthCheckerSocketConnectImpl(sa, connection, remoteNodeDesc + "(callbackport:" + callbackPort + ")",
                                              loger, cnfg.getSocketConnectTimeout());
  }

  private void clearPresentConnection() {
    sockectConnect.removeSocketConnectEventListener(this);
    presentConnection = null;
  }

  public synchronized void refresh() {
    initProbeCycle();
    initSocketConnectCycle();
  }

  public synchronized boolean probeIfAlive() {
    if (currentState.equals(DEAD)) {
      // connection events might have moved us to DEAD state.
      // all return are done at the bottom
    } else if (currentState.equals(SOCKET_CONNECT)) {

      /* Socket Connect is in progress; wait for one more interval or move to next state */
      if (!sockectConnect.probeConnectStatus()) {
        changeState(DEAD);
      }

    } else if (currentState.equals(START) || currentState.equals(ALIVE) || currentState.equals(AWAIT_PINGREPLY)) {

      /* Send Probe again; if not possible move to next state */
      if (canPingProbe()) {
        if (logger.isDebugEnabled()) {
          logger.debug("Sending PING Probe to IDLE " + remoteNodeDesc);
        }
        sendProbeMessage(this.messageFactory.createPing(transport.getConnectionId(), transport.getConnection()));
        pingProbeSentCount.increment();
        probeReplyNotRecievedCount.increment();
        changeState(AWAIT_PINGREPLY);
      } else if (config.isSocketConnectOnPingFail()) {
        changeState(SOCKET_CONNECT);
        if (!initSocketConnectProbe()) {
          changeState(DEAD);
        }
      } else {
        changeState(DEAD);
      }
    } else if (currentState.equals(INIT)) {
      // callbackport initial verification not yet done. connection events didn't arrive still. lets probe the status
      if (!sockectConnect.probeConnectStatus()) {
        callbackPortVerificationFailed();
      } else {
        // verification still in progress
      }
    }

    if (currentState.equals(DEAD)) {
      logger.info(remoteNodeDesc + " is DEAD");
      return false;
    }
    return true;
  }

  private void callbackPortVerificationFailed() {
    logger.info("HealthCheckCallbackPort verification FAILED for " + remoteNodeDesc + "(callbackport: " + callbackPort
                + ")");
    transport.setRemoteCallbackPort(TransportHandshakeMessage.NO_CALLBACK_PORT);
    changeState(START);
  }

  private void callbackPortVerificationSuccess() {
    logger.info("HealthCheckCallbackPort verification PASSED for " + remoteNodeDesc + "(callbackport: " + callbackPort
                + ")");
    changeState(START);
  }

  public synchronized boolean receiveProbe(HealthCheckerProbeMessage message) {
    if (message.isPing()) {
      // Echo back but no change in this health checker state
      sendProbeMessage(this.messageFactory.createPingReply(transport.getConnectionId(), transport.getConnection()));
    } else if (message.isPingReply()) {
      // The peer is alive
      if (probeReplyNotRecievedCount.get() > 0) probeReplyNotRecievedCount.decrement();

      if (probeReplyNotRecievedCount.compareTo(0) <= 0) {
        changeState(ALIVE);
      }

      if (wasInLongGC()) {
        initSocketConnectCycle();
      }
    } else {
      // error message thrown at transport layers
      return false;
    }
    return true;
  }

  private void sendProbeMessage(HealthCheckerProbeMessage message) {
    this.transport.send(message);
  }

  public long getTotalProbesSent() {
    return pingProbeSentCount.get();
  }

  private void initProbeCycle() {
    probeReplyNotRecievedCount.set(0);
  }

  private void initSocketConnectCycle() {
    socketConnectSuccessCount = 0;
  }

  private boolean wasInLongGC() {
    return (socketConnectSuccessCount > 0);
  }

  private boolean canAcceptConnectionEvent(TCConnectionEvent event) {
    if ((event.getSource() == presentConnection) && (currentState == SOCKET_CONNECT)) {
      return true;
    } else {
      // connection events after wait-period OR when not in socket connect stage -- ignore
      logger.info("Unexpected connection event: " + event + ". Current state: " + currentState);
      return false;
    }
  }

  TCLogger getLogger() {
    return this.logger;
  }

  public synchronized void notifySocketConnectFail(TCConnectionEvent failureEvent) {
    if (currentState.equals(INIT)) {
      callbackPortVerificationFailed();
    } else {
      if (canAcceptConnectionEvent(failureEvent)) {
        logger.warn("Socket Connect error event:" + failureEvent.toString() + " on " + remoteNodeDesc);
        changeState(DEAD);
      }
    }
  }

  public synchronized void notifySocketConnectSuccess(TCConnectionEvent successEvent) {
    if (currentState.equals(INIT)) {
      callbackPortVerificationSuccess();
    } else {
      if (canAcceptConnectionEvent(successEvent)) {
        // Async connect goes thru
        socketConnectSuccessCount++;
        if (socketConnectSuccessCount < config.getSocketConnectMaxCount()) {
          logger.warn(remoteNodeDesc + " might be in Long GC. GC count since last ping reply : "
                      + socketConnectSuccessCount);
          initProbeCycle();
          changeState(ALIVE);
        } else {
          logger.error(remoteNodeDesc + " might be in Long GC. GC count since last ping reply : "
                       + socketConnectSuccessCount + ". But its too long. No more retries");
          changeState(DEAD);
        }
      }
    }
  }

}
