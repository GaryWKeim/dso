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
package com.tc.server;

import com.tc.util.ProductInfo;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class VersionServlet extends HttpServlet {
  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    ProductInfo productInfo = ProductInfo.getInstance();
    response.setHeader("Version", productInfo.version());
    PrintWriter writer = response.getWriter();
    writer.println("<html><title>Version Information</title><body><pre>");
    writer.println(productInfo.toLongString());
    if (productInfo.isPatched()) {
      writer.println("<br>");
      writer.println(productInfo.toLongPatchString());
    }
    writer.println("</pre></body></html>");
  }
}
