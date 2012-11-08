/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.express.tests.nonstop;

import org.terracotta.express.tests.base.AbstractToolkitTestBase;
import org.terracotta.express.tests.base.NonStopClientBase;
import org.terracotta.toolkit.Toolkit;
import org.terracotta.toolkit.cache.ToolkitCache;
import org.terracotta.toolkit.cache.ToolkitCacheConfigBuilder;
import org.terracotta.toolkit.concurrent.ToolkitBarrier;
import org.terracotta.toolkit.nonstop.NonStopConfigBuilder;
import org.terracotta.toolkit.nonstop.NonStopConfigFields.NonStopTimeoutBehavior;
import org.terracotta.toolkit.nonstop.NonStopException;
import org.terracotta.toolkit.store.ToolkitStoreConfigFields.Consistency;

import com.tc.test.config.model.TestConfig;

import java.util.Date;

import junit.framework.Assert;

public class NonStopLocalReadTest extends AbstractToolkitTestBase {

  public NonStopLocalReadTest(TestConfig testConfig) {
    super(testConfig, NonStopLocalReadTestClient.class, NonStopLocalReadTestClient.class);
    testConfig.getClientConfig().setParallelClients(true);
  }

  public static class NonStopLocalReadTestClient extends NonStopClientBase {
    private static final int  CLIENT_COUNT            = 2;
    private static final int  NUMBER_OF_ELEMENTS      = 10;
    private static final int  MAX_ENTRIES_LOCAL_HEAP  = 0;
    private static final long NON_STOP_TIMEOUT_MILLIS = 10000;

    public NonStopLocalReadTestClient(String[] args) {
      super(args);
    }

    public static void main(String[] args) {
      new NonStopLocalReadTestClient(args).run();
    }

    @Override
    protected void test(Toolkit toolkit) throws Throwable {
      ToolkitBarrier barrier = toolkit.getBarrier("testBarrier", CLIENT_COUNT);
      int index = barrier.await();
      ToolkitCache cache = null;
      cache = createCache(toolkit);

      if (index == 0) {
        for (int i = 0; i < NUMBER_OF_ELEMENTS; i++) {
          cache.put(i, i);
        }
        System.err.println("Cache size " + cache.size() + " at " + new Date());
      }

      barrier.await();

      if (index == 1) {
        for (int i = 0; i < NUMBER_OF_ELEMENTS; i++) {
          long time = System.currentTimeMillis();
          try {
            Assert.assertNotNull(cache.get(i));
          } catch (NonStopException e) {
            System.err.println("Time elapsed " + (System.currentTimeMillis() - time) + " , i=" + i);
            throw e;
          }
        }

        makeServerDie();

        Thread.sleep(10000);

        for (int i = 0; i < NUMBER_OF_ELEMENTS; i++) {
          long time = System.currentTimeMillis();

          // Since get in strong consistency requires to take lock, hence local reads should happen
          // Local Cache should have all the elements faulted in
          Assert.assertEquals(i, cache.get(i));

          time = System.currentTimeMillis() - time;
          System.err.println("Time consumed " + time);
          Assert.assertTrue((time > (NON_STOP_TIMEOUT_MILLIS - 500)) && (time < (NON_STOP_TIMEOUT_MILLIS + 2000)));
        }

        restartCrashedServer();
      }
    }

    private void restartCrashedServer() throws Exception {
      getTestControlMbean().reastartLastCrashedServer(0);
    }

    private void makeServerDie() throws Exception {
      getTestControlMbean().crashActiveServer(0);
    }

    private ToolkitCache createCache(Toolkit toolkit) {
      String cacheName = "test-cache";

      new NonStopConfigBuilder().timeoutMillis(NON_STOP_TIMEOUT_MILLIS)
          .nonStopTimeoutBehavior(NonStopTimeoutBehavior.EXCEPTION_ON_MUTATE_AND_LOCAL_READS).apply(toolkit);

      ToolkitCacheConfigBuilder builder = new ToolkitCacheConfigBuilder();
      builder.maxCountLocalHeap(MAX_ENTRIES_LOCAL_HEAP).consistency(Consistency.STRONG);

      return toolkit.getCache(cacheName, builder.build(), Integer.class);
    }
  }

}