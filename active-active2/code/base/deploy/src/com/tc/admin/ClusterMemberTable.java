/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin;

import com.tc.admin.common.XObjectTable;
import com.tc.admin.common.XObjectTableModel;

import java.awt.Point;
import java.awt.event.MouseEvent;

public class ClusterMemberTable extends XObjectTable {
  public ClusterMemberTable() {
    super();
  }
  
  public String getToolTipText(MouseEvent event) {
    String tip = null;
    Point  p   = event.getPoint();
    int    row = rowAtPoint(p);

    if(row != -1) {  
      XObjectTableModel       model = (XObjectTableModel)getModel();
      ServerConnectionManager scm   = (ServerConnectionManager)model.getObjectAt(row);
      Exception               e     = scm.getConnectionException();
      
      if(e != null) {
        tip = ClusterNode.getConnectionExceptionString(e, scm.getConnectionContext());
      }
    }
    
    return tip;
  }
}
