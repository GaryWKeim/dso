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
package com.tc.test.collections;

import org.apache.commons.lang3.builder.EqualsBuilder;

import com.tc.util.Stringifier;

/**
 * A {@link CollectionMismatch}that is used when two collections don't contain the same number of instances of two
 * objects.
 */
class UnequalObjectCountCollectionMismatch extends CollectionMismatch {

  private final int numInCollectionOne;
  private final int numInCollectionTwo;

  public UnequalObjectCountCollectionMismatch(Object theObject, int objectIndexInCollectionOne, int numInCollectionOne,
                                              int numInCollectionTwo, Stringifier describer) {
    super(theObject, null, true, objectIndexInCollectionOne, -1, describer);

    this.numInCollectionOne = numInCollectionOne;
    this.numInCollectionTwo = numInCollectionTwo;
  }

  @Override
  public String toString() {
    return "Unequal number of objects: " + originatingString() + " occurs " + this.numInCollectionOne + " times "
           + "in collection one, but " + this.numInCollectionTwo + " times in collection two.";
  }

  @Override
  public boolean equals(Object that) {
    if (!(that instanceof UnequalObjectCountCollectionMismatch)) return false;

    UnequalObjectCountCollectionMismatch misThat = (UnequalObjectCountCollectionMismatch) that;

    return new EqualsBuilder().appendSuper(super.equals(that)).append(this.numInCollectionOne,
                                                                      misThat.numInCollectionOne)
        .append(this.numInCollectionTwo, misThat.numInCollectionTwo).isEquals();
  }

}