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
package com.tc.config.schema.messaging.http;

import org.apache.commons.io.IOUtils;
import org.terracotta.groupConfigForL1.GroupnameId;
import org.terracotta.groupConfigForL1.GroupnameIdMapDocument;
import org.terracotta.groupConfigForL1.GroupnameIdMapDocument.GroupnameIdMap;

import com.tc.config.schema.ActiveServerGroupConfig;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.List;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class GroupIDMapServlet extends HttpServlet {
  public static final String GROUPID_MAP_ATTRIBUTE = GroupIDMapServlet.class.getName() + ".groupidmap";

  @Override
  protected synchronized void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    L2ConfigurationSetupManager configSetupManager = (L2ConfigurationSetupManager) getServletContext()
        .getAttribute(GROUPID_MAP_ATTRIBUTE);
    List<ActiveServerGroupConfig> activeServerGroupConfigs = configSetupManager.activeServerGroupsConfig()
        .getActiveServerGroups();
    GroupnameIdMapDocument groupnameIdMapDocument = GroupnameIdMapDocument.Factory.newInstance();
    GroupnameIdMap groupnameIdMap = groupnameIdMapDocument.addNewGroupnameIdMap();
    for (ActiveServerGroupConfig group : activeServerGroupConfigs) {
      GroupnameId groupnameId = groupnameIdMap.addNewGroupnameId();
      groupnameId.setName(group.getGroupName());
      groupnameId.setGid(new BigInteger(String.valueOf(group.getGroupId().toInt())));
    }

    OutputStream out = response.getOutputStream();
    int bytesCopied = IOUtils.copy(groupnameIdMapDocument.newInputStream(), out);
    response.setContentLength(bytesCopied);
    response.flushBuffer();
  }
}
