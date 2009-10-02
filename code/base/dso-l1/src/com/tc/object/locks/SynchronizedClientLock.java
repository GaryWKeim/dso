/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.object.locks;

import com.tc.exception.TCLockUpgradeNotSupportedError;
import com.tc.net.ClientID;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.lockmanager.api.WaitListener;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.util.SinglyLinkedList;
import com.tc.util.UnsafeUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.LockSupport;

public class SynchronizedClientLock extends SinglyLinkedList<State> implements ClientLock {
  private static final boolean DEBUG    = false;  
  private static final Timer   LOCK_TIMER = new Timer("ClientLock Greedy Lease Timer", true);
  
  private final LockID         lock;
  
  private ClientGreediness     greediness = ClientGreediness.FREE;
  private ServerLockLevel      recalledLevel;
  
  private volatile int         gcCycleCount = 0;

  public SynchronizedClientLock(LockID lock) {
    this.lock = lock;
  }
  
  /*
   * Try to acquire this lock locally - if successful then return, otherwise queue the request
   * and potentially call out to the server.
   */    
  public void lock(RemoteLockManager remote, ThreadID thread, LockLevel level) {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to " + level + " lock");
    if (!tryAcquireLocally(thread, level).succeeded()) {
      if (acquireQueued(remote, thread, level))
        Thread.currentThread().interrupt();
    }
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " locked " + level);
  }

  /*
   * Try to acquire this lock locally - if successful then return, otherwise queue the request
   * and potentially call out to the server
   */
  public void lockInterruptibly(RemoteLockManager remote, ThreadID thread, LockLevel level) throws InterruptedException {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to " + level + " lock interruptibly");
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    if (!tryAcquireLocally(thread, level).succeeded()) {
      acquireQueuedInterruptibly(remote, thread, level);
    }
  }

  /*
   * Try lock would normally just be:
   *   <code>return tryAcquire(remote, thread, level, 0).succeeeded();</code>
   * <p>
   * However because the existing contract on tryLock requires us to wait for the server
   * if the lock attempt is delegated things get significantly more complicated.
   */
  public boolean tryLock(RemoteLockManager remote, ThreadID thread, LockLevel level) {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to " + level + " try lock");

    // try to acquire locally
    AcquireResult local = tryAcquireLocally(thread, level);
    if (!local.delegated()) {
      // if local acquire did not require delegation to the server then return the result
      return local.succeeded();
    } else {
      //we need to talk to the server queue the request...
      QueuedLockAcquire node = new QueuedLockAcquire(thread, level, 0);
      try {
        synchronized (this) {
          addLast(node);
        }

        boolean delegate = true;
        // keep trying until we get a response from the server
        while (!node.serverResponded()) {
          synchronized (this) {
            // try acquire - with possible server communications if delegate is true 
            AcquireResult result = tryAcquire(delegate, remote, thread, level, 0);
            if (result.shared()) {
              unparkNextQueuedAcquire(node.getNext());
            }
            if (result.succeeded()) {
              // if acquire suceeded then return true
              return true;
            }
            if (result.delegated()) {
              // if acquire talked to the server - then set delegate to false to prevent duplicate server getting upset at duplicate messages
              delegate = false;
            }
          }
          // park this queued node and wait for any state changes that might allow us to make progress
          node.park();
          if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
          }
        }

        //finally heard from the server - this our last chance to succeed
        AcquireResult localResult = tryAcquireLocally(thread, level);
        if (localResult.shared()) {
          unparkNextQueuedAcquire(node.getNext());
        }
        return localResult.succeeded();
      } finally {
        synchronized (this) {
          remove(node);
        }
      }
    }
  }

  /*
   * Try to acquire locally - if we fail then queue the request and defer to the server.
   */
  public boolean tryLock(RemoteLockManager remote, ThreadID thread, LockLevel level, long timeout) throws InterruptedException {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to " + level + " try lock w/ timeout");
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    return tryAcquireLocally(thread, level).succeeded() || acquireQueuedTimeout(remote, thread, level, timeout);
  }

  /*
   * Release the lock and unpark an acquire if release tells us that queued acquires may now succeed.
   */
  public void unlock(RemoteLockManager remote, ThreadID thread, LockLevel level) {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to " + level + " unlock");
    if (tryRelease(remote, thread, level)) {
      unparkNextQueuedAcquire();
    }
  }

  /*
   * Find a lock waiter in the state and unpark it - while concurrently checking for a write hold by the notifying thread
   */
  public synchronized boolean notify(RemoteLockManager remote, ThreadID thread) {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " notifying a single lock waiter");
    if (greediness.equals(ClientGreediness.FREE)) {
      //other L1s may be waiting (let server decide who to notify)
      return true;
    } else {
      boolean lockHeld = false;
      for (State s : this) {
        if ((s instanceof LockHold) && s.getOwner().equals(thread) && ((LockHold) s).getLockLevel().isWrite()) {
          lockHeld = true;
        }
        if (s instanceof LockWaiter) {
          if (!lockHeld) {
            throw new IllegalMonitorStateException();
          }
          // move this waiters reacquire nodes into the queue - we must do this before returning to ensure transactional correctness on notifies.
          notify((LockWaiter) s);
          ((LockWaiter) s).unpark();
          return false;
        }
      }
      // no local waiters - defer to server
      // this seems wrong - we hold the greedy lock so no other nodes should have waiting threads - but i'm not going to question it for the moment...
      return true;
    }
  }

  /*
   * Find all the lock waiters in the state and unpark them.
   */
  public synchronized boolean notifyAll(RemoteLockManager remote, ThreadID thread) {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " notifying all lock waiters");
    if (greediness.equals(ClientGreediness.FREE)) {
      //other L1s may be waiting (let server decide who to notify)
      return true;
    } else {
      boolean lockHeld = false;
      for (State s : this) {
        if ((s instanceof LockHold) && s.getOwner().equals(thread) && ((LockHold) s).getLockLevel().isWrite()) {
          lockHeld = true;
        }
        if (s instanceof LockWaiter) {
          if (!lockHeld) {
            throw new IllegalMonitorStateException();
          }
          // move this waiters reacquire nodes into the queue - we must do this before returning to ensure transactional correctness on notifies.
          notify((LockWaiter) s);
          ((LockWaiter) s).unpark();
        }
      }
      return true;
    }    
  }

  public void wait(RemoteLockManager remote, WaitListener listener, ThreadID thread) throws InterruptedException {
    wait(remote, listener, thread, -1);
  }

  /*
   * Waiting involves unlocking all the write lock holds, sleeping on the original condition, until wake up, and
   * then re-acquiring the original locks in their original order.
   * 
   * This code is extraordinarily sensitive to the order of operations...
   */
  public void wait(RemoteLockManager remote, WaitListener listener, ThreadID thread, long timeout) throws InterruptedException {
    markUsed();
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " moving to wait with " + ((timeout < 0) ? "no timeout" : (timeout + " ms")));
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    LockWaiter node = null;
    // stack of reacquires that should locked when leaving the wait...
    Stack<QueuedLockAcquire> reacquireNodes = new Stack<QueuedLockAcquire>();
    try {      
      synchronized (this) {
        for (State s : this) {
          // find all this threads write holds...
          if ((s instanceof LockHold) && s.getOwner().equals(thread) && ((LockHold) s).getLockLevel().isWrite()) {
            LockHold hold = (LockHold) s;
            //if the unlocking of this hold triggers a flush requirement then do it now
            if (hold.getLockLevel().flushOnUnlock() || greediness.flushOnUnlock(this, hold)) {
              if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " flushing transactions on wait");
              doFlush(remote);
            }
            //remove the hold from the state and push into the reacquire stack
            remove(hold);
            reacquireNodes.push(new MonitorBasedQueuedLockAcquire(thread, hold.getLockLevel(), lock.javaObject(), -1));
          }
        }
        // if no write locks were held then throw here
        if (reacquireNodes.isEmpty()) { throw new IllegalMonitorStateException(); }

        // add the lock waiter node to the state (and attach the reacquire stack to it - the notifying thread will need the stack later)
        node = new LockWaiter(thread, lock.javaObject(), reacquireNodes, timeout);
        addLast(node);
        // potentially (dependent on greediness state) communicate with the server here
        greediness = greediness.waiting(remote, lock, this, thread, timeout);
        // kick the queue to allow queued acquires to grab the lock
        unparkNextQueuedAcquire();
      }
      
      //horrible listener call used by the test code
      listener.handleWaitEvent();
      
      try {
        if (timeout < 0) {
          node.park();
        } else {
          node.park(timeout);
        }
      } catch (InterruptedException e) {
        synchronized (this) {
          // potentially tell the server that the wait was interrupted - otherwise we may end up waiting for the locks to awarded by the server 
          greediness = greediness.interrupt(remote, lock, thread);
        }
      }
    } finally {
      synchronized (this) {
        // ensure that our reacquire nodes have been moved into the queue
        notify(node);
      }
      //reacquire the locks in their original order using the already in place queue nodes
      while (!reacquireNodes.isEmpty()) {
        QueuedLockAcquire qa = reacquireNodes.pop();
        if (acquireQueued(remote, thread, qa.getLockLevel(), qa, false))
          Thread.currentThread().interrupt();
      }
    }
  }

  public synchronized Collection<ClientServerExchangeLockContext> getStateSnapshot() {
    ClientID client = ManagerUtil.getClientID();
    Collection<ClientServerExchangeLockContext> contexts = new ArrayList<ClientServerExchangeLockContext>();

    switch (greediness) {
      case RECALLED_READ:
      case READ_RECALL_IN_PROGRESS:
      case GREEDY_READ:
        contexts.add(new ClientServerExchangeLockContext(lock, client, ThreadID.VM_ID, ServerLockContext.State.GREEDY_HOLDER_READ));
        break;
      case RECALLED_WRITE:        
      case WRITE_RECALL_IN_PROGRESS:
      case LEASED_GREEDY_WRITE:
      case GREEDY_WRITE:
        contexts.add(new ClientServerExchangeLockContext(lock, client, ThreadID.VM_ID, ServerLockContext.State.GREEDY_HOLDER_WRITE));
        break;
      case GARBAGE:
        return Collections.emptyList();
      case FREE:
        break;
    }

    for (State s : this) {
      if (s instanceof LockHold) {
        switch (((LockHold) s).getLockLevel()) {
          case READ:
            contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.HOLDER_READ));
            break;
          case WRITE:
          case SYNCHRONOUS_WRITE:
            contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.HOLDER_WRITE));
            break;
          default:
            throw new AssertionError();
        }
      } else if (s instanceof LockWaiter) {
        LockWaiter lw = (LockWaiter) s;
        contexts.add(new ClientServerExchangeLockContext(lock, client, lw.getOwner(), ServerLockContext.State.WAITER, lw.getTimeout()));
      } else if (s instanceof QueuedLockAcquire) {
        switch (((QueuedLockAcquire) s).getLockLevel()) {
          case READ:
            QueuedLockAcquire qla = (QueuedLockAcquire) s;
            if (qla.getTimeout() < 0) {
              contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.PENDING_READ));
            } else {
              contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.TRY_PENDING_READ, qla.getTimeout()));
            }
            break;
          case WRITE:
          case SYNCHRONOUS_WRITE:
            qla = (QueuedLockAcquire) s;
            if (qla.getTimeout() < 0) {
              contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.PENDING_WRITE));
            } else {
              contexts.add(new ClientServerExchangeLockContext(lock, client, s.getOwner(), ServerLockContext.State.TRY_PENDING_WRITE, qla.getTimeout()));
            }
            break;
          default:
            throw new AssertionError();
        }
      }
    }
    
    return contexts;
  }

  public synchronized int pendingCount() {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : getting pending count");
    int penders = 0;
    for (State s : this) {
      if (s instanceof QueuedLockAcquire) {
        penders++;
      }
    }
    return penders;
  }

  public synchronized int waitingCount() {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : getting waiting count");
    int waiters = 0;
    for (State s : this) {
      if (s instanceof LockWaiter) {
        waiters++;
      }
    }
    return waiters;
  }

  public synchronized boolean isLocked(LockLevel level) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : getting isLocked " + level);
    for (State s : this) {
      if ((s instanceof LockHold) && (((LockHold) s).level.equals(level))) {
        return true;
      } else if (s instanceof LockWaiter) {
        break;
      } else if (s instanceof QueuedLockAcquire) {
        break;
      }
    }
    return false;
  }

  public synchronized boolean isLockedBy(ThreadID thread, LockLevel level) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : getting isLocked " + level + " by " + thread);
    for (State s : this) {
      if ((s instanceof LockHold) && ((LockHold) s).getLockLevel().equals(level) && s.getOwner().equals(thread)) {
        return true;
      } else if (s instanceof LockWaiter) {
        break;
      } else if (s instanceof QueuedLockAcquire) {
        break;
      }
    }
    return false;
  }

  public synchronized int holdCount(LockLevel level) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : getting hold count @ " + level);
    int holders = 0;
    for (State s : this) {
      if ((s instanceof LockHold) && ((LockHold) s).level.equals(level)) {
        holders++;
      } else if (s instanceof LockWaiter) {
        break;
      } else if (s instanceof QueuedLockAcquire) {
        break;
      }
    }
    return holders;
  }

  /*
   * Called by the stage thread (the transaction apply thread) when the server wishes to notify a thread waiting on this lock
   */
  public void notified(ThreadID thread) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server notifying " + thread);
    LockWaiter waiter = null;
    synchronized (this) {
      for (State s : this) {
        if ((s instanceof LockWaiter) && s.getOwner().equals(thread)) {
          waiter = (LockWaiter) s;
          // move the waiting nodes reacquires into the queue in this thread so we can be certain that the lock state has changed by the time the server gets the txn ack.
          notify(waiter);
          break;
        }
      }
    }

    if (waiter != null) {
      waiter.unpark();
    }
  }

  /*
   * Move the given waiters reacquire nodes into the queue
   */
  private synchronized void notify(LockWaiter waiter) {
    if (remove(waiter) != null) {
      for (QueuedLockAcquire a : waiter.getReacquires()) {
        addLast(a);
      }
    }
  }
  
  /*
   * Called by the locking stage thread to request a greedy recall.
   */
  public synchronized void recall(final RemoteLockManager remote, final ServerLockLevel interest, int lease) {
    // transition the greediness state
    greediness = greediness.recall(null, interest, lease);
    recalledLevel = interest;

    if (greediness.isRecalled()) {
      if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server requested recall " + interest);
      greediness = doRecall(remote);
    } else if (greediness.equals(ClientGreediness.LEASED_GREEDY_WRITE)) {
      if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server granted leased " + interest);
      // schedule the greedy lease
      LOCK_TIMER.schedule(new TimerTask() {
        @Override
        public void run() {
          /*if (DEBUG) */System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : doing recall commit after lease expiry");
          synchronized (SynchronizedClientLock.this) {
            greediness = doRecall(remote);
          }
        }
      }, lease);
    }
  }

  /*
   * Called by the stage thread to indicate that the tryLock attempt has failed.
   */
  public void refuse(ThreadID thread, ServerLockLevel level) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server refusing lock request " + level);
    // kick the locking thread
    unparkQueuedAcquire(thread);
  }

  /*
   * Called by the stage thread when the server has awarded a lock (either greedy or per thread).
   */
  public synchronized void award(RemoteLockManager remote, ThreadID thread, ServerLockLevel level) {
    if (ThreadID.VM_ID.equals(thread)) {
      if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server awarded greedy " + level);
      greediness = greediness.award(level);
      unparkNextQueuedAcquire();
    } else {
      if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : server awarded per-thread " + level + " to " + thread);
      LockAward award = new LockAward(thread, level);
      addFirst(award);
      if (!unparkQueuedAcquire(thread)) {
        remove(award);
        remote.unlock(lock, thread, level);
      }
    }
  }

  /**
   * Our locks behave in a slightly bizarre way - we don't queue very strictly, if the head of the
   * acquire queue fails, we allow acquires further down to succeed.  This is different to the JDK
   * RRWL - suspect this is a historical accident.
   */
  static enum AcquireResult {
    /**
     * Acquire succeeded - other threads may succeed now too.
     */
    SUCCEEDED_SHARED,
    /**
     * Acquire succeeded - other threads will fail in acquire
     */
    SUCCEEDED,
    /**
     * Acquire was delegated to the server - used by tryLock.
     */
    DELEGATED,
    /**
     * Acquire was refused - other threads might succeed though.
     */
    FAILED;

    public boolean shared() {
      // because of our loose queuing everything except a exclusive acquire is `shared'
      return this != SUCCEEDED;
    }

    public boolean succeeded() {
      return (this == SUCCEEDED) | (this == SUCCEEDED_SHARED);
    }
    
    public boolean delegated() {
      return this == DELEGATED;
    }
  }

  /*
   * Try to acquire the lock (optionally with delegation to the server)
   */
  private AcquireResult tryAcquire(boolean delegate, RemoteLockManager remote, ThreadID thread, LockLevel level, long timeout) {
    if (delegate) {
      return tryAcquireWithDelegation(remote, thread, level, timeout);
    } else {
      return tryAcquireLocally(thread, level);
    }
  }
  
  /*
   * Attempt to acquire the lock at the given level locally
   */
  private AcquireResult tryAcquireLocally(ThreadID thread, LockLevel level) {
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " attempting to acquire " + level);
    // if this is a concurrent acquire then just let it through.
    if (level == LockLevel.CONCURRENT) {
      return AcquireResult.SUCCEEDED_SHARED;
    }
    
    synchronized (this) {
      //What can we glean from local lock state
      LockHold newHold = new LockHold(thread, level);
      LockLevel holdLevel = null;
      for (Iterator<State> it = iterator(); it.hasNext();) {
        State s = it.next();
        if (s instanceof LockHold) {
          LockHold hold = (LockHold) s;
          if (hold.getOwner().equals(thread)) {
            if (level.isRead()) {
              if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " awarded " + level + " due to existing thread hold");
              addFirst(newHold);
              return hold.getLockLevel().isWrite() ? AcquireResult.SUCCEEDED : AcquireResult.SUCCEEDED_SHARED;
            }
            if (hold.getLockLevel().isWrite()) {
              if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " awarded " + level + " due to existing WRITE hold");
              addFirst(newHold);
              return AcquireResult.SUCCEEDED;
            } else {
              holdLevel = hold.getLockLevel();
            }
          } else {
            if (hold.getLockLevel().isWrite()) {
              if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " denied " + level + " due to other thread holding WRITE");
              return AcquireResult.FAILED;
            }
          }
        } else if (s instanceof LockAward) {
          LockAward award = (LockAward) s;
          if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " found per thread award for " + award.getOwner() + " @ " + award.getLockLevel());
          if (award.getOwner().equals(thread)) {
            switch (level) {
              case READ:
                if (award.getLockLevel().equals(ServerLockLevel.READ)) {
                  it.remove();
                  if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " awarded " + level + " due to per thread award");
                  addFirst(newHold);
                  return AcquireResult.SUCCEEDED_SHARED;
                }
                break;
              case SYNCHRONOUS_WRITE:
              case WRITE:
                if (award.getLockLevel().equals(ServerLockLevel.WRITE)) {
                  it.remove();
                  if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " awarded " + level + " due to per thread award");
                  addFirst(newHold);
                  return AcquireResult.SUCCEEDED;
                }
                break;
              default:
                throw new AssertionError();
            }
          }
        }
      }
  
      //Lock upgrade not supported check
      if (level.isWrite() && (holdLevel != null) && holdLevel.isRead()) {
        throw new TCLockUpgradeNotSupportedError();
      }
      
      //Thread level lock state did not give us a definitive answer
      if (greediness.canAward(level)) {
        if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " awarded " + level + " due to client greedy hold");
        addFirst(newHold);
        return level.isWrite() ? AcquireResult.SUCCEEDED : AcquireResult.SUCCEEDED_SHARED;
      } else {
        return AcquireResult.DELEGATED;
      }
    }
  }
  
  /*
   * Try to acquire the lock and delegate to the server if necessary
   */
  private synchronized AcquireResult tryAcquireWithDelegation(RemoteLockManager remote, ThreadID thread, LockLevel level, long timeout) {
    // try to do things locally first...
    AcquireResult local = tryAcquireLocally(thread, level);
    if (local.delegated()) {
      // if local calls for delegation then fire into the server.
      if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " denied " + level + " - contacting server...");
      greediness = greediness.requestLevel(remote, lock, thread, level, timeout);
      return AcquireResult.DELEGATED;
    } else {
      return local;
    }
  }
  
  /*
   * Unlock and return true if acquires might now succeed.
   */
  private boolean tryRelease(RemoteLockManager remote, ThreadID thread, LockLevel level) {
    // concurrent unlocks are implicitly okay - we don't monitor concurrent locks
    if (level == LockLevel.CONCURRENT) {
      return false;
    }
    
    LockHold unlocked = null;
    synchronized (this) {
      for (Iterator<State> it = iterator(); it.hasNext();) {
        State s = it.next();
        if (s instanceof LockHold) {
          LockHold hold = (LockHold) s;
          if (hold.getOwner().equals(thread) && (hold.getLockLevel().equals(level) || (level == null))) {
            unlocked = hold;
            break;
          }
        }
      }
      
      if (unlocked == null) {
        if (level == null) {
          return false; //tolerate this for the moment... (this covers concurrent locks - when we aren't told what we are unlocking)
        } else {
          throw new IllegalMonitorStateException();
        }
      }
    }

    if (unlocked.getLockLevel().flushOnUnlock() || greediness.flushOnUnlock(this, unlocked)) {
      remote.flush(lock);
    }
    
    synchronized (this) {
      if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " unlocking " + level);
      remove(unlocked);
      greediness = greediness.unlocked(remote, lock, this, unlocked);

      if (DEBUG) System.err.println("\t" + ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : " + thread + " unlocked " + level);
      // this is wrong - but shouldn't break anything
      return true;
    }
  }

  /*
   * Conventional acquire queued - uses a LockSupport based queue object.
   */
  private boolean acquireQueued(RemoteLockManager remote, ThreadID thread, LockLevel level) {
    final QueuedLockAcquire node = new QueuedLockAcquire(thread, level, -1);
    synchronized (this) {
      addLast(node);
    }
    return acquireQueued(remote, thread, level, node, true);
  }

  /*
   * Generic acquire - uses an already existing queued node - used during wait notify
   */
  private boolean acquireQueued(RemoteLockManager remote, ThreadID thread, LockLevel level, QueuedLockAcquire node, boolean delegate) {    
    try {
      boolean interrupted = false;
      for (;;) {
        synchronized (this) {
          // try to acquire before sleeping
          AcquireResult result = tryAcquire(delegate, remote, thread, level, -1L);
          if (result.delegated()) {
            // we contacted server - disable delegation to prevent multiple messages
            delegate = false;
          }
          if (result.shared()) {
            unparkNextQueuedAcquire(node.getNext());
          }
          if (result.succeeded()) {
            // we succeeded return interrupted state
            remove(node);
            return interrupted;
          }
        }
        // park the thread and wait for unpark
        node.park();
        if (Thread.interrupted()) {
          interrupted = true;
        }
      }
    } catch (RuntimeException ex) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw ex;
    } catch (TCLockUpgradeNotSupportedError e) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw e;
    }
  }

  /*
   * Just like acquireQueued but throws InterruptedException if unparked by interrupt rather then saving the interrupt state
   */
  private void acquireQueuedInterruptibly(RemoteLockManager remote, ThreadID thread, LockLevel level) throws InterruptedException {
    final QueuedLockAcquire node = new QueuedLockAcquire(thread, level, -1);
    synchronized (this) {
      addLast(node);
    }
    try {
      boolean delegate = true;
      for (;;) {
        synchronized (this) {
          AcquireResult result = tryAcquire(delegate, remote, thread, level, -1);
          if (result.delegated()) {
            delegate = false;
          }
          if (result.shared()) {
            unparkNextQueuedAcquire(node.getNext());
          }
          if (result.succeeded()) {
            remove(node);
            return;
          }
        }
        node.park();
        if (Thread.interrupted()) {
          break;
        }
      }
    } catch (RuntimeException ex) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw ex;
    } catch (TCLockUpgradeNotSupportedError e) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw e;
    }
    // Arrive here only if interrupted
    synchronized (this) {
      remove(node);
    }
    throw new InterruptedException();
  }

  /*
   * Acquire queued - waiting for at most timeout milliseconds.
   */
  private boolean acquireQueuedTimeout(RemoteLockManager remote, ThreadID thread, LockLevel level, long timeout) throws InterruptedException {
    long lastTime = System.currentTimeMillis();
    final QueuedLockAcquire node = new QueuedLockAcquire(thread, level, timeout);
    synchronized (this) {
      addLast(node);
    }
    try {
      boolean delegate = true;
      for (;;) {
        synchronized (this) {
          AcquireResult result = tryAcquire(delegate, remote, thread, level, timeout);
          if (result.delegated()) {
            delegate = false;
          }
          if (result.shared()) {
            unparkNextQueuedAcquire(node.getNext());
          }
          if (result.succeeded()) {
            remove(node);
            return true;
          } else {
            if (timeout <= 0) {
              remove(node);
              return false;
            }
          }
        }
        node.park(timeout);
        if (Thread.interrupted()) break;
        long now = System.currentTimeMillis();
        timeout -= now - lastTime;
        lastTime = now;
      }
    } catch (RuntimeException ex) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw ex;
    } catch (TCLockUpgradeNotSupportedError e) {
      synchronized (this) {
        remove(node);
        unparkNextQueuedAcquire();
      }
      throw e;
    }
    // Arrive here only if interrupted
    synchronized (this) {
      remove(node);
    }
    throw new InterruptedException();
  }

  /*
   * Unpark the first queued acquire
   */
  private synchronized void unparkNextQueuedAcquire() {
    if (!isEmpty()) {
      unparkNextQueuedAcquire(getFirst());
    }
  }

  /*
   * Unpark the next queued acquire (after supplied node)
   */
  private synchronized void unparkNextQueuedAcquire(State node) {
    while (node != null) {
      if (node instanceof QueuedLockAcquire) {
        ((QueuedLockAcquire) node).acked();
        ((QueuedLockAcquire) node).unpark();
        if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : unparked " + node.getOwner() + " wanting " + ((QueuedLockAcquire) node).getLockLevel());
        return;
      }
      node = node.getNext();
    }
  }

  /*
   * Unpark the queued acquire associated with the given thread - used by per-thread awards
   */
  private synchronized boolean unparkQueuedAcquire(ThreadID thread) {
    for (State s : this) {
      if ((s instanceof QueuedLockAcquire) && s.getOwner().equals(thread)) {
        QueuedLockAcquire acquire = (QueuedLockAcquire) s;
        acquire.acked();
        acquire.unpark();
        if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : unparked " + thread + " wanting " + ((QueuedLockAcquire) s).getLockLevel());
        return true;
      }
    }
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : failed to unpark " + thread);
    return false;
  }

  protected synchronized ClientGreediness doRecall(final RemoteLockManager remote) {
    if (canRecallNow(recalledLevel)) {
      doFlush(remote);
      if (remote.isTransactionsForLockFlushed(lock, new LockFlushCallback() {
        public void transactionsForLockFlushed(LockID id) {
          synchronized (SynchronizedClientLock.this) {
            if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : doing recall commit (having flushed transactions)");
            greediness = recallCommit(remote);
          }
        }
      })) {
        if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : doing recall commit " + greediness);
        return recallCommit(remote);
      }
      return greediness.recallInProgress();
    } else {
      if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : cannot recall right now");
      return this.greediness;
    }
  }

  private synchronized ClientGreediness recallCommit(RemoteLockManager remote) {
    if (ClientGreediness.FREE.equals(greediness)) {
      return greediness;
    }
    
    Collection<ClientServerExchangeLockContext> contexts = getFilteredStateSnapshot(false);
    if (DEBUG) {
      synchronized (System.err) {
        System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : recalling :");
        for (ClientServerExchangeLockContext c : contexts) {
          System.err.println("\t" + c);
        }
      }
    }
    remote.recallCommit(lock, contexts);
    if (DEBUG) System.err.println(ManagerUtil.getClientID() + " : " + lock + "[" + System.identityHashCode(this) + "] : free'd greedy lock");
    
    return ClientGreediness.FREE;
  }
  
  private synchronized boolean canRecallNow(ServerLockLevel level) {
    for (State s : this) {
      if (s instanceof LockHold) {
        switch (level) {
          case WRITE: //any hold will block a recall for write
            return false;
          case READ: //a write hold will block a read recall
            if (((LockHold) s).getLockLevel().isWrite()) return false;
            break;
          case NONE:
            throw new AssertionError();
        }
      }
    }
    return true;
  }

  //bleagh this is horrible - actively looking at this
  protected void doFlush(RemoteLockManager remote) {
    UnsafeUtil.monitorExit(this);
    try {
      remote.flush(lock);
    } finally {
      UnsafeUtil.monitorEnter(this);
    }
  }
  
  static class LockHold extends State {
    private final LockLevel level;
    
    LockHold(ThreadID owner, LockLevel level) {
      super(owner);
      this.level = level;
    }
    
    LockLevel getLockLevel() {
      return level;
    }
    
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o instanceof LockHold) {
        return super.equals(o) && level.equals(((LockHold) o).level);
      } else {
        return false;
      }
    }
    
    public String toString() {
      return super.toString() + " : " + level;
    }
  }
  
  static class QueuedLockAcquire extends State {
    private final LockLevel  level;
    private final Thread     javaThread;
    private final long       waitTime;
    private volatile boolean serverResponded = false;
    
    QueuedLockAcquire(ThreadID owner, LockLevel level, long timeout) {
      super(owner);
      this.javaThread = Thread.currentThread();
      this.level = level;
      this.waitTime = timeout;
    }
    
    LockLevel getLockLevel() {
      return level;
    }
    
    Thread getJavaThread() {
      return javaThread;
    }
    
    long getTimeout() {
      return waitTime;
    }
    
    void park() {
      LockSupport.park();
    }
    
    void park(long timeout) {
      LockSupport.parkNanos(timeout * 1000000L);
    }
    
    void unpark() {
      LockSupport.unpark(javaThread);
    }

    boolean serverResponded() {
      return serverResponded;
    }
    
    void acked() {
      serverResponded = true;
    }
    
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o instanceof QueuedLockAcquire) {
        return super.equals(o) && level.equals(((QueuedLockAcquire) o).level);
      } else {
        return false;
      }
    }
    
    
    public String toString() {
      return super.toString() + " : " + level;
    }
  }
  
  static class MonitorBasedQueuedLockAcquire extends QueuedLockAcquire {

    private final Object javaObject;
    private boolean      parked = false;
    
    MonitorBasedQueuedLockAcquire(ThreadID owner, LockLevel level, Object javaObject, long timeout) {
      super(owner, level, timeout);
      this.javaObject = javaObject;
    }
    
    void park() {
      synchronized (javaObject) {
        parked = true;
        while (parked) {
          try {
            javaObject.wait();
          } catch (InterruptedException e) {
            //
          }
        }
        Thread.interrupted();
        parked = false;
      }
    }

    /**
     * MonitorBasedQueuedLockAcquires are only used to reacquire locks after waiting
     *  - they should always park indefinitely
     */
    void park(long timeout) {
      throw new AssertionError();
    }
    
    void unpark() {
      if (parked) {
        parked = false;
        getJavaThread().interrupt();
      }
    }
  }
  
  static class LockWaiter extends State {
    
    private final Object waitObject;
    private final long   waitTime;
    private final Stack<QueuedLockAcquire> reacquires;
    
    private boolean      notified;
    
    LockWaiter(ThreadID owner, Object waitObject, Stack<QueuedLockAcquire> reacquires, long timeout) {
      super(owner);
      this.waitObject = waitObject;
      this.reacquires = reacquires;
      this.waitTime = timeout;
    }
    
    long getTimeout() {
      return waitTime;
    }

    Stack<QueuedLockAcquire> getReacquires() {
      return reacquires;
    }
    
    void park() throws InterruptedException {
      synchronized (waitObject) {
        while (!notified) {
          waitObject.wait();
        }
      }
    }

    void park(long timeout) throws InterruptedException {
      synchronized (waitObject) {
        waitObject.wait(timeout);
      }
    }
    
    void unpark() {
      synchronized (waitObject) {
        notified = true;
        waitObject.notifyAll();
      }
    }
    
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o instanceof LockWaiter) {
        return super.equals(o);
      } else {
        return false;
      }
    }
  }
  
  static class LockAward extends State {
    private final ServerLockLevel level;
    
    LockAward(ThreadID target, ServerLockLevel level) {
      super(target);
      this.level = level;
    }
    
    ServerLockLevel getLockLevel() {
      return level;
    }
    
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o instanceof LockAward) {
        return super.equals(o) && level.equals(((LockAward) o).level);
      } else {
        return false;
      }
    }
    
    public String toString() {
      return super.toString() + " : " + level;
    }
  }

  public synchronized boolean garbageCollect() {
    if (isEmpty() && gcCycleCount > 0) {
      greediness = ClientGreediness.GARBAGE;
      return true;
    } else {
      gcCycleCount++;
      return false;
    }
  }  
  
  private void markUsed() {
    gcCycleCount = 0;
  }

  public synchronized void initializeHandshake(ClientHandshakeMessage message) {
    Collection<ClientServerExchangeLockContext> contexts = getFilteredStateSnapshot(true);

    for (ClientServerExchangeLockContext c : contexts) {
      if (DEBUG) System.err.println("Handshaking : " + ManagerUtil.getClientID() + " : " + c);
      message.addLockContext(c);
    }
  }
  
  private synchronized Collection<ClientServerExchangeLockContext> getFilteredStateSnapshot(boolean greedy) {
    Collection<ClientServerExchangeLockContext> fullState = getStateSnapshot();
    Collection<ClientServerExchangeLockContext> legacyState = new ArrayList();
    Map<ThreadID, ClientServerExchangeLockContext> holds = new HashMap<ThreadID, ClientServerExchangeLockContext>();

    for (ClientServerExchangeLockContext context : fullState) {
      switch (context.getState()) {
        case HOLDER_READ:
        case HOLDER_WRITE:
          ClientServerExchangeLockContext current = holds.get(context.getThreadID());
          if (current == null) {
            holds.put(context.getThreadID(), context);
          } else {
            if (context.getState().getLockLevel().equals(ServerLockLevel.WRITE)) {
              holds.put(context.getThreadID(), context);
            }
          }
          break;
        case PENDING_READ:
        case PENDING_WRITE:
        case TRY_PENDING_READ:
        case TRY_PENDING_WRITE:
        case WAITER:
          legacyState.add(context);
          break;
        case GREEDY_HOLDER_READ:
        case GREEDY_HOLDER_WRITE:
          if (greedy) {
            return Collections.singletonList(context);
          }
          break;
      }
    }    
    legacyState.addAll(holds.values());
    
    return legacyState;
  }
  
  public synchronized String toString() {
    StringBuilder sb = new StringBuilder();
    
    sb.append("SynchronizedClientLock : ").append(lock).append('\n');
    sb.append("Greediness : ").append(greediness).append('\n');
    sb.append("State:").append('\n');
    for (State s : this) {
      sb.append('\t').append(s).append('\n');
    }
    
    return sb.toString();
  }
}

abstract class State implements SinglyLinkedList.LinkedNode<State> {

  private final ThreadID owner;
  
  private State next;

  State(ThreadID owner) {
    this.owner = owner;
    this.next = null;
  }
  
  void park() throws InterruptedException {
    throw new AssertionError();
  }

  void park(long timeout) throws InterruptedException {
    throw new AssertionError();
  }

  void unpark() {
    throw new AssertionError();
  }
  
  ThreadID getOwner() {
    return owner;
  }
  
  public State getNext() {
    return next;
  }

  public State setNext(State newNext) {
    State old = next;
    next = newNext;
    return old;
  }
  
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (o instanceof State) {
      return (owner.equals(((State) o).owner));
    } else {
      return false;
    }
  }
  
  public String toString() {
    return getClass().getSimpleName() + " : " + owner;
  }
}
