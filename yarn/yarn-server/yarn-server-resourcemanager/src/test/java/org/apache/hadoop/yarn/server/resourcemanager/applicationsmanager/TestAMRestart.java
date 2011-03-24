package org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.yarn.ApplicationID;
import org.apache.hadoop.yarn.ApplicationMaster;
import org.apache.hadoop.yarn.ApplicationState;
import org.apache.hadoop.yarn.ApplicationStatus;
import org.apache.hadoop.yarn.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.Container;
import org.apache.hadoop.yarn.ContainerID;
import org.apache.hadoop.yarn.ContainerToken;
import org.apache.hadoop.yarn.NodeID;
import org.apache.hadoop.yarn.Resource;
import org.apache.hadoop.yarn.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.security.ApplicationTokenSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager.ASMContext;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ASMEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.AMLauncherEventType;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.ApplicationEventType;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.ApplicationTrackerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.resourcetracker.NodeInfo;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeResponse;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.server.security.ContainerTokenSecretManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test to restart the AM on failure.
 *
 */
public class TestAMRestart extends TestCase {
  private static final Log LOG = LogFactory.getLog(TestAMRestart.class);
  ApplicationsManagerImpl appImpl;
  ASMContext asmContext = new ResourceManager.ASMContextImpl();
  ApplicationTokenSecretManager appTokenSecretManager = 
    new ApplicationTokenSecretManager();
  DummyResourceScheduler scheduler;
  int count = 0;
  ApplicationID appID;
  final int maxFailures = 3;
  AtomicInteger launchNotify = new AtomicInteger();
  AtomicInteger schedulerNotify = new AtomicInteger();
  volatile boolean stop = false;
  int schedulerAddApplication = 0;
  int schedulerRemoveApplication = 0;
  int launcherLaunchCalled = 0;
  int launcherCleanupCalled = 0;
  ApplicationMasterInfo masterInfo;
  
  private class ExtApplicationsManagerImpl extends ApplicationsManagerImpl {
    public ExtApplicationsManagerImpl(
        ApplicationTokenSecretManager applicationTokenSecretManager,
        YarnScheduler scheduler, ASMContext asmContext) {
      super(applicationTokenSecretManager, scheduler, asmContext);
    }

    @Override
    public EventHandler<ASMEvent<AMLauncherEventType>> createNewApplicationMasterLauncher(
        ApplicationTokenSecretManager tokenSecretManager) {
      return new DummyAMLauncher();
    }
  }

  private class DummyAMLauncher implements EventHandler<ASMEvent<AMLauncherEventType>> {

    public DummyAMLauncher() {
      asmContext.getDispatcher().register(AMLauncherEventType.class, this);
      new Thread() {
        public void run() {
          while (!stop) {
            LOG.info("DEBUG -- waiting for launch");
            synchronized(launchNotify) {
              while (launchNotify.get() == 0) {
                try { 
                  launchNotify.wait();
                } catch (InterruptedException e) {
                }
              }
              asmContext.getDispatcher().getEventHandler().handle(new 
                  ASMEvent<ApplicationEventType>(ApplicationEventType.LAUNCHED,
                      new TestAppContext(appID)));
              launchNotify.addAndGet(-1);
            }
          }
        }
      }.start();
    }

    @Override
    public void handle(ASMEvent<AMLauncherEventType> event) {
      switch (event.getType()) {
      case CLEANUP:
        launcherCleanupCalled++;
        break;
      case LAUNCH:
        LOG.info("DEBUG -- launching");
        launcherLaunchCalled++;
        synchronized (launchNotify) {
          launchNotify.addAndGet(1);
          launchNotify.notify();
        }
        break;
      default:
        break;
      }
    }
  }

  private class DummyResourceScheduler implements ResourceScheduler {
    @Override
    public NodeInfo addNode(NodeID nodeId, String hostName, Node node,
        Resource capability) {
      return null;
    }
    @Override
    public void removeNode(NodeInfo node) {
    }
    @Override
    public NodeResponse nodeUpdate(NodeInfo nodeInfo,
        Map<CharSequence, List<Container>> containers) {
      return null;
    }

