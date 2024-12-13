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
package com.tc.util.stringification;

import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.builder.StandardToStringStyle;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * A subclass of {@link ToStringBuilder} that lets us override things as we'd want.
 */
public class OurStringBuilder extends ToStringBuilder {

  public static final StandardToStringStyle STANDARD_STYLE;
  public static final StandardToStringStyle MULTI_LINE_STYLE;
  public static final StandardToStringStyle COMPACT_STYLE;

  static {
    STANDARD_STYLE = new StandardToStringStyle();
    STANDARD_STYLE.setUseShortClassName(true);
    STANDARD_STYLE.setArrayContentDetail(true);
    STANDARD_STYLE.setFieldSeparator(", ");
    STANDARD_STYLE.setArraySeparator(", ");

    MULTI_LINE_STYLE = new StandardToStringStyle();
    MULTI_LINE_STYLE.setUseShortClassName(true);
    MULTI_LINE_STYLE.setArrayContentDetail(true);
    MULTI_LINE_STYLE.setContentStart("[");
    MULTI_LINE_STYLE.setFieldSeparator(SystemUtils.LINE_SEPARATOR + "  ");
    MULTI_LINE_STYLE.setFieldSeparatorAtStart(true);
    MULTI_LINE_STYLE.setContentEnd(SystemUtils.LINE_SEPARATOR + "]");
    MULTI_LINE_STYLE.setArraySeparator(", ");

    COMPACT_STYLE = new StandardToStringStyle() {
      @Override
      public void appendStart(StringBuffer buffer, Object object) {
        buffer.append("<");
        appendClassName(buffer, object);
        appendIdentityHashCode(buffer, object);
        appendContentStart(buffer);
      }
    };
    COMPACT_STYLE.setUseShortClassName(true);
    COMPACT_STYLE.setArrayContentDetail(true);
    COMPACT_STYLE.setContentStart(": ");
    COMPACT_STYLE.setContentEnd(">");
    COMPACT_STYLE.setFieldNameValueSeparator(" ");
    COMPACT_STYLE.setFieldSeparator(", ");
    COMPACT_STYLE.setArraySeparator(", ");
  }

  public OurStringBuilder(Object arg0, ToStringStyle arg1, StringBuffer arg2) {
    super(arg0, arg1, arg2);
  }

  public OurStringBuilder(Object arg0, ToStringStyle arg1) {
    super(arg0, arg1);
  }

  public OurStringBuilder(Object arg0) {
    this(arg0, STANDARD_STYLE);
  }
  
  @Override
  public ToStringBuilder append(String tag, Object[]arr, boolean b) {
    if (arr == null) return super.append(tag, (Object[])null, b);
    if (arr.length == 0) return super.append(tag, arr, b);
    this.append("{elementCount=" + arr.length + ":");
    for (int i = 0; i < arr.length; i++) {
      Object e = arr[i];
      if (e == null) this.append("<null>");
      else this.append("[#" + i + "<" + e.getClass().getName() + "=" + e + ">]");
    }
    this.append("]");
    return this;
  }

}
