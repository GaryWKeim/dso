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
package com.tc.util.diff;

import org.apache.commons.lang3.builder.EqualsBuilder;

/**
 * A mock {@link Difference}, for use in tests.
 */
public class MockDifference extends Difference {

  private final Object a;
  private final Object b;

  public MockDifference(DifferenceContext where, Object a, Object b) {
    super(where);

    this.a = a;
    this.b = b;
  }

  public MockDifference(DifferenceContext where) {
    this(where, new Object(), new Object());
  }

  @Override
  public Object a() {
    return this.a;
  }

  @Override
  public Object b() {
    return this.b;
  }

  @Override
  public String toString() {
    return "<MockDifference: " + a() + ", " + b() + ">";
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((a == null) ? 0 : a.hashCode());
    result = prime * result + ((b == null) ? 0 : b.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object that) {
    if (!(that instanceof MockDifference)) return false;

    MockDifference mockThat = (MockDifference) that;

    return new EqualsBuilder().appendSuper(super.equals(that)).append(this.a, mockThat.a).append(this.b, mockThat.b)
        .isEquals();
  }

}