/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.transport;

import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArraySet;

import com.tc.bytes.TCByteBuffer;
import com.tc.exception.ImplementMe;
import com.tc.net.core.TCConnection;
import com.tc.net.protocol.TCNetworkHeader;
import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.TCNetworkMessageEvent;
import com.tc.net.protocol.TCNetworkMessageListener;

import java.util.Iterator;
import java.util.Set;

public class TestWireProtocolMessage implements WireProtocolMessage {
  public TCConnection    connection;
  public TCNetworkHeader header;
  private final Set      listeners = new CopyOnWriteArraySet();

  public short getMessageProtocol() {
    throw new ImplementMe();
  }

  public WireProtocolHeader getWireProtocolHeader() {
    throw new ImplementMe();
  }

  public TCNetworkHeader getHeader() {
    return header;
  }

  public TCNetworkMessage getMessagePayload() {
    throw new ImplementMe();
  }

  public TCByteBuffer[] getPayload() {
    throw new ImplementMe();
  }

  public TCByteBuffer[] getEntireMessageData() {
    throw new ImplementMe();
  }

  public boolean isSealed() {
    throw new ImplementMe();
  }

  public void seal() {
    throw new ImplementMe();

  }

  public int getDataLength() {
    throw new ImplementMe();
  }

  public int getHeaderLength() {
    throw new ImplementMe();
  }

  public int getTotalLength() {
    throw new ImplementMe();
  }

  public void wasSent() {
    throw new ImplementMe();

  }

  public void setSentCallback(Runnable callback) {
    throw new ImplementMe();

  }

  public Runnable getSentCallback() {
    throw new ImplementMe();
  }

  public TCConnection getSource() {
    return connection;
  }

  public void recycle() {
    return;
  }

  public void addListener(TCNetworkMessageListener listener) {
    listeners.add(listener);
  }

  public boolean isEmptyListeners() {
    return (listeners.isEmpty());
  }

  public void notifyMessageEvent(TCNetworkMessageEvent event) {
    for (Iterator i = listeners.iterator(); i.hasNext();) {
      ((TCNetworkMessageListener) i.next()).notifyMessageEvent(event);
    }
  }

}
