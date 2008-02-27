/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.dso;

import org.dijon.Button;
import org.dijon.CheckBox;
import org.dijon.ContainerResource;
import org.dijon.List;
import org.dijon.ScrollPane;
import org.dijon.SplitPane;
import org.dijon.TabbedPane;
import org.dijon.TextArea;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.time.Second;
import org.jfree.data.time.TimeSeries;

import com.tc.admin.AdminClient;
import com.tc.admin.AdminClientContext;
import com.tc.admin.ThreadDumpEntry;
import com.tc.admin.common.DemoChartFactory;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XLabel;
import com.tc.management.beans.l1.L1InfoMBean;
import com.tc.management.beans.logging.InstrumentationLoggingMBean;
import com.tc.management.beans.logging.RuntimeLoggingMBean;
import com.tc.management.beans.logging.RuntimeOutputOptionsMBean;
import com.tc.statistics.StatisticData;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class ClientPanel extends XContainer implements NotificationListener {
  private DSOClient                 m_client;
  
  private TabbedPane                m_tabbedPane;
  private XLabel                    m_detailsLabel;
  private Button                    m_smiteButton;

  private TextArea                  m_environmentTextArea;
  private TextArea                  m_configTextArea;

  private Button                    m_threadDumpButton;
  private SplitPane                 m_threadDumpsSplitter;
  private Integer                   m_dividerLoc;
  private DividerListener           m_dividerListener;
  private List                      m_threadDumpList;
  private DefaultListModel          m_threadDumpListModel;
  private TextArea                  m_threadDumpTextArea;
  private ScrollPane                m_threadDumpTextScroller;
  private ThreadDumpEntry           m_lastSelectedEntry;

  private CheckBox                  m_classCheckBox;
  private CheckBox                  m_locksCheckBox;
  private CheckBox                  m_transientRootCheckBox;
  private CheckBox                  m_rootsCheckBox;
  private CheckBox                  m_distributedMethodsCheckBox;

  private CheckBox                  m_nonPortableDumpCheckBox;
  private CheckBox                  m_lockDebugCheckBox;
  private CheckBox                  m_fieldChangeDebugCheckBox;
  private CheckBox                  m_waitNotifyDebugCheckBox;
  private CheckBox                  m_distributedMethodDebugCheckBox;
  private CheckBox                  m_newObjectDebugCheckBox;

  private CheckBox                  m_autoLockDetailsCheckBox;
  private CheckBox                  m_callerCheckBox;
  private CheckBox                  m_fullStackCheckBox;

  private ActionListener            m_loggingChangeHandler;
  private HashMap<String, CheckBox> m_loggingControlMap;

  private TimeSeries[]              m_memoryTimeSeries;
  private Container                 m_cpuPanel;
  private TimeSeries[]              m_cpuTimeSeries;
  private Map<String, TimeSeries>   m_cpuTimeSeriesMap;

  public ClientPanel(DSOClient client) {
    super();

    AdminClientContext acc = AdminClient.getContext();

    load((ContainerResource) acc.topRes.getComponent("DSOClientPanel"));

    m_tabbedPane = (TabbedPane) findComponent("TabbedPane");
    
    m_detailsLabel = (XLabel) findComponent("DetailsLabel");
    m_smiteButton = (Button) findComponent("SmiteClientButton");
    m_smiteButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        SwingUtilities.invokeLater(new Runnable() {
          public void run() {
            String msg = MessageFormat.format("Are you sure you want to terminate {0}?", m_client);
            int answer = JOptionPane.showConfirmDialog(m_smiteButton, msg, "Terracotta AdminConsole",
                                                       JOptionPane.YES_NO_OPTION);
            if (answer == JOptionPane.YES_OPTION) {
              m_client.killClient();
            }
          }
        });
      }
    });

    m_environmentTextArea = (TextArea) findComponent("EnvironmentTextArea");
    m_configTextArea = (TextArea) findComponent("ConfigTextArea");

    m_threadDumpButton = (Button) findComponent("TakeThreadDumpButton");
    m_threadDumpButton.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent ae) {
        long requestMillis = System.currentTimeMillis();
        try {
          ThreadDumpEntry tde = new ThreadDumpEntry(m_client.getL1InfoMBean().takeThreadDump(requestMillis));
          m_threadDumpListModel.addElement(tde);
          m_threadDumpList.setSelectedIndex(m_threadDumpListModel.getSize() - 1);
        } catch (Exception e) {
          AdminClient.getContext().log(e);
        }
      }
    });

    m_threadDumpsSplitter = (SplitPane) findComponent("ClientThreadDumpsSplitter");
    m_dividerLoc = new Integer(getThreadDumpSplitPref());
    m_dividerListener = new DividerListener();

    m_threadDumpList = (List) findComponent("ThreadDumpList");
    m_threadDumpList.setModel(m_threadDumpListModel = new DefaultListModel());
    m_threadDumpList.addListSelectionListener(new ListSelectionListener() {
      public void valueChanged(ListSelectionEvent lse) {
        if (lse.getValueIsAdjusting()) return;
        if (m_lastSelectedEntry != null) {
          m_lastSelectedEntry.setViewPosition(m_threadDumpTextScroller.getViewport().getViewPosition());
        }
        ThreadDumpEntry tde = (ThreadDumpEntry) m_threadDumpList.getSelectedValue();
        m_threadDumpTextArea.setText(tde.getThreadDumpText());
        final Point viewPosition = tde.getViewPosition();
        if (viewPosition != null) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              m_threadDumpTextScroller.getViewport().setViewPosition(viewPosition);
            }
          });
        }
        m_lastSelectedEntry = tde;
      }
    });
    m_threadDumpTextArea = (TextArea) findComponent("ThreadDumpTextArea");
    m_threadDumpTextScroller = (ScrollPane) findComponent("ThreadDumpTextScroller");

    m_classCheckBox = (CheckBox) findComponent("Class1");
    m_locksCheckBox = (CheckBox) findComponent("Locks");
    m_transientRootCheckBox = (CheckBox) findComponent("TransientRoot");
    m_rootsCheckBox = (CheckBox) findComponent("Roots");
    m_distributedMethodsCheckBox = (CheckBox) findComponent("DistributedMethods");

    m_nonPortableDumpCheckBox = (CheckBox) findComponent("NonPortableDump");
    m_lockDebugCheckBox = (CheckBox) findComponent("LockDebug");
    m_fieldChangeDebugCheckBox = (CheckBox) findComponent("FieldChangeDebug");
    m_waitNotifyDebugCheckBox = (CheckBox) findComponent("WaitNotifyDebug");
    m_distributedMethodDebugCheckBox = (CheckBox) findComponent("DistributedMethodDebug");
    m_newObjectDebugCheckBox = (CheckBox) findComponent("NewObjectDebug");

    m_autoLockDetailsCheckBox = (CheckBox) findComponent("AutoLockDetails");
    m_callerCheckBox = (CheckBox) findComponent("Caller");
    m_fullStackCheckBox = (CheckBox) findComponent("FullStack");

    m_loggingControlMap = new HashMap<String, CheckBox>();

    m_cpuPanel = (Container) findComponent("CpuPanel");
    Container memoryPanel = (Container) findComponent("MemoryPanel");
    setupMemoryPanel(memoryPanel);

    setClient(client);
  }

  private TimeSeries createTimeSeries(String name) {
    TimeSeries ts = new TimeSeries(name, Second.class);
    ts.setMaximumItemCount(50);
    return ts;
  }

  private void setupMemoryPanel(Container memoryPanel) {
    memoryPanel.setLayout(new BorderLayout());
    m_memoryTimeSeries = new TimeSeries[2];
    m_memoryTimeSeries[0] = createTimeSeries("memory max");
    m_memoryTimeSeries[1] = createTimeSeries("memory used");
    JFreeChart chart = DemoChartFactory.getXYLineChart("", "", "", m_memoryTimeSeries);
    memoryPanel.add(new ChartPanel(chart, false));
  }

  private void setupCpuPanel(int processorCount) {
    m_cpuPanel.setLayout(new BorderLayout());
    m_cpuTimeSeriesMap = new HashMap<String, TimeSeries>();
    m_cpuTimeSeries = new TimeSeries[processorCount];
    for (int i = 0; i < processorCount; i++) {
      String cpuName = "cpu " + i;
      m_cpuTimeSeriesMap.put(cpuName, m_cpuTimeSeries[i] = createTimeSeries(cpuName));
    }
    JFreeChart chart = DemoChartFactory.getXYLineChart("", "", "", m_cpuTimeSeries);
    XYPlot plot = (XYPlot) chart.getPlot();
    NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
    numberAxis.setRange(0.0, 1.0);
    m_cpuPanel.add(new ChartPanel(chart, false));
  }

  public void setClient(DSOClient client) {
    m_client = client;

    String details = "<html><table border=1 cellspacing=0 cellpadding=3><tr><td>Host:</td<td>{0}</td></tr><tr><td>Port:</td><td>{1}</td></tr><tr><td>Channel:</td><td>{2}</td></tr></table></html>";
    m_detailsLabel.setText(MessageFormat.format(details, client.getHost(), Integer.toString(client.getPort()), client
        .getChannelID()));

    try {
      L1InfoMBean l1InfoBean = client.getL1InfoMBean(this);
      if (l1InfoBean != null) {
        l1InfoBean.addNotificationListener(this, null, null);
        m_environmentTextArea.setText(l1InfoBean.getEnvironment());
        m_configTextArea.setText(l1InfoBean.getConfig());

        Timer timer = new Timer(1000, new TaskPerformer());
        timer.start();
      }
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }

    m_loggingChangeHandler = new LoggingChangeHandler();

    try {
      InstrumentationLoggingMBean instrumentationLoggingBean = client.getInstrumentationLoggingMBean(this);
      if (instrumentationLoggingBean != null) {
        setupInstrumentationLogging(instrumentationLoggingBean);
        instrumentationLoggingBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }

    try {
      RuntimeLoggingMBean runtimeLoggingBean = client.getRuntimeLoggingMBean(this);
      if (runtimeLoggingBean != null) {
        setupRuntimeLogging(runtimeLoggingBean);
        runtimeLoggingBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }

    try {
      RuntimeOutputOptionsMBean runtimeOutputOptionsBean = client.getRuntimeOutputOptionsMBean(this);
      if (runtimeOutputOptionsBean != null) {
        setupRuntimeOutputOptions(runtimeOutputOptionsBean);
        runtimeOutputOptionsBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }
  }

  class TaskPerformer implements ActionListener {
    public void actionPerformed(ActionEvent evt) {
      try {
        L1InfoMBean l1InfoBean = m_client.getL1InfoMBean();
        if (l1InfoBean != null) {
          Map statMap = l1InfoBean.getStatistics();

          m_memoryTimeSeries[0].addOrUpdate(new Second(), Double.valueOf((Long) statMap.get("memory max")));
          m_memoryTimeSeries[1].addOrUpdate(new Second(), Double.valueOf((Long) statMap.get("memory used")));

          if (m_cpuPanel != null) {
            StatisticData[] cpuUsageData = (StatisticData[]) statMap.get("cpu usage");
            if (cpuUsageData != null) {
              if (m_cpuTimeSeries == null) {
                setupCpuPanel(cpuUsageData.length);
              }
              for (int i = 0; i < cpuUsageData.length; i++) {
                StatisticData cpuData = cpuUsageData[i];
                String cpuName = cpuData.getElement();
                TimeSeries timeSeries = m_cpuTimeSeriesMap.get(cpuName);
                if (timeSeries != null) {
                  timeSeries.addOrUpdate(new Second(), ((Number) cpuData.getData()).doubleValue());
                }
              }
            } else {
              // Sigar must not be available; hide cpu page
              m_tabbedPane.remove(m_cpuPanel);
              m_cpuPanel = null;
            }
          }
        }
      } catch (Exception e) {/**/
      }
    }
  }

  public DSOClient getClient() {
    return m_client;
  }

  private void setupInstrumentationLogging(InstrumentationLoggingMBean instrumentationLoggingBean) {
    setupLoggingControl(m_classCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_locksCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_transientRootCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_rootsCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_distributedMethodsCheckBox, instrumentationLoggingBean);
  }

  private void setupRuntimeLogging(RuntimeLoggingMBean runtimeLoggingBean) {
    setupLoggingControl(m_nonPortableDumpCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_lockDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_fieldChangeDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_waitNotifyDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_distributedMethodDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_newObjectDebugCheckBox, runtimeLoggingBean);
  }

  private void setupRuntimeOutputOptions(RuntimeOutputOptionsMBean runtimeOutputOptionsBean) {
    setupLoggingControl(m_autoLockDetailsCheckBox, runtimeOutputOptionsBean);
    setupLoggingControl(m_callerCheckBox, runtimeOutputOptionsBean);
    setupLoggingControl(m_fullStackCheckBox, runtimeOutputOptionsBean);
  }

  private void setupLoggingControl(CheckBox checkBox, Object bean) {
    setLoggingControl(checkBox, bean);
    checkBox.putClientProperty(checkBox.getName(), bean);
    checkBox.addActionListener(m_loggingChangeHandler);
    m_loggingControlMap.put(checkBox.getName(), checkBox);
  }

  private void setLoggingControl(CheckBox checkBox, Object bean) {
    try {
      Class beanClass = bean.getClass();
      Method setter = beanClass.getMethod("get" + checkBox.getName(), new Class[0]);
      Boolean value = (Boolean) setter.invoke(bean, new Object[0]);
      checkBox.setSelected(value.booleanValue());
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }
  }

  private void setLoggingBean(CheckBox checkBox) {
    try {
      Object bean = checkBox.getClientProperty(checkBox.getName());
      Class beanClass = bean.getClass();
      Method setter = beanClass.getMethod("set" + checkBox.getName(), new Class[] { Boolean.TYPE });
      setter.invoke(bean, new Object[] { Boolean.valueOf(checkBox.isSelected()) });
    } catch (Exception e) {
      AdminClient.getContext().log(e);
    }
  }

  class LoggingChangeHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      setLoggingBean((CheckBox) ae.getSource());
    }
  }

  public void addNotify() {
    super.addNotify();
    m_threadDumpsSplitter.addPropertyChangeListener(m_dividerListener);
  }

  public void removeNotify() {
    m_threadDumpsSplitter.removePropertyChangeListener(m_dividerListener);
    super.removeNotify();
  }

  public void doLayout() {
    super.doLayout();

    if (m_dividerLoc != null) {
      m_threadDumpsSplitter.setDividerLocation(m_dividerLoc.intValue());
    } else {
      m_threadDumpsSplitter.setDividerLocation(0.7);
    }
  }

  private int getThreadDumpSplitPref() {
    Preferences prefs = getPreferences();
    Preferences splitPrefs = prefs.node(m_threadDumpsSplitter.getName());
    return splitPrefs.getInt("Split", -1);
  }

  protected Preferences getPreferences() {
    AdminClientContext acc = AdminClient.getContext();
    return acc.prefs.node("ClientPanel");
  }

  protected void storePreferences() {
    AdminClientContext acc = AdminClient.getContext();
    acc.client.storePrefs();
  }

  private class DividerListener implements PropertyChangeListener {
    public void propertyChange(PropertyChangeEvent pce) {
      JSplitPane splitter = (JSplitPane) pce.getSource();
      String propName = pce.getPropertyName();

      if (splitter.isShowing() == false || JSplitPane.DIVIDER_LOCATION_PROPERTY.equals(propName) == false) { return; }

      int divLoc = splitter.getDividerLocation();
      Integer divLocObj = new Integer(divLoc);
      Preferences prefs = getPreferences();
      String name = splitter.getName();
      Preferences node = prefs.node(name);

      node.putInt("Split", divLoc);
      storePreferences();

      m_dividerLoc = divLocObj;
    }
  }

  public void handleNotification(Notification notification, Object handback) {
    String type = notification.getType();

    if (type.startsWith("tc.logging.")) {
      String name = type.substring(type.lastIndexOf('.') + 1);
      CheckBox checkBox = m_loggingControlMap.get(name);
      if (checkBox != null) {
        checkBox.setSelected(Boolean.valueOf(notification.getMessage()));
      }
      return;
    }

    if (type.equals("l1.statistics")) {
      Map statMap = (Map) notification.getUserData();

      m_memoryTimeSeries[0].addOrUpdate(new Second(), Double.valueOf((Long) statMap.get("memory free")));
      m_memoryTimeSeries[1].addOrUpdate(new Second(), Double.valueOf((Long) statMap.get("memory max")));
      m_memoryTimeSeries[2].addOrUpdate(new Second(), Double.valueOf((Long) statMap.get("memory used")));

      if (statMap.containsKey("cpu combined")) {
        m_cpuTimeSeries[0].addOrUpdate(new Second(), ((Number) statMap.get("cpu combined")).doubleValue());
        m_cpuTimeSeries[1].addOrUpdate(new Second(), ((Number) statMap.get("cpu idle")).doubleValue());
        m_cpuTimeSeries[2].addOrUpdate(new Second(), ((Number) statMap.get("cpu nice")).doubleValue());
        m_cpuTimeSeries[3].addOrUpdate(new Second(), ((Number) statMap.get("cpu sys")).doubleValue());
        m_cpuTimeSeries[4].addOrUpdate(new Second(), ((Number) statMap.get("cpu user")).doubleValue());
        m_cpuTimeSeries[5].addOrUpdate(new Second(), ((Number) statMap.get("cpu wait")).doubleValue());
      }
      return;
    }

    if (notification instanceof MBeanServerNotification) {
      MBeanServerNotification mbsn = (MBeanServerNotification) notification;

      if (type.equals(MBeanServerNotification.REGISTRATION_NOTIFICATION)) {
        String on = mbsn.getMBeanName().getCanonicalName();

        if (on.equals(m_client.getInstrumentationLoggingObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                InstrumentationLoggingMBean instrumentationLoggingBean = m_client.getInstrumentationLoggingMBean();
                setupInstrumentationLogging(instrumentationLoggingBean);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getRuntimeLoggingObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                RuntimeLoggingMBean runtimeLoggingBean = m_client.getRuntimeLoggingMBean();
                setupRuntimeLogging(runtimeLoggingBean);
                runtimeLoggingBean.addNotificationListener(ClientPanel.this, null, null);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getRuntimeOutputOptionsObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                RuntimeOutputOptionsMBean runtimeOutputOptionsBean = m_client.getRuntimeOutputOptionsMBean();
                setupRuntimeOutputOptions(runtimeOutputOptionsBean);
                runtimeOutputOptionsBean.addNotificationListener(ClientPanel.this, null, null);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getL1InfoObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                L1InfoMBean l1InfoBean = m_client.getL1InfoMBean();
                l1InfoBean.addNotificationListener(ClientPanel.this, null, null);
                m_environmentTextArea.setText(l1InfoBean.getEnvironment());
                m_configTextArea.setText(l1InfoBean.getConfig());

                Timer timer = new Timer(1000, new TaskPerformer());
                timer.start();
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        }
      }
    }
  }
}
