/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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
package com.tc.server.util;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.SessionIdManager;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.HouseKeeper;

import java.util.EventListener;

/**
 * Delegates to Jetty's HashSessionIdManager but initializes it lazily
 * with a background thread.
 */
public class TcHashSessionIdManager implements SessionIdManager {

  private final DefaultSessionIdManager delegate;

  private final Server server;

  public TcHashSessionIdManager(Server server) {
    this.server = server;

    this.delegate = new DefaultSessionIdManager(this.server);
    this.delegate.setWorkerName("tcSessions");
  }

  @Override
  public String getId(String nodeId) {
    return getDelegate().getId(nodeId);
  }

  @Override
  public String getExtendedId(String s, Request request) {
    return getDelegate().getExtendedId(s, request);
  }

  @Override
  public void scavenge() {

  }

  @Override
  public String getWorkerName() {
    return getDelegate().getWorkerName();
  }


  @Override
  public String renewSessionId(String oldId, String oldExtendedId, Request request) {
    return getDelegate().renewSessionId(oldId, oldExtendedId, request);
  }

//  @Override
//  public Set<SessionHandler> getSessionHandlers() {
//    return getDelegate().getSessionHandlers();
//  }

  @Override
  public void setSessionHouseKeeper(HouseKeeper houseKeeper) {
    getDelegate().setSessionHouseKeeper(houseKeeper);
  }

  @Override
  public HouseKeeper getSessionHouseKeeper() {
    return getDelegate().getSessionHouseKeeper();
  }

  @Override
  public boolean isIdInUse(String id) {
    return getDelegate().isIdInUse(id);
  }


  @Override
  public void expireAll(String id) {
    getDelegate().expireAll(id);
  }

  @Override
  public void invalidateAll(String id) {
    getDelegate().invalidateAll(id);
  }

  @Override
  public String newSessionId(Request request, String s, long l) {
    return "";
  }

  @Override
  public boolean isFailed() {
    return getDelegate().isFailed();
  }

  @Override
  public boolean addEventListener(EventListener eventListener) {
    return getDelegate().addEventListener(eventListener);
  }

  @Override
  public boolean removeEventListener(EventListener eventListener) {
    return getDelegate().removeEventListener(eventListener);
  }

  @Override
  public final void start() throws Exception {
    getDelegate().start();
  }

  @Override
  public final void stop() throws Exception {
    getDelegate().stop();
  }

  @Override
  public boolean isRunning() {
    return getDelegate().isRunning();
  }

  @Override
  public boolean isStarted() {
    return getDelegate().isStarted();
  }

  @Override
  public boolean isStarting() {
    return getDelegate().isStarting();
  }

  @Override
  public boolean isStopping() {
    return getDelegate().isStopping();
  }

  @Override
  public boolean isStopped() {
    return getDelegate().isStopped();
  }

  private DefaultSessionIdManager getDelegate() {
    return delegate;
  }
}
