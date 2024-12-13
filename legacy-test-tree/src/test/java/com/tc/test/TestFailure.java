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
package com.tc.test;

import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.Date;


public class TestFailure {
  private final long timestamp;
  private final String message;
  private final Thread thread;
  private final Throwable throwable;
  
  public TestFailure(String message, Thread thread, Throwable throwable) {
    this.timestamp = System.currentTimeMillis();
    this.message = message;
    this.thread = thread;
    this.throwable= throwable;
  }
  
  @Override
  public String toString() {
    StringBuffer buf = new StringBuffer( new Date(timestamp) + " " + thread + message );
    if (this.throwable != null) {
      buf.append(": " + ExceptionUtils.getStackTrace(this.throwable));
    }
    return buf.toString();
  }
}