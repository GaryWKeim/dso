/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.net.protocol.transport;

import java.util.Set;


public interface ConnectionIdFactory {

  public ConnectionID nextConnectionId();
  
  public Set loadConnectionIDs();

}
