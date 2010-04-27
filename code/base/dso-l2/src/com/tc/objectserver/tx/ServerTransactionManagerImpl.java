/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.tx;

import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArrayList;
import EDU.oswego.cs.dl.util.concurrent.Latch;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.impl.DNAImpl;
import com.tc.object.dna.impl.VersionizedDNAWrapper;
import com.tc.object.gtx.GlobalTransactionID;
import com.tc.object.gtx.GlobalTransactionManager;
import com.tc.object.net.ChannelStats;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.ObjectInstanceMonitor;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.gtx.GlobalTransactionIDLowWaterMarkProvider;
import com.tc.objectserver.gtx.ServerGlobalTransactionManager;
import com.tc.objectserver.l1.api.ClientStateManager;
import com.tc.objectserver.l1.impl.TransactionAcknowledgeAction;
import com.tc.objectserver.locks.LockManager;
import com.tc.objectserver.managedobject.BackReferences;
import com.tc.objectserver.mgmt.ObjectStatsRecorder;
import com.tc.objectserver.persistence.api.PersistenceTransaction;
import com.tc.objectserver.persistence.api.PersistenceTransactionProvider;
import com.tc.objectserver.persistence.api.TransactionStore;
import com.tc.stats.counter.Counter;
import com.tc.text.DumpLoggerWriter;
import com.tc.text.PrettyPrinter;
import com.tc.text.PrettyPrinterImpl;
import com.tc.util.Assert;
import com.tc.util.ObjectIDSet;
import com.tc.util.State;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ServerTransactionManagerImpl implements ServerTransactionManager, ServerTransactionManagerMBean,
    GlobalTransactionManager {

  private static final TCLogger                         logger                       = TCLogging
                                                                                         .getLogger(ServerTransactionManager.class);

  private static final State                            PASSIVE_MODE                 = new State("PASSIVE-MODE");
  private static final State                            ACTIVE_MODE                  = new State("ACTIVE-MODE");

  // TODO::FIXME::Change this to concurrent HashMap
  private final Map                                     transactionAccounts          = Collections
                                                                                         .synchronizedMap(new HashMap());
  private final ClientStateManager                      stateManager;
  private final ObjectManager                           objectManager;
  private final ResentTransactionSequencer              resentTxnSequencer;
  private final TransactionAcknowledgeAction            action;
  private final LockManager                             lockManager;
  private final List                                    rootEventListeners           = new CopyOnWriteArrayList();
  private final List                                    txnEventListeners            = new CopyOnWriteArrayList();
  private final GlobalTransactionIDLowWaterMarkProvider lwmProvider;

  private final Counter                                 transactionRateCounter;

  private final ChannelStats                            channelStats;

  private final ServerGlobalTransactionManager          gtxm;

  private final ServerTransactionLogger                 txnLogger;

  private volatile State                                state                        = PASSIVE_MODE;
  private final AtomicInteger                           totalPendingTransactions     = new AtomicInteger(0);
  private final AtomicInteger                           txnsCommitted                = new AtomicInteger(0);
  private final AtomicInteger                           objectsCommitted             = new AtomicInteger(0);
  private final AtomicInteger                           noOfCommits                  = new AtomicInteger(0);
  private final AtomicLong                              totalNumOfActiveTransactions = new AtomicLong(0);
  private final boolean                                 commitLoggingEnabled;
  private final boolean                                 broadcastStatsLoggingEnabled;

  private volatile long                                 lastStatsTime                = 0;
  private final Object                                  statsLock                    = new Object();

  private final ObjectStatsRecorder                     objectStatsRecorder;

  public ServerTransactionManagerImpl(final ServerGlobalTransactionManager gtxm,
                                      final TransactionStore transactionStore, final LockManager lockManager,
                                      final ClientStateManager stateManager, final ObjectManager objectManager,
                                      final TransactionalObjectManager txnObjectManager,
                                      final TransactionAcknowledgeAction action, final Counter transactionRateCounter,
                                      final ChannelStats channelStats, final ServerTransactionManagerConfig config,
                                      final ObjectStatsRecorder objectStatsRecorder) {
    this.gtxm = gtxm;
    this.lockManager = lockManager;
    this.objectManager = objectManager;
    this.stateManager = stateManager;
    this.resentTxnSequencer = new ResentTransactionSequencer(this, gtxm, txnObjectManager);
    this.action = action;
    this.transactionRateCounter = transactionRateCounter;
    this.channelStats = channelStats;
    this.lwmProvider = new GlobalTransactionIDLowWaterMarkProvider(this, gtxm);
    this.txnLogger = new ServerTransactionLogger(logger, config);
    if (config.isLoggingEnabled()) {
      enableTransactionLogger();
    }
    this.commitLoggingEnabled = config.isPrintCommitsEnabled();
    this.broadcastStatsLoggingEnabled = config.isPrintBroadcastStatsEnabled();
    this.objectStatsRecorder = objectStatsRecorder;
  }

  public void enableTransactionLogger() {
    synchronized (this.txnLogger) {
      removeTransactionListener(this.txnLogger);
      addTransactionListener(this.txnLogger);
    }
  }

  public void disableTransactionLogger() {
    synchronized (this.txnLogger) {
      removeTransactionListener(this.txnLogger);
    }
  }

  public void dumpToLogger() {
    final DumpLoggerWriter writer = new DumpLoggerWriter();
    final PrintWriter pw = new PrintWriter(writer);
    final PrettyPrinterImpl prettyPrinter = new PrettyPrinterImpl(pw);
    prettyPrinter.autoflush(false);
    prettyPrinter.visit(this);
    writer.flush();
  }

  public synchronized PrettyPrinter prettyPrint(final PrettyPrinter out) {
    out.print(this.getClass().getName()).flush();
    out.indent().print("transactionAccounts: ").visit(this.transactionAccounts).println().flush();
    return out;
  }

  /**
   * Shutdown clients are not cleared immediately. Only on completing of all txns this is processed.
   */
  public void shutdownNode(final NodeID deadNodeID) {
    boolean callBackAdded = false;
    synchronized (this.transactionAccounts) {
      final TransactionAccount deadClientTA = (TransactionAccount) this.transactionAccounts.get(deadNodeID);
      if (deadClientTA != null) {
        deadClientTA.nodeDead(new TransactionAccount.CallBackOnComplete() {
          public void onComplete(final NodeID dead) {
            synchronized (ServerTransactionManagerImpl.this.transactionAccounts) {
              ServerTransactionManagerImpl.this.transactionAccounts.remove(deadNodeID);
            }
            ServerTransactionManagerImpl.this.stateManager.shutdownNode(deadNodeID);
            if (deadNodeID instanceof ClientID) {
              ServerTransactionManagerImpl.this.lockManager.clearAllLocksFor((ClientID) deadNodeID);
            }
            ServerTransactionManagerImpl.this.gtxm.shutdownNode(deadNodeID);
            fireClientDisconnectedEvent(deadNodeID);
          }
        });
        callBackAdded = true;
      }

      final TransactionAccount tas[] = (TransactionAccount[]) this.transactionAccounts.values()
          .toArray(new TransactionAccount[this.transactionAccounts.size()]);
      for (final TransactionAccount client : tas) {
        if (client == deadClientTA) {
          continue;
        }
        for (final Iterator it = client.requestersWaitingFor(deadNodeID).iterator(); it.hasNext();) {
          final TransactionID reqID = (TransactionID) it.next();
          acknowledgement(client.getNodeID(), reqID, deadNodeID);
        }
      }
    }

    if (!callBackAdded) {
      this.stateManager.shutdownNode(deadNodeID);
      if (deadNodeID instanceof ClientID) {
        this.lockManager.clearAllLocksFor((ClientID) deadNodeID);
      }
      this.gtxm.shutdownNode(deadNodeID);
      fireClientDisconnectedEvent(deadNodeID);
    }
  }

  public void nodeConnected(final NodeID nodeID) {
    this.lockManager.enableLockStatsForNodeIfNeeded((ClientID) nodeID);
  }

  public void start(final Set cids) {
    synchronized (this.transactionAccounts) {
      final int sizeB4 = this.transactionAccounts.size();
      this.transactionAccounts.keySet().retainAll(cids);
      final int sizeAfter = this.transactionAccounts.size();
      if (sizeB4 != sizeAfter) {
        logger.warn("Cleaned up Transaction Accounts for : " + (sizeB4 - sizeAfter) + " clients");
      }
    }
    // XXX:: The server could have crashed right after a client crash/disconnect before it had a chance to remove
    // transactions from the DB. If we dont do this, then these will stick around for ever and cause low-water mark to
    // remain the same for ever and ever.
    // For Network enabled Active/Passive, when a passive becomes active, this will be called and the passive (now
    // active) will correct itself.
    this.gtxm.shutdownAllClientsExcept(cids);
    fireTransactionManagerStartedEvent(cids);
  }

  public void goToActiveMode() {
    waitForTxnsToComplete();
    this.state = ACTIVE_MODE;
    this.resentTxnSequencer.goToActiveMode();
    this.lwmProvider.goToActiveMode();
  }

  private void waitForTxnsToComplete() {
    final Latch latch = new Latch();
    logger.info("Waiting for txns to complete");
    callBackOnTxnsInSystemCompletion(new TxnsInSystemCompletionLister() {
      public void onCompletion() {
        logger.info("No more txns in the system.");
        latch.release();
      }
    });
    try {
      latch.acquire();
    } catch (final InterruptedException e) {
      throw new AssertionError(e);
    }

  }

  public GlobalTransactionID getLowGlobalTransactionIDWatermark() {
    return this.lwmProvider.getLowGlobalTransactionIDWatermark();
  }

  public void addWaitingForAcknowledgement(final NodeID waiter, final TransactionID txnID, final NodeID waitee) {
    final TransactionAccount ci = getTransactionAccount(waiter);
    if (ci != null) {
      ci.addWaitee(waitee, txnID);
    } else {
      logger.warn("Not adding to Waiting for Ack since Waiter not found in the states map: " + waiter);
    }
    if (isActive() && waitee.getNodeType() == NodeID.CLIENT_NODE_TYPE) {
      this.channelStats.notifyTransactionBroadcastedTo(waitee);
    }
  }

  // This method is called when objects are sent to sync, this is done to maintain correct booking since things like DGC
  // relies on this to decide when to send the results
  public void objectsSynched(final NodeID to, final ServerTransactionID stid) {
    final TransactionAccount ci = getOrCreateObjectSyncTransactionAccount(stid.getSourceID()); // Local Node ID
    this.totalPendingTransactions.incrementAndGet();
    ci.addWaitee(to, stid.getClientTransactionID());
  }

  // For testing
  public boolean isWaiting(final NodeID waiter, final TransactionID txnID) {
    final TransactionAccount c = getTransactionAccount(waiter);
    return c != null && c.hasWaitees(txnID);
  }

  private void acknowledge(final NodeID waiter, final TransactionID txnID) {
    final ServerTransactionID serverTxnID = new ServerTransactionID(waiter, txnID);
    this.totalPendingTransactions.decrementAndGet();
    if (isActive() && waiter.getNodeType() == NodeID.CLIENT_NODE_TYPE) {
      this.action.acknowledgeTransaction(serverTxnID);
    }
    fireTransactionCompleteEvent(serverTxnID);
  }

  public void acknowledgement(final NodeID waiter, final TransactionID txnID, final NodeID waitee) {

    // NOTE ::Sometime you can get double notification for the same txn in server restart cases. In those cases the
    // accounting could be messed up. The counter is set to have a minimum bound of zero to avoid ugly negative values.
    // @see ChannelStatsImpl
    if (isActive() && waitee.getNodeType() == NodeID.CLIENT_NODE_TYPE) {
      this.channelStats.notifyTransactionAckedFrom(waitee);
    }

    final TransactionAccount transactionAccount = getTransactionAccount(waiter);
    if (transactionAccount == null) {
      // This can happen if an ack makes it into the system and the server crashed
      // leading to a removed state;
      logger.warn("Waiter not found in the states map: " + waiter);
      return;
    }
    if (transactionAccount.removeWaitee(waitee, txnID)) {
      acknowledge(waiter, txnID);
    }
  }

  public void apply(final ServerTransaction txn, final Map objects, final BackReferences includeIDs,
                    final ObjectInstanceMonitor instanceMonitor) {

    final ServerTransactionID stxnID = txn.getServerTransactionID();
    final NodeID sourceID = txn.getSourceID();
    final TransactionID txnID = txn.getTransactionID();
    final List changes = txn.getChanges();

    final GlobalTransactionID gtxID = txn.getGlobalTransactionID();

    final boolean active = isActive();

    for (final Iterator i = changes.iterator(); i.hasNext();) {
      final DNA orgDNA = (DNA) i.next();
      long version = orgDNA.getVersion();
      if (version == DNA.NULL_VERSION) {
        Assert.assertFalse(gtxID.isNull());
        version = gtxID.toLong();
      }
      final DNA change = new VersionizedDNAWrapper(orgDNA, version, true);
      final ManagedObject mo = (ManagedObject) objects.get(change.getObjectID());
      mo.apply(change, txnID, includeIDs, instanceMonitor, !active);
      if (this.broadcastStatsLoggingEnabled || this.objectStatsRecorder.getBroadcastDebug()) {
        // This ugly code exists so that Broadcast change handler can log more stats about the broadcasts that are
        // taking place there. This type info was lost from the DNA in one of the performance optimizations that we did.
        final DNAImpl dnaImpl = (DNAImpl) orgDNA;
        dnaImpl.setTypeClassName(mo.getManagedObjectState().getClassName());
      }
      if (active && !change.isDelta()) {
        // Only New objects reference are added here
        this.stateManager.addReference(txn.getSourceID(), mo.getID());
      }
    }

    final Map newRoots = txn.getNewRoots();

    if (newRoots.size() > 0) {
      for (final Iterator i = newRoots.entrySet().iterator(); i.hasNext();) {
        final Entry entry = (Entry) i.next();
        final String rootName = (String) entry.getKey();
        final ObjectID newID = (ObjectID) entry.getValue();
        this.objectManager.createRoot(rootName, newID);
      }
    }
    if (active) {
      this.channelStats.notifyTransaction(sourceID, txn.getNumApplicationTxn());
    }
    this.transactionRateCounter.increment(txn.getNumApplicationTxn());

    fireTransactionAppliedEvent(stxnID, txn.getNewObjectIDs());
  }

  public void skipApplyAndCommit(final ServerTransaction txn) {
    final NodeID nodeID = txn.getSourceID();
    final TransactionID txnID = txn.getTransactionID();
    final TransactionAccount ci = getTransactionAccount(nodeID);
    fireTransactionAppliedEvent(txn.getServerTransactionID(), txn.getNewObjectIDs());
    if (ci.skipApplyAndCommit(txnID)) {
      acknowledge(nodeID, txnID);
    }
  }

  /**
   * Earlier this method used to call releaseAll and then explicitly do a commit on the txn. But that implies that the
   * objects are released for other lookups before it it committed to disk. This is good for performance reason but
   * imposes a problem. The clients could read an object that has changes but it not committed to the disk yet and If
   * the server crashes then transactions are resent and may be re-applied in the clients when it should not have
   * re-applied. To avoid this we now commit inline before releasing the objects.
   * 
   * @see ObjectManagerImpl.releaseAll() for more details.
   */
  public void commit(final PersistenceTransactionProvider ptxp, final Collection objects, final Map newRoots,
                     final Collection appliedServerTransactionIDs) {
    final PersistenceTransaction ptx = ptxp.newTransaction();
    this.gtxm.commitAll(ptx, appliedServerTransactionIDs);
    // This call commits the transaction too.
    this.objectManager.releaseAll(ptx, objects);
    fireRootCreatedEvents(newRoots);
    committed(appliedServerTransactionIDs);
    if (this.commitLoggingEnabled) {
      updateCommittedStats(appliedServerTransactionIDs.size(), objects.size());
    }
  }

  private void updateCommittedStats(final int noOfTxns, final int noOfObjects) {
    this.txnsCommitted.addAndGet(noOfTxns);
    this.objectsCommitted.addAndGet(noOfObjects);
    this.noOfCommits.incrementAndGet();
    final long now = System.currentTimeMillis();
    if (now - this.lastStatsTime > 5000) {
      synchronized (this.statsLock) {
        if (now - this.lastStatsTime > 5000) {
          this.lastStatsTime = now;
          logger.info("Last 5 secs : No Of Txns committed : " + this.txnsCommitted.getAndSet(0)
                      + " No of Objects Commited : " + this.objectsCommitted.getAndSet(0) + " No of commits : "
                      + this.noOfCommits.getAndSet(0));
        }
      }
    }
  }

  /**
   * NOTE: Its important to have released all objects in the TXN before calling this event as the listeners tries to
   * lookup for the object and blocks
   */
  private void fireRootCreatedEvents(final Map newRoots) {
    for (final Iterator i = newRoots.entrySet().iterator(); i.hasNext();) {
      final Map.Entry entry = (Entry) i.next();
      fireRootCreatedEvent((String) entry.getKey(), (ObjectID) entry.getValue());
    }
  }

  public void incomingTransactions(final NodeID source, final Set txnIDs, final Collection txns, final boolean relayed) {
    final boolean active = isActive();
    final TransactionAccount ci = getOrCreateTransactionAccount(source);
    ci.incommingTransactions(txnIDs);
    this.totalPendingTransactions.addAndGet(txnIDs.size());
    if (isActive()) {
      this.totalNumOfActiveTransactions.addAndGet(txnIDs.size());
    }
    for (final Iterator i = txns.iterator(); i.hasNext();) {
      final ServerTransaction txn = (ServerTransaction) i.next();
      final ServerTransactionID stxnID = txn.getServerTransactionID();
      final TransactionID txnID = stxnID.getClientTransactionID();
      if (active && !relayed) {
        ci.relayTransactionComplete(txnID);
      } else if (!active) {
        this.gtxm.createGlobalTransactionDescIfNeeded(stxnID, txn.getGlobalTransactionID());
      }
    }
    fireIncomingTransactionsEvent(source, txnIDs);
    this.resentTxnSequencer.addTransactions(txns);
  }

  public long getTotalNumOfActiveTransactions() {
    return this.totalNumOfActiveTransactions.get();
  }

  public int getTotalPendingTransactionsCount() {
    return this.totalPendingTransactions.get();
  }

  private boolean isActive() {
    return (this.state == ACTIVE_MODE);
  }

  public void transactionsRelayed(final NodeID node, final Set serverTxnIDs) {
    final TransactionAccount ci = getTransactionAccount(node);
    if (ci == null) {
      logger.warn("transactionsRelayed(): TransactionAccount not found for " + node);
      return;
    }
    for (final Iterator i = serverTxnIDs.iterator(); i.hasNext();) {
      final ServerTransactionID txnId = (ServerTransactionID) i.next();
      final TransactionID txnID = txnId.getClientTransactionID();
      if (ci.relayTransactionComplete(txnID)) {
        acknowledge(node, txnID);
      }
    }
  }

  private void committed(final Collection txnsIds) {
    for (final Iterator i = txnsIds.iterator(); i.hasNext();) {
      final ServerTransactionID txnId = (ServerTransactionID) i.next();
      final NodeID waiter = txnId.getSourceID();
      final TransactionID txnID = txnId.getClientTransactionID();

      final TransactionAccount ci = getTransactionAccount(waiter);
      if (ci != null && ci.applyCommitted(txnID)) {
        acknowledge(waiter, txnID);
      }
    }
  }

  public void broadcasted(final NodeID waiter, final TransactionID txnID) {
    final TransactionAccount ci = getTransactionAccount(waiter);

    if (ci != null && ci.broadcastCompleted(txnID)) {
      acknowledge(waiter, txnID);
    }
  }

  private TransactionAccount getOrCreateObjectSyncTransactionAccount(final NodeID localID) {
    synchronized (this.transactionAccounts) {
      if (this.state != ACTIVE_MODE) { throw new AssertionError(
                                                                "ServerTransactionManager is not in ACTIVE_MODE, the current state = "
                                                                    + this.state); }
      TransactionAccount ta = (TransactionAccount) this.transactionAccounts.get(localID);
      if (ta == null) {
        this.transactionAccounts.put(localID, (ta = new ObjectSynchTransactionAccount(localID)));
      }
      return ta;
    }
  }

  private TransactionAccount getOrCreateTransactionAccount(final NodeID source) {
    synchronized (this.transactionAccounts) {
      TransactionAccount ta = (TransactionAccount) this.transactionAccounts.get(source);
      if (ta != null && ta instanceof ObjectSynchTransactionAccount) { throw new AssertionError(
                                                                                                "Transaction Account is of type ObjectSyncTransactionAccount : "
                                                                                                    + ta
                                                                                                    + " source Id  : "
                                                                                                    + source); }
      if (this.state == ACTIVE_MODE) {
        if ((ta == null) || (ta instanceof PassiveTransactionAccount)) {
          final Object old = this.transactionAccounts.put(source, (ta = new TransactionAccountImpl(source)));
          if (old != null) {
            logger.info("Transaction Account changed from : " + old + " to " + ta);
          }
        }
      } else {
        if ((ta == null) || (ta instanceof TransactionAccountImpl)) {
          final Object old = this.transactionAccounts.put(source, (ta = new PassiveTransactionAccount(source)));
          if (old != null) {
            logger.info("Transaction Account changed from : " + old + " to " + ta);
          }
        }
      }
      return ta;
    }
  }

  private TransactionAccount getTransactionAccount(final NodeID node) {
    return (TransactionAccount) this.transactionAccounts.get(node);
  }

  public void addRootListener(final ServerTransactionManagerEventListener listener) {
    if (listener == null) { throw new IllegalArgumentException("listener cannot be null"); }
    this.rootEventListeners.add(listener);
  }

  private void fireRootCreatedEvent(final String rootName, final ObjectID id) {
    for (final Iterator iter = this.rootEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionManagerEventListener listener = (ServerTransactionManagerEventListener) iter.next();
        listener.rootCreated(rootName, id);
      } catch (final Exception e) {
        if (logger.isDebugEnabled()) {
          logger.debug(e);
        } else {
          logger.warn("Exception in rootCreated event callback: " + e.getMessage());
        }
      }
    }
  }

  public void addTransactionListener(final ServerTransactionListener listener) {
    if (listener == null) { throw new IllegalArgumentException("listener cannot be null"); }
    this.txnEventListeners.add(listener);
  }

  public void removeTransactionListener(final ServerTransactionListener listener) {
    if (listener == null) { throw new IllegalArgumentException("listener cannot be null"); }
    this.txnEventListeners.remove(listener);
  }

  public void callBackOnTxnsInSystemCompletion(final TxnsInSystemCompletionLister l) {
    final TxnsInSystemCompletionListenerCallback callBack = new TxnsInSystemCompletionListenerCallback(l);
    final Set txnsInSystem = callBack.getTxnsInSystem();
    synchronized (this.transactionAccounts) {
      // DEV-1874, MNK-683 :: Register before adding pending server transaction ids to avoid race.
      addTransactionListener(callBack);
      for (final Iterator i = this.transactionAccounts.entrySet().iterator(); i.hasNext();) {
        final Entry entry = (Entry) i.next();
        final TransactionAccount client = (TransactionAccount) entry.getValue();
        client.addAllPendingServerTransactionIDsTo(txnsInSystem);
      }
    }
    callBack.initializationComplete();
  }

  /*
   * This method calls back the listener when all the resent TXNs are complete.
   */
  public void callBackOnResentTxnsInSystemCompletion(final TxnsInSystemCompletionLister l) {
    this.resentTxnSequencer.callBackOnResentTxnsInSystemCompletion(l);
  }

  private void fireIncomingTransactionsEvent(final NodeID nodeID, final Set serverTxnIDs) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.incomingTransactions(nodeID, serverTxnIDs);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  private void fireTransactionCompleteEvent(final ServerTransactionID stxID) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.transactionCompleted(stxID);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  private void fireTransactionAppliedEvent(final ServerTransactionID stxID, final ObjectIDSet newObjectsCreated) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.transactionApplied(stxID, newObjectsCreated);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  private void fireTransactionManagerStartedEvent(final Set cids) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.transactionManagerStarted(cids);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  public void setResentTransactionIDs(final NodeID source, final Collection transactionIDs) {
    if (transactionIDs.isEmpty()) { return; }
    final Collection stxIDs = new ArrayList();
    for (final Iterator iter = transactionIDs.iterator(); iter.hasNext();) {
      final TransactionID txn = (TransactionID) iter.next();
      stxIDs.add(new ServerTransactionID(source, txn));
    }
    fireAddResentTransactionIDsEvent(stxIDs);
  }

  private void fireAddResentTransactionIDsEvent(final Collection stxIDs) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.addResentServerTransactionIDs(stxIDs);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  private void fireClientDisconnectedEvent(final NodeID deadNodeID) {
    for (final Iterator iter = this.txnEventListeners.iterator(); iter.hasNext();) {
      try {
        final ServerTransactionListener listener = (ServerTransactionListener) iter.next();
        listener.clearAllTransactionsFor(deadNodeID);
      } catch (final Exception e) {
        logger.error("Exception in Txn listener event callback: ", e);
        throw new AssertionError(e);
      }
    }
  }

  private final class TxnsInSystemCompletionListenerCallback extends AbstractServerTransactionListener {

    private final TxnsInSystemCompletionLister callback;
    private final Set                          txnsInSystem;
    private boolean                            initialized = false;
    private int                                count       = 0;
    private int                                lastSize    = -1;

    public TxnsInSystemCompletionListenerCallback(final TxnsInSystemCompletionLister callback) {
      this.callback = callback;
      this.txnsInSystem = Collections.synchronizedSet(new HashSet());
    }

    public void initializationComplete() {
      synchronized (this.txnsInSystem) {
        this.initialized = true;
        this.lastSize = this.txnsInSystem.size();
        callBackIfEmpty();
      }
    }

    public Set getTxnsInSystem() {
      return this.txnsInSystem;
    }

    @Override
    public void transactionCompleted(final ServerTransactionID stxID) {
      synchronized (this.txnsInSystem) {
        if (this.txnsInSystem.remove(stxID) && this.initialized) {
          callBackIfEmpty();
        }
        if (++this.count % 1000 == 0) {
          if (this.lastSize == this.txnsInSystem.size()) {
            logger.warn("TxnsInSystemCompletionLister :: Still waiting for completion of " + this.txnsInSystem.size()
                        + " txns to call callback " + this.callback + " count = " + this.count + " lastSize = "
                        + this.lastSize + " No change in size. Txns = " + shortDesc(this.txnsInSystem));
          } else {
            this.lastSize = this.txnsInSystem.size();
          }
        }
      }
    }

    private void callBackIfEmpty() {
      if (this.txnsInSystem.isEmpty()) {
        removeTransactionListener(this);
        this.callback.onCompletion();
      }
    }

    private String shortDesc(final Set txns) {
      if (txns == null) {
        return "null";
      } else if (txns.size() < 11) {
        return txns.toString();
      } else {
        final StringBuilder sb = new StringBuilder("{");
        int j = 0;
        for (final Iterator i = txns.iterator(); j < 11 && i.hasNext(); j++) {
          sb.append(i.next()).append(",");
        }
        sb.append("....<more> }");
        return sb.toString();
      }
    }
  }
}
