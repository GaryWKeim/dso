/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.objectserver;

import com.tc.async.api.ConfigurationContext;
import com.tc.async.api.PostInit;
import com.tc.async.api.Sink;
import com.tc.l2.msg.ObjectSyncMessage;
import com.tc.net.ClientID;
import com.tc.net.NodeID;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.MetaDataReader;
import com.tc.object.dna.impl.ObjectStringSerializer;
import com.tc.object.locks.LockID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.object.tx.TransactionID;
import com.tc.object.tx.TxnBatchID;
import com.tc.object.tx.TxnType;
import com.tc.objectserver.api.EvictableEntry;
import com.tc.objectserver.context.ServerTransactionCompleteContext;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.tx.AbstractServerTransactionListener;
import com.tc.objectserver.tx.RemoveAllDNA;
import com.tc.objectserver.tx.RemoveAllMetaDataReader;
import com.tc.objectserver.tx.RemoveEventListeningClientDNA;
import com.tc.objectserver.tx.ServerEvictionTransactionImpl;
import com.tc.objectserver.tx.ServerMapEvictionDNA;
import com.tc.objectserver.tx.ServerTransaction;
import com.tc.objectserver.tx.ServerTransactionImpl;
import com.tc.util.SequenceID;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class ServerTransactionFactory extends AbstractServerTransactionListener implements PostInit {

  private static final LockID[]                NULL_LOCK_ID          = new LockID[0];
  private static final long[]                  EMPTY_HIGH_WATER_MARK = new long[0];

  private final AtomicLong                     tid                   = new AtomicLong();
  private final NodeID                         nodeId;
  private Sink                                 lwmUpdateSink;

  public ServerTransactionFactory(final NodeID nodeId) {
    this.nodeId = nodeId;
  }

  public ServerTransaction createTxnFrom(final ObjectSyncMessage syncMsg) {
    final ObjectSyncServerTransaction txn = new ObjectSyncServerTransaction(syncMsg.getServerTransactionID(),
                                                                            syncMsg.getOids(), syncMsg.getDnaCount(),
                                                                            syncMsg.getDNAs(), syncMsg.getRootsMap(),
                                                                            syncMsg.messageFrom());
    return txn;
  }

  @Override
  public void initializeContext(final ConfigurationContext context) {
    lwmUpdateSink = context.getStage(ServerConfigurationContext.TRANSACTION_LOWWATERMARK_STAGE).getSink();
  }

  @Override
  public void transactionCompleted(final ServerTransactionID stxID) {
    if (stxID.isServerGeneratedTransaction()) {
      lwmUpdateSink.add(new ServerTransactionCompleteContext(stxID));
    }
  }

  private TransactionID getNextTransactionID() {
    return new TransactionID(this.tid.incrementAndGet());
  }

  /**
   * Since the transaction IDs that are generated by this factory are not coming from a persisted DB this will
   * regenerate the same TransactionID on server restart, but the nodeID should be different.
   */
  public ServerTransactionID getNextServerTransactionID() {
    return new ServerTransactionID(nodeId, getNextTransactionID());
  }

  public ServerTransaction createServerMapEvictionTransactionFor(final ObjectID oid, final Map<Object, EvictableEntry> candidates,
                                                                 final ObjectStringSerializer serializer,
                                                                 final String cacheName) {
    return createServerMapEvictionTransactionFor(getNextServerTransactionID(), oid,
        candidates, serializer,
        cacheName);

  }

  public ServerTransaction createServerMapEvictionTransactionFor(final ServerTransactionID serverTransactionID, final ObjectID oid,
                                                                 final Map<Object, EvictableEntry> candidates,
                                                                 final ObjectStringSerializer serializer,
                                                                 final String cacheName) {
    return new ServerEvictionTransactionImpl(TxnBatchID.NULL_BATCH_ID, serverTransactionID.getClientTransactionID(), SequenceID.NULL_ID,
        NULL_LOCK_ID, serverTransactionID.getSourceID(),
        Collections.singletonList(createServerMapEvictionDNAFor(oid, cacheName, candidates)),
        serializer, Collections.EMPTY_MAP, TxnType.NORMAL, Collections.EMPTY_LIST,
        createEvictionMetaDataFor(oid, cacheName, candidates), 1,
        EMPTY_HIGH_WATER_MARK);
  }

  public ServerTransaction createRemoveAllTransaction(final ServerTransactionID serverTransactionID, final ObjectID oid,
                                                      final String cacheName, final Map<Object, EvictableEntry> candidates,
                                                      ObjectStringSerializer objectStringSerializer) {
    return new ServerEvictionTransactionImpl(TxnBatchID.NULL_BATCH_ID, serverTransactionID.getClientTransactionID(),
        SequenceID.NULL_ID, NULL_LOCK_ID, serverTransactionID.getSourceID(),
        Collections.singletonList(createRemoveAllDNA(oid, cacheName, candidates)), objectStringSerializer,
        Collections.emptyMap(), TxnType.NORMAL, Collections.emptyList(),
        createEvictionMetaDataFor(oid, cacheName, candidates), 1, EMPTY_HIGH_WATER_MARK);
  }

  public ServerTransaction createRemoveEventListeningClientTransaction(final ObjectID oid,
                                                                       final ClientID clientID,
                                                                       final ObjectStringSerializer objectStringSerializer) {

    return createRemoveEventListeningClientTransaction(getNextServerTransactionID(), oid,
                                                       clientID, objectStringSerializer);

  }

  private ServerTransaction createRemoveEventListeningClientTransaction(final ServerTransactionID serverTransactionID,
                                                                       final ObjectID oid, final ClientID clientID,
                                                                       final ObjectStringSerializer objectStringSerializer) {
    return new ServerTransactionImpl(TxnBatchID.NULL_BATCH_ID, serverTransactionID.getClientTransactionID(),
                                             SequenceID.NULL_ID, NULL_LOCK_ID, serverTransactionID.getSourceID(),
                                             Collections.singletonList(createRemoveEventListeningClientDNA(oid, clientID)),
                                             objectStringSerializer, Collections.emptyMap(), TxnType.NORMAL,
                                             Collections.emptyList(),
                                             new MetaDataReader[] {}, 1, EMPTY_HIGH_WATER_MARK);
  }

  private DNA createRemoveEventListeningClientDNA(ObjectID oid, ClientID clientID) {
    return new RemoveEventListeningClientDNA(oid, clientID);
  }

  private MetaDataReader[] createEvictionMetaDataFor(ObjectID oid, String cacheName, Map<Object, EvictableEntry> candidates) {
    return new MetaDataReader[] { new RemoveAllMetaDataReader(oid, cacheName, candidates) };
  }

  private DNA createServerMapEvictionDNAFor(final ObjectID oid, String cacheName, final Map<Object, EvictableEntry> candidates) {
    return new ServerMapEvictionDNA(oid, candidates, cacheName);
  }

  private DNA createRemoveAllDNA(final ObjectID oid, final String cacheName, final Map<Object, EvictableEntry> candidates) {
    return new RemoveAllDNA(oid, cacheName, candidates);
  }
}
