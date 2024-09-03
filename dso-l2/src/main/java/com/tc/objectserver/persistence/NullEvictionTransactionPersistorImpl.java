/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tc.objectserver.persistence;


import com.tc.object.ObjectID;
import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.api.EvictableEntry;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class NullEvictionTransactionPersistorImpl implements EvictionTransactionPersistor {

  @Override
  public EvictionRemoveContext getEviction(ServerTransactionID serverTransactionID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void saveEviction(ServerTransactionID serverTransactionID, final ObjectID oid, final String cacheName, final Map<Object, EvictableEntry> samples) {

  }

  @Override
  public void removeEviction(ServerTransactionID serverTransactionID) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<ServerTransactionID> getPersistedTransactions() {
    return Collections.emptySet();
  }
}