    @Override
    public List<Container> allocate(ApplicationID applicationId,
        List<ResourceRequest> ask, List<Container> release) throws IOException {
      Container container = new Container();
      container.containerToken = new ContainerToken();
      container.hostName = "localhost";
      container.id = new ContainerID();
      container.id.appID = appID;
      container.id.id = count;
      count++;
      return Arrays.asList(container);
    }

    @Override
    public void handle(ASMEvent<ApplicationTrackerEventType> event) {
      switch (event.getType()) {
      case ADD:
        schedulerAddApplication++;
        break;
      case REMOVE:
        schedulerRemoveApplication++;
        LOG.info("REMOVING app : " + schedulerRemoveApplication);
        if (schedulerRemoveApplication == maxFailures) {
          synchronized (schedulerNotify) {
            schedulerNotify.addAndGet(1);
            schedulerNotify.notify();
          }
        }
        break;
      default:
        break;
      }
    }

    @Override
    public void reinitialize(Configuration conf,
        ContainerTokenSecretManager secretManager) {
    }
  }

  @Before
  public void setUp() {
    appID = new ApplicationID();
    appID.clusterTimeStamp = System.currentTimeMillis();
    appID.id = 1;
    scheduler = new DummyResourceScheduler();
    asmContext.getDispatcher().register(ApplicationTrackerEventType.class, scheduler);
    appImpl = new ExtApplicationsManagerImpl(appTokenSecretManager, scheduler, asmContext);
    Configuration conf = new Configuration();
    conf.setLong(YarnConfiguration.AM_EXPIRY_INTERVAL, 1000L);
    conf.setInt(YarnConfiguration.AM_MAX_RETRIES, maxFailures);
    appImpl.init(conf);
    appImpl.start();
  }

  @After
  public void tearDown() {
  }

  private void waitForFailed(ApplicationMasterInfo masterInfo, ApplicationState 
      finalState) throws Exception {
    int count = 0;
    while(masterInfo.getState() != finalState && count < 10) {
      Thread.sleep(500);
      count++;
    }
    assertTrue(masterInfo.getState() == finalState);
  }
  
  private class TestAppContext implements AppContext {
    private ApplicationID appID;
   
    public TestAppContext(ApplicationID appID) {
      this.appID = appID;
    }
    @Override
    public ApplicationSubmissionContext getSubmissionContext() {
      return null;
    }

    @Override
    public Resource getResource() {
      return null;
    }

    @Override
    public ApplicationID getApplicationID() {
      return appID;
    }

    @Override
    public ApplicationStatus getStatus() {
      return null;
    }

    @Override
    public ApplicationMaster getMaster() {
      return null;
    }

    @Override
    public Container getMasterContainer() {
      return null;
    }

    @Override
    public String getUser() {
      return null;
    }

    @Override
    public long getLastSeen() {
      return 0;
    }

    @Override
    public String getName() {
      return null;
    }

    @Override
    public String getQueue() {
      return null;
    }

    @Override
    public int getFailedCount() {
      return 0;
    }
    
  }

  @Test
  public void testAMRestart() throws Exception {
    ApplicationSubmissionContext subContext = new ApplicationSubmissionContext();
    subContext.applicationId = appID;
    subContext.applicationName = "dummyApp";
    subContext.command = new ArrayList<CharSequence>();
    subContext.environment = new HashMap<CharSequence, CharSequence>();
    subContext.fsTokens = new ArrayList<CharSequence>();
    subContext.fsTokens_todo = ByteBuffer.wrap(new byte[0]);
    appImpl.submitApplication(subContext);
    masterInfo = appImpl.getApplicationMasterInfo(appID);
    synchronized (schedulerNotify) {
      while(schedulerNotify.get() == 0) {
        schedulerNotify.wait();
      }
    }
    assertTrue(launcherCleanupCalled == maxFailures);
    assertTrue(launcherLaunchCalled == maxFailures);
    assertTrue(schedulerAddApplication == maxFailures);
    assertTrue(schedulerRemoveApplication == maxFailures);
    assertTrue(masterInfo.getFailedCount() == maxFailures);
    waitForFailed(masterInfo, ApplicationState.FAILED);
    stop = true;
  }
}