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
package com.tc.objectserver.locks.factory;

import com.tc.object.locks.LockID;
import com.tc.objectserver.locks.ServerLock;
import com.tc.objectserver.locks.LockFactory;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;

public class ServerLockFactoryImpl implements LockFactory {
  private final static boolean GREEDY_LOCKS_ENABLED = TCPropertiesImpl
                                                     .getProperties()
                                                     .getBoolean(TCPropertiesConsts.L2_LOCKMANAGER_GREEDY_LOCKS_ENABLED);
  private final LockFactory    factory;

  public ServerLockFactoryImpl() {
    if (GREEDY_LOCKS_ENABLED) {
      factory = new GreedyPolicyFactory();
    } else {
      factory = new NonGreedyLockPolicyFactory();
    }
  }

  @Override
  public ServerLock createLock(LockID lid) {
    return factory.createLock(lid);
  }
}
