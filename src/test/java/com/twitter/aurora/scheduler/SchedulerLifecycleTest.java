/*
 * Copyright 2013 Twitter, Inc.
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
package com.twitter.aurora.scheduler;

import java.lang.Thread.UncaughtExceptionHandler;

import org.apache.mesos.Protos.Status;
import org.apache.mesos.SchedulerDriver;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.scheduler.SchedulerLifecycle.DelayedActions;
import com.twitter.aurora.scheduler.SchedulerLifecycle.DriverReference;
import com.twitter.aurora.scheduler.events.PubsubEvent.DriverRegistered;
import com.twitter.aurora.scheduler.storage.Storage.MutateWork.NoResult.Quiet;
import com.twitter.aurora.scheduler.storage.testing.StorageTestUtil;
import com.twitter.common.application.Lifecycle;
import com.twitter.common.base.Command;
import com.twitter.common.testing.easymock.EasyMockTest;
import com.twitter.common.util.Clock;
import com.twitter.common.zookeeper.SingletonService.LeaderControl;
import com.twitter.common.zookeeper.SingletonService.LeadershipListener;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.fail;

public class SchedulerLifecycleTest extends EasyMockTest {

  private static final String FRAMEWORK_ID = "framework id";

  private DriverFactory driverFactory;
  private StorageTestUtil storageUtil;
  private Command shutdownRegistry;
  private Driver driver;
  private DriverReference driverRef;
  private LeaderControl leaderControl;
  private SchedulerDriver schedulerDriver;
  private DelayedActions delayedActions;

  private SchedulerLifecycle schedulerLifecycle;

  @Before
  public void setUp() {
    driverFactory = createMock(DriverFactory.class);
    storageUtil = new StorageTestUtil(this);
    shutdownRegistry = createMock(Command.class);
    driver = createMock(Driver.class);
    driverRef = new DriverReference();
    leaderControl = createMock(LeaderControl.class);
    schedulerDriver = createMock(SchedulerDriver.class);
    delayedActions = createMock(DelayedActions.class);
    schedulerLifecycle = new SchedulerLifecycle(
        driverFactory,
        storageUtil.storage,
        new Lifecycle(shutdownRegistry, new UncaughtExceptionHandler() {
          @Override public void uncaughtException(Thread t, Throwable e) {
            fail(e.getMessage());
          }
        }),
        driver,
        driverRef,
        delayedActions,
        createMock(Clock.class));
  }

  @Test
  public void testAutoFailover() throws Throwable {
    // Test that when timed failover is initiated, cleanup is done in a way that should allow the
    // application to tear down cleanly.  Specifically, neglecting to call leaderControl.leave()
    // can result in a lame duck scheduler process.

    storageUtil.storage.prepare();

    storageUtil.storage.start(EasyMock.<Quiet>anyObject());
    storageUtil.expectOperations();
    expect(storageUtil.schedulerStore.fetchFrameworkId()).andReturn(FRAMEWORK_ID);
    expect(driverFactory.apply(FRAMEWORK_ID)).andReturn(schedulerDriver);
    Capture<Runnable> triggerFailoverCapture = createCapture();
    delayedActions.onAutoFailover(capture(triggerFailoverCapture));
    delayedActions.onRegistrationTimeout(EasyMock.<Runnable>anyObject());
    expect(driver.start()).andReturn(Status.DRIVER_RUNNING);
    delayedActions.blockingDriverJoin(EasyMock.<Runnable>anyObject());

    leaderControl.advertise();
    leaderControl.leave();
    driver.stop();
    storageUtil.storage.stop();
    shutdownRegistry.execute();

    control.replay();

    LeadershipListener leaderListener = schedulerLifecycle.prepare();
    leaderListener.onLeading(leaderControl);
    schedulerLifecycle.registered(new DriverRegistered());
    triggerFailoverCapture.getValue().run();
  }

  @Test
  public void testDefeatedBeforeRegistered() throws Throwable {
    storageUtil.storage.prepare();
    storageUtil.storage.start(EasyMock.<Quiet>anyObject());
    storageUtil.expectOperations();
    expect(storageUtil.schedulerStore.fetchFrameworkId()).andReturn(FRAMEWORK_ID);
    expect(driverFactory.apply(FRAMEWORK_ID)).andReturn(schedulerDriver);
    delayedActions.onAutoFailover(EasyMock.<Runnable>anyObject());
    delayedActions.onRegistrationTimeout(EasyMock.<Runnable>anyObject());
    expect(driver.start()).andReturn(Status.DRIVER_RUNNING);
    delayedActions.blockingDriverJoin(EasyMock.<Runnable>anyObject());

    // Important piece here is what's absent - leader presence is not advertised.
    leaderControl.leave();
    driver.stop();
    storageUtil.storage.stop();
    shutdownRegistry.execute();

    control.replay();

    LeadershipListener leaderListener = schedulerLifecycle.prepare();
    leaderListener.onLeading(leaderControl);
    leaderListener.onDefeated(null);
  }
}
