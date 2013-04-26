package com.tc.object.msg;

import com.tc.bytes.TCByteBuffer;
import com.tc.io.TCByteBufferOutputStream;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.MessageMonitor;
import com.tc.net.protocol.tcm.TCMessageHeader;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.object.InterestType;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.impl.SerializerDNAEncodingImpl;
import com.tc.object.dna.impl.StorageDNAEncodingImpl;
import com.tc.object.session.SessionID;

import java.io.IOException;

/**
 * @author Eugene Shelestovich
 */
public class ServerInterestMessageImpl extends DSOMessageBase implements ServerInterestMessage {

  private final static DNAEncoding encoder = new SerializerDNAEncodingImpl();
  private final static DNAEncoding decoder = new StorageDNAEncodingImpl();

  private static final byte KEY_ID = 0;
  private static final byte TYPE_ID = 1;
  private static final byte CACHE_NAME_ID = 2;

  private Object key;
  private InterestType type;
  private String cacheName;

  public ServerInterestMessageImpl(final SessionID sessionID, final MessageMonitor monitor,
                                   final TCByteBufferOutputStream out, final MessageChannel channel,
                                   final TCMessageType type) {
    super(sessionID, monitor, out, channel, type);
  }

  public ServerInterestMessageImpl(final SessionID sessionID, final MessageMonitor monitor,
                                   final MessageChannel channel, final TCMessageHeader header,
                                   final TCByteBuffer[] data) {
    super(sessionID, monitor, channel, header, data);
  }

  @Override
  protected void dehydrateValues() {
    putNVPair(TYPE_ID, type.toInt());
    putNVPair(KEY_ID, true);
    encoder.encode(key, getOutputStream());
    putNVPair(CACHE_NAME_ID, cacheName);
  }

  @Override
  protected boolean hydrateValue(byte name) throws IOException {
    switch (name) {
      case TYPE_ID:
        type = InterestType.fromInt(getIntValue());
        return true;
      case CACHE_NAME_ID:
        cacheName = getStringValue();
        return true;
      case KEY_ID:
        try {
          getBooleanValue();
          key = decoder.decode(getInputStream());
        } catch (ClassNotFoundException e) {
          return false;
        }
        return true;
      default:
        return false;
    }
  }

  @Override
  public Object getKey() {
    return key;
  }

  @Override
  public void setKey(final Object key) {
    this.key = key;
  }

  @Override
  public InterestType getInterestType() {
    return type;
  }

  @Override
  public void setInterestType(final InterestType type) {
    this.type = type;
  }

  @Override
  public String getCacheName() {
    return cacheName;
  }

  @Override
  public void setCacheName(final String cacheName) {
    this.cacheName = cacheName;
  }
}