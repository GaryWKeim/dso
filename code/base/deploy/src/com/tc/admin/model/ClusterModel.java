/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.model;

import com.tc.admin.ConnectionContext;
import com.tc.object.ObjectID;
import com.tc.objectserver.api.NoSuchObjectException;
import com.tc.objectserver.mgmt.ManagedObjectFacade;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.swing.event.EventListenerList;

public class ClusterModel implements IClusterModel {
  private String                                               name;
  private IServer                                              connectServer;
  private boolean                                              autoConnect;
  private boolean                                              connected;
  private boolean                                              ready;
  private IServerGroup[]                                       serverGroups;
  protected EventListenerList                                  listenerList;

  private IServer                                              activeCoordinator;
  // private boolean userDisconnecting;
  protected PropertyChangeSupport                              propertyChangeSupport;

  protected ConnectServerListener                              connectServerListener;
  protected ActiveCoordinatorListener                          activeCoordinatorListener;
  protected ServerGroupListener                                serverGroupListener;
  protected ServerStateListenerDelegate                        serverStateListenerDelegate;
  protected IServerGroup                                       activeCoordinatorGroup;

  private String                                               displayLabel;
  public boolean                                               isConnectListening;

  private final Map<PollScope, Map<String, EventListenerList>> pollScopes;
  private Set<PolledAttributeListener>                         allScopedPollListeners;

  public ClusterModel() {
    this(ConnectionContext.DEFAULT_HOST, ConnectionContext.DEFAULT_PORT, ConnectionContext.DEFAULT_AUTO_CONNECT);
  }

  public ClusterModel(final String host, final int jmxPort, boolean autoConnect) {
    isConnectListening = false;
    listenerList = new EventListenerList();
    connectServer = createConnectServer(host, jmxPort);
    displayLabel = connectServer.toString();
    propertyChangeSupport = new PropertyChangeSupport(this);
    connectServerListener = new ConnectServerListener();
    activeCoordinatorListener = new ActiveCoordinatorListener();
    serverGroupListener = new ServerGroupListener();
    serverStateListenerDelegate = new ServerStateListenerDelegate();
    pollScopes = new HashMap<PollScope, Map<String, EventListenerList>>();
    setAutoConnect(autoConnect);
  }

  public String getName() {
    return name != null ? name : Integer.toString(hashCode());
  }

  public void setName(String name) {
    this.name = name;
  }

  public void startConnectListener() {
    if (!isConnectListening) {
      connectServer.addPropertyChangeListener(connectServerListener);
      if (autoConnect) {
        connectServer.setAutoConnect(autoConnect);
      }
    }
    isConnectListening = true;
  }

  protected void stopConnectListener() {
    if (isConnectListening) {
      connectServer.removePropertyChangeListener(connectServerListener);
      connectServer.setAutoConnect(false);
      connectServer.disconnect();
    }
    isConnectListening = false;
  }

  protected IServer createConnectServer(String host, int jmxPort) {
    return new Server(this, host, jmxPort, false);
  }

  public IServer getConnectServer() {
    return connectServer;
  }

  public void setConnectionCredentials(String[] creds) {
    connectServer.setConnectionCredentials(creds);
    if (isReady()) {
      for (IServerGroup group : getServerGroups()) {
        group.setConnectionCredentials(creds);
      }
    }
  }

  public synchronized boolean isAutoConnect() {
    return this.autoConnect;
  }

  public void setAutoConnect(boolean autoConnect) {
    boolean oldAutoConnect;
    synchronized (this) {
      oldAutoConnect = isAutoConnect();
      this.autoConnect = autoConnect;
    }
    firePropertyChange(PROP_AUTO_CONNECT, oldAutoConnect, autoConnect);
    if (isConnectListening) {
      connectServer.setAutoConnect(autoConnect);
    }
  }

  public String[] getConnectionCredentials() {
    return connectServer.getConnectionCredentials();
  }

  public Map<String, Object> getConnectionEnvironment() {
    return connectServer.getConnectionEnvironment();
  }

  public JMXConnector getJMXConnector() {
    return connectServer.getJMXConnector();
  }

  public void setJMXConnector(JMXConnector jmxc) throws IOException {
    connectServer.setJMXConnector(jmxc);
  }

  public void refreshCachedCredentials() {
    connectServer.refreshCachedCredentials();
  }

  public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
    if (listener != null && propertyChangeSupport != null) {
      propertyChangeSupport.removePropertyChangeListener(listener);
      propertyChangeSupport.addPropertyChangeListener(listener);
    }
  }

  public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
    if (listener != null && propertyChangeSupport != null) {
      propertyChangeSupport.removePropertyChangeListener(listener);
    }
  }

  public void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
    PropertyChangeSupport pcs;
    synchronized (this) {
      pcs = propertyChangeSupport;
    }
    if (pcs != null && (oldValue != null || newValue != null)) {
      pcs.firePropertyChange(propertyName, oldValue, newValue);
    }
  }

  private void setActiveCoordinator(IServer activeCoord) {
    IServer oldActiveServer = _setActiveCoordinator(activeCoord);
    firePropertyChange(PROP_ACTIVE_COORDINATOR, oldActiveServer, activeCoord);
    if (activeCoord != null) {
      displayLabel = activeCoord.toString();
      activeCoord.addPropertyChangeListener(activeCoordinatorListener);
    } else {
      displayLabel = connectServer.toString();
    }
  }

  private IServer _setActiveCoordinator(IServer theActiveCoordinator) {
    IServer oldActiveCoordinator;
    synchronized (this) {
      oldActiveCoordinator = activeCoordinator;
      activeCoordinator = theActiveCoordinator;
    }
    return oldActiveCoordinator;
  }

  private IServer _clearActiveCoordinator() {
    IServer oldActiveCoordinator;
    synchronized (this) {
      oldActiveCoordinator = activeCoordinator;
      activeCoordinator = null;
    }
    return oldActiveCoordinator;
  }

  private void clearActiveCoordinator() {
    IServer oldActiveCoordinator = _clearActiveCoordinator();
    if (oldActiveCoordinator != null) {
      oldActiveCoordinator.removePropertyChangeListener(activeCoordinatorListener);
      firePropertyChange(PROP_ACTIVE_COORDINATOR, oldActiveCoordinator, null);
    }
  }

  public synchronized IServer getActiveCoordinator() {
    return activeCoordinator;
  }

  public synchronized IServerGroup[] getServerGroups() {
    if (serverGroups == null) { return IServerGroup.NULL_SET; }
    return Arrays.asList(serverGroups).toArray(IServerGroup.NULL_SET);
  }

  public String dump() {
    StringBuilder sb = new StringBuilder();
    sb.append("connected=");
    sb.append(isConnected());
    sb.append(", ready=");
    sb.append(isReady());
    sb.append(", active-server=");
    sb.append(getActiveCoordinator());
    return sb.toString();
  }

  public boolean determineReady() {
    for (IServerGroup group : serverGroups) {
      if (!group.isReady()) { return false; }
    }
    return serverGroups != IServerGroup.NULL_SET;
  }

  public boolean determineConnected() {
    for (IServerGroup group : serverGroups) {
      if (group.isConnected()) { return true; }
    }
    return false;
  }

  protected void setReady(boolean ready) {
    boolean oldReady;
    synchronized (this) {
      oldReady = isReady();
      this.ready = ready;
    }
    firePropertyChange(PROP_READY, oldReady, ready);
    if (oldReady != ready) {
      setPolledAttributeTaskEnabled(ready);
    }
  }

  public synchronized boolean isReady() {
    return ready;
  }

  private ScheduledThreadPoolExecutor scheduledExecutor;
  private long                        pollPeriodSeconds = 3;

  public void setPollPeriod(long seconds) {
    pollPeriodSeconds = seconds;
  }

  public long getPollPeriod() {
    return pollPeriodSeconds;
  }

  private synchronized void setScheduledExecutor(ScheduledThreadPoolExecutor scheduledExecutor) {
    this.scheduledExecutor = scheduledExecutor;
  }

  private synchronized ScheduledThreadPoolExecutor getScheduledExecutor() {
    return scheduledExecutor;
  }

  private synchronized void setPolledAttributeTaskEnabled(boolean enabled) {
    if (enabled) {
      scheduledExecutor = new ScheduledThreadPoolExecutor(1);
      setScheduledExecutor(scheduledExecutor);
      scheduledExecutor.schedule(new PollTask(), pollPeriodSeconds, TimeUnit.SECONDS);
    } else if (scheduledExecutor != null) {
      scheduledExecutor.shutdownNow();
      scheduledExecutor = null;
    }
  }

  private static final ExecutorService executor = Executors.newCachedThreadPool();

  private static class NodePollResult {
    IClusterNode                         clusterNode;
    Map<ObjectName, Map<String, Object>> attributeMap;

    private NodePollResult(IClusterNode clusterNode) {
      this.clusterNode = clusterNode;
      attributeMap = Collections.emptyMap();
    }

    private NodePollResult(IClusterNode node, Map<ObjectName, Map<String, Object>> attributeMap) {
      this(node);
      this.attributeMap = attributeMap;
    }
  }

  private static class ClientPollResult extends NodePollResult {
    Set<ObjectName> objectNames;

    private ClientPollResult(IClient client, Set<ObjectName> objectNames) {
      super(client);
      this.objectNames = objectNames;
      attributeMap = new HashMap<ObjectName, Map<String, Object>>();
    }
  }

  private class NodePollWorker implements Callable<Collection<NodePollResult>> {
    private final IServer                      server;
    private final Map<ObjectName, Set<String>> attrMap;

    NodePollWorker(IServer server, Map<ObjectName, Set<String>> attrMap) {
      this.server = server;
      this.attrMap = attrMap;
    }

    public Collection<NodePollResult> call() throws Exception {
      if (server.isActiveCoordinator()) {
        List<ClientPollResult> cprList = new ArrayList<ClientPollResult>();
        for (IClient client : server.getClients()) {
          Map<ObjectName, Set<String>> clientAttrMap = client.getPolledAttributes();
          mergeScopePolledAttributes(client, clientAttrMap, PollScope.CLIENTS);
          if (!clientAttrMap.isEmpty()) {
            attrMap.putAll(clientAttrMap);
            cprList.add(new ClientPollResult(client, clientAttrMap.keySet()));
          }
        }
        if (attrMap.isEmpty()) { return Collections.singleton(new NodePollResult(server)); }
        Map<ObjectName, Map<String, Object>> combinedResultMap = server.getAttributeMap(attrMap, pollPeriodSeconds,
                                                                                        TimeUnit.SECONDS);
        if (!cprList.isEmpty()) {
          List<NodePollResult> result = new ArrayList<NodePollResult>();
          Iterator<ClientPollResult> cprIter = cprList.iterator();
          while (cprIter.hasNext()) {
            ClientPollResult cpr = cprIter.next();
            Iterator<ObjectName> onIter = cpr.objectNames.iterator();
            while (onIter.hasNext()) {
              ObjectName on = onIter.next();
              Map<String, Object> onAttrMap = combinedResultMap.remove(on);
              if (onAttrMap != null) {
                cpr.attributeMap.put(on, onAttrMap);
              }
            }
            result.add(cpr);
          }
          result.add(new NodePollResult(server, combinedResultMap));
          return result;
        } else {
          return Collections.singleton(new NodePollResult(server, combinedResultMap));
        }
      } else {
        Map<ObjectName, Map<String, Object>> resultMap;
        if (attrMap.isEmpty()) {
          resultMap = Collections.emptyMap();
        } else {
          resultMap = server.getAttributeMap(attrMap, pollPeriodSeconds, TimeUnit.SECONDS);
        }
        return Collections.singleton(new NodePollResult(server, resultMap));
      }
    }
  }

  private void mergeScopePolledAttributes(IClusterNode node, Map<ObjectName, Set<String>> attributeMap, PollScope scope) {
    Set<String> polledAttrs = getPolledAttributes(scope);
    Iterator<String> polledAttrIter = polledAttrs.iterator();
    while (polledAttrIter.hasNext()) {
      PolledAttribute pa = node.getPolledAttribute(polledAttrIter.next());
      if (pa != null) {
        ObjectName objectName = pa.getObjectName();
        Set<String> attrs = attributeMap.get(objectName);
        if (attrs == null) {
          attributeMap.put(objectName, attrs = new HashSet<String>());
        }
        attrs.add(pa.getAttribute());
      }
    }
  }

  private static class PolledAttributesResultImpl implements PolledAttributesResult {
    private final Map<IClusterNode, Map<ObjectName, Map<String, Object>>> result;

    PolledAttributesResultImpl(Map<IClusterNode, Map<ObjectName, Map<String, Object>>> result) {
      this.result = result;
    }

    public Map<ObjectName, Map<String, Object>> getAttributeMap(IClusterNode clusterNode) {
      return result.get(clusterNode);
    }

    public Object getPolledAttribute(IClusterNode clusterNode, ObjectName objectName, String attribute) {
      Map<ObjectName, Map<String, Object>> onMap = getAttributeMap(clusterNode);
      if (onMap != null) {
        Map<String, Object> attrMap = onMap.get(objectName);
        if (attrMap != null) { return attrMap.get(attribute); }
      }
      return null;
    }

    public Object getPolledAttribute(IClusterNode clusterNode, String attr) {
      return getPolledAttribute(clusterNode, clusterNode.getPolledAttribute(attr));
    }

    public Object getPolledAttribute(IClusterNode clusterNode, PolledAttribute polledAttr) {
      if (polledAttr != null) { return getPolledAttribute(clusterNode, polledAttr.getObjectName(), polledAttr
          .getAttribute()); }
      return null;
    }
  }

  private class PollTask implements Runnable {
    public void run() {
      List<Callable<Collection<NodePollResult>>> tasks = new ArrayList<Callable<Collection<NodePollResult>>>();
      for (IServerGroup group : getServerGroups()) {
        for (IServer server : group.getMembers()) {
          if (server.isReady()) {
            Map<ObjectName, Set<String>> attributeMap = server.getPolledAttributes();
            mergeScopePolledAttributes(server, attributeMap, PollScope.ALL_SERVERS);
            if (server.isActive()) {
              mergeScopePolledAttributes(server, attributeMap, PollScope.ACTIVE_SERVERS);
            }
            if (server.isActiveCoordinator() || !attributeMap.isEmpty()) {
              tasks.add(new NodePollWorker(server, attributeMap));
            }
          }
        }
      }
      try {
        final Map<IClusterNode, Map<ObjectName, Map<String, Object>>> resultObj = new HashMap<IClusterNode, Map<ObjectName, Map<String, Object>>>();
        Set<PolledAttributeListener> listenerSet = getAllScopedPollListeners();
        List<Future<Collection<NodePollResult>>> results = executor.invokeAll(tasks, 2 * pollPeriodSeconds,
                                                                              TimeUnit.SECONDS);
        Iterator<Future<Collection<NodePollResult>>> resultIter = results.iterator();
        while (resultIter.hasNext()) {
          Future<Collection<NodePollResult>> future = resultIter.next();
          if (future.isDone()) {
            try {
              Iterator<NodePollResult> nodeResult = future.get().iterator();
              while (nodeResult.hasNext()) {
                NodePollResult npr = nodeResult.next();
                if (!npr.attributeMap.isEmpty()) {
                  resultObj.put(npr.clusterNode, npr.attributeMap);
                  Set<PolledAttributeListener> listeners = npr.clusterNode.getPolledAttributeListeners();
                  listenerSet.addAll(listeners);
                }
              }
            } catch (Exception e) {
              /**/
            }
          }
        }
        Iterator<PolledAttributeListener> listenerIter = listenerSet.iterator();
        PolledAttributesResult par = new PolledAttributesResultImpl(resultObj);
        while (listenerIter.hasNext()) {
          listenerIter.next().attributesPolled(par);
        }
      } catch (InterruptedException ie) {/**/
      }
      ScheduledThreadPoolExecutor theScheduledExecutor = getScheduledExecutor();
      if (theScheduledExecutor != null) {
        theScheduledExecutor.schedule(PollTask.this, pollPeriodSeconds, TimeUnit.SECONDS);
      }
    }
  }

  public boolean hasConnectError() {
    return connectServer.hasConnectError();
  }

  public Exception getConnectError() {
    return connectServer.getConnectError();
  }

  public String getConnectErrorMessage(Exception e) {
    return connectServer.getConnectErrorMessage(e);
  }

  // private synchronized boolean isUserDisconnecting() {
  // return userDisconnecting;
  // }

  private synchronized void setUserDisconnecting(boolean userDisconnecting) {
    // this.userDisconnecting = userDisconnecting;
  }

  public synchronized void addServerStateListener(ServerStateListener listener) {
    removeServerStateListener(listener);
    listenerList.add(ServerStateListener.class, listener);
  }

  public synchronized void removeServerStateListener(ServerStateListener listener) {
    listenerList.remove(ServerStateListener.class, listener);
  }

  protected void fireServerStateChanged(IServer server, PropertyChangeEvent pce) {
    Object[] listeners = listenerList.getListenerList();
    for (int i = listeners.length - 2; i >= 0; i -= 2) {
      if (listeners[i] == ServerStateListener.class) {
        ((ServerStateListener) listeners[i + 1]).serverStateChanged(server, pce);
      }
    }
  }

  public void connect() {
    startConnectListener();
  }

  public void disconnect() {
    setUserDisconnecting(true);
    for (IServerGroup group : getServerGroups()) {
      group.disconnect();
    }
  }

  protected void setConnected(boolean connected) {
    boolean oldConnected;
    synchronized (this) {
      oldConnected = isConnected();
      this.connected = connected;
    }
    firePropertyChange(PROP_CONNECTED, oldConnected, connected);
    setUserDisconnecting(false);
  }

  public synchronized boolean isConnected() {
    return connected;
  }

  private static Future<String> threadDumpFuture(ExecutorService pool, final IClusterNode node, final long time) {
    return pool.submit(new Callable<String>() {
      public String call() throws Exception {
        return node.isReady() ? node.takeThreadDump(time) : "";
      }
    });
  }

  public synchronized Future<String> takeThreadDump(IClusterNode node) {
    return threadDumpFuture(executor, node, System.currentTimeMillis());
  }

  public synchronized Map<IClusterNode, Future<String>> takeThreadDump() {
    IServer activeCoord = getActiveCoordinator();

    if (activeCoord != null) {
      ExecutorService pool = Executors.newCachedThreadPool();
      long requestMillis = System.currentTimeMillis();
      Map<IClusterNode, Future<String>> map = new HashMap<IClusterNode, Future<String>>();
      for (IServerGroup group : getServerGroups()) {
        for (IServer server : group.getMembers()) {
          map.put(server, threadDumpFuture(pool, server, requestMillis));
        }
      }
      for (IClient client : activeCoord.getClients()) {
        map.put(client, threadDumpFuture(pool, client, requestMillis));
      }
      pool.shutdown();
      return map;
    }

    return Collections.emptyMap();
  }

  public Map<IClient, Long> getClientTransactionRates() {
    IServer activeCoord = getActiveCoordinator();
    if (activeCoord != null) { return activeCoord.getClientTransactionRates(); }
    return Collections.emptyMap();
  }

  public Map<IServer, Number> getServerTransactionRates() {
    Map<IServer, Number> result = new HashMap<IServer, Number>();
    for (IServerGroup group : getServerGroups()) {
      for (IServer server : group.getMembers()) {
        if (server.isReady()) {
          result.put(server, server.getTransactionRate());
        }
      }
    }
    return result;
  }

  public Map<IClient, Map<String, Object>> getPrimaryClientStatistics() {
    IServer activeCoord = getActiveCoordinator();
    if (activeCoord != null) { return activeCoord.getPrimaryClientStatistics(); }
    return Collections.emptyMap();
  }

  public Map<IServer, Map<String, Object>> getPrimaryServerStatistics() {
    Map<IServer, Map<String, Object>> result = new HashMap<IServer, Map<String, Object>>();
    for (IServerGroup group : getServerGroups()) {
      for (IServer server : group.getMembers()) {
        if (server.isReady()) {
          result.put(server, server.getPrimaryStatistics());
        }
      }
    }
    return result;
  }

  public synchronized void tearDown() {
    connectServer.removePropertyChangeListener(connectServerListener);
    connectServer.tearDown();
    connectServer = null;
    if (serverGroups != null) {
      for (IServerGroup group : serverGroups) {
        group.removeServerStateListener(serverStateListenerDelegate);
        group.removePropertyChangeListener(serverGroupListener);
        group.tearDown();
      }
      serverGroups = null;
    }
    connectServerListener = null;
    activeCoordinatorListener = null;
    serverStateListenerDelegate = null;
    activeCoordinator = null;
    activeCoordinatorGroup = null;
  }

  public void setHost(String host) {
    connectServer.setHost(host);
  }

  public String getHost() {
    return connectServer.getHost();
  }

  public void setPort(int port) {
    connectServer.setPort(port);
  }

  public int getPort() {
    return connectServer.getPort();
  }

  class ActiveCoordinatorListener implements PropertyChangeListener {
    public void propertyChange(PropertyChangeEvent evt) {
      String prop = evt.getPropertyName();
      IServer server = (IServer) evt.getSource();
      if (IClusterModelElement.PROP_READY.equals(prop)) {
        if (!server.isReady()) {
          clearActiveCoordinator();
          setReady(false);
          // setConnected(!isUserDisconnecting() && determineConnected());
        }
      }
    }
  }

  class ConnectServerListener implements PropertyChangeListener {
    public void propertyChange(PropertyChangeEvent evt) {
      if (!isConnectListening) { throw new RuntimeException(
                                                            "ConnectServerListener.propertyChange called when not listening"); }
      String prop = evt.getPropertyName();
      if (IServer.PROP_CONNECTED.equals(prop)) {
        if (connectServer.isConnected()) {
          serverGroups = connectServer.getClusterServerGroups();
          for (IServerGroup group : serverGroups) {
            if (group.isCoordinator()) {
              activeCoordinatorGroup = group;
              activeCoordinator = group.getActiveServer();
            }
            group.addServerStateListener(serverStateListenerDelegate);
            group.addPropertyChangeListener(serverGroupListener);
            group.connect();
          }
          stopConnectListener();
          setConnected(true);
        }
      }
    }
  }

  protected class ServerStateListenerDelegate implements ServerStateListener {
    public void serverStateChanged(IServer server, PropertyChangeEvent pce) {
      fireServerStateChanged(server, pce);
    }
  }

  protected class ServerGroupListener implements PropertyChangeListener {
    public void propertyChange(PropertyChangeEvent evt) {
      String prop = evt.getPropertyName();
      IServerGroup group = (IServerGroup) evt.getSource();
      if (IServerGroup.PROP_ACTIVE_SERVER.equals(prop)) {
        if (group.isCoordinator()) {
          setActiveCoordinator(group.getActiveServer());
        }
        setReady(determineReady());
      } else if (IServerGroup.PROP_CONNECTED.equals(prop)) {
        setConnected(determineConnected());
      }
    }
  }

  @Override
  public String toString() {
    return name != null ? name : displayLabel;
  }

  public int getLiveObjectCount() {
    IServerGroup[] groups = getServerGroups();
    int result = 0;
    if (groups != null) {
      for (IServerGroup group : groups) {
        IServer activeServer = group.getActiveServer();
        if (activeServer != null) {
          result += activeServer.getLiveObjectCount();
        }
      }
    }
    return result;
  }

  public IBasicObject[] getRoots() {
    IServer activeCoord = getActiveCoordinator();
    if (activeCoord != null) { return activeCoord.getRoots(); }
    return IBasicObject.NULL_SET;
  }

  public IClient[] getClients() {
    IServer activeCoord = getActiveCoordinator();
    if (activeCoord != null) { return activeCoord.getClients(); }
    return IClient.NULL_SET;
  }

  private IServerGroup groupForObjectID(ObjectID objectID) {
    IServerGroup[] theServerGroups = getServerGroups();
    if (theServerGroups != null) {
      for (IServerGroup group : theServerGroups) {
        if (objectID.getGroupID() == group.getId()) { return group; }
      }
    }
    return null;
  }

  public synchronized ManagedObjectFacade lookupFacade(ObjectID oid, int limit) throws NoSuchObjectException {
    IServerGroup group = groupForObjectID(oid);
    if (group != null) {
      IServer activeServer = group.getActiveServer();
      if (activeServer != null) { return activeServer.lookupFacade(oid, limit); }
    }
    return null;
  }

  public boolean isResidentOnClient(IClient client, ObjectID oid) {
    IServerGroup group = groupForObjectID(oid);
    if (group != null) {
      IServer activeServer = group.getActiveServer();
      if (activeServer != null) { return activeServer.isResidentOnClient(client, oid); }
    }
    return false;
  }

  public void addPolledAttributeListener(PollScope scope, String attribute, PolledAttributeListener listener) {
    addPolledAttributeListener(scope, Collections.singleton(attribute), listener);
  }

  public synchronized void addPolledAttributeListener(PollScope scope, Set<String> attributes,
                                                      PolledAttributeListener listener) {
    if (allScopedPollListeners != null) {
      allScopedPollListeners.clear();
      allScopedPollListeners = null;
    }

    Map<String, EventListenerList> scopeMap = pollScopes.get(scope);
    if (scopeMap == null) {
      pollScopes.put(scope, scopeMap = new HashMap<String, EventListenerList>());
    }
    Iterator<String> attrIter = attributes.iterator();
    while (attrIter.hasNext()) {
      String attr = attrIter.next();
      EventListenerList attrListenerList = scopeMap.get(attr);
      if (attrListenerList == null) {
        scopeMap.put(attr, attrListenerList = new EventListenerList());
      }
      attrListenerList.remove(PolledAttributeListener.class, listener);
      attrListenerList.add(PolledAttributeListener.class, listener);
    }
  }

  public synchronized Set<String> getPolledAttributes(PollScope scope) {
    Map<String, EventListenerList> scopeMap = pollScopes.get(scope);
    if (scopeMap != null) { return Collections.unmodifiableSet(scopeMap.keySet()); }
    return Collections.emptySet();
  }

  public void removePolledAttributeListener(PollScope scope, String attribute, PolledAttributeListener listener) {
    removePolledAttributeListener(scope, Collections.singleton(attribute), listener);
  }

  public synchronized void removePolledAttributeListener(PollScope scope, Set<String> attributes,
                                                         PolledAttributeListener listener) {
    if (allScopedPollListeners != null) {
      allScopedPollListeners.clear();
      allScopedPollListeners = null;
    }

    Map<String, EventListenerList> scopeMap = pollScopes.get(scope);
    if (scopeMap != null) {
      Iterator<String> attrIter = attributes.iterator();
      while (attrIter.hasNext()) {
        String attr = attrIter.next();
        EventListenerList attrListenerList = scopeMap.get(attr);
        if (attrListenerList != null) {
          attrListenerList.remove(PolledAttributeListener.class, listener);
          if (attrListenerList.getListenerCount() == 0) {
            scopeMap.remove(attr);
          }
        }
      }
    }
  }

  private synchronized Set<PolledAttributeListener> getAllScopedPollListeners() {
    if (allScopedPollListeners != null) { return allScopedPollListeners; }

    Set<PolledAttributeListener> result = new HashSet<PolledAttributeListener>();
    Iterator<PollScope> scopeIter = pollScopes.keySet().iterator();
    while (scopeIter.hasNext()) {
      PollScope scope = scopeIter.next();
      Map<String, EventListenerList> scopeMap = pollScopes.get(scope);
      if (scopeMap != null) {
        Iterator<EventListenerList> listenerListIter = scopeMap.values().iterator();
        while (listenerListIter.hasNext()) {
          Collections.addAll(result, listenerListIter.next().getListeners(PolledAttributeListener.class));
        }
      }
    }
    return allScopedPollListeners = result;
  }
}
