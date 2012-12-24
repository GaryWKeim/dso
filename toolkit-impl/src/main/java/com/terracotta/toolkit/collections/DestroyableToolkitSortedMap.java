/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.collections;

import org.terracotta.toolkit.ToolkitObjectType;
import org.terracotta.toolkit.collections.ToolkitSortedMap;
import org.terracotta.toolkit.concurrent.locks.ToolkitReadWriteLock;

import com.google.common.base.Preconditions;
import com.terracotta.toolkit.collections.map.SubTypeWrapperSet;
import com.terracotta.toolkit.collections.map.SubTypeWrapperCollection;
import com.terracotta.toolkit.collections.map.SubTypeWrapperSortedMap;
import com.terracotta.toolkit.collections.map.ToolkitSortedMapImpl;
import com.terracotta.toolkit.factory.ToolkitObjectFactory;
import com.terracotta.toolkit.object.AbstractDestroyableToolkitObject;
import com.terracotta.toolkit.rejoin.RejoinAwareToolkitMap;
import com.terracotta.toolkit.type.IsolatedClusteredObjectLookup;
import com.terracotta.toolkit.util.ToolkitInstanceProxy;
import com.terracotta.toolkit.util.ToolkitSubtypeStatusImpl;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

public class DestroyableToolkitSortedMap<K extends Comparable<? super K>, V> extends
    AbstractDestroyableToolkitObject<ToolkitSortedMap> implements ToolkitSortedMap<K, V>, RejoinAwareToolkitMap<K, V> {

  private final String                                              name;
  private volatile ToolkitSortedMap<K, V>                           map;
  private final IsolatedClusteredObjectLookup<ToolkitSortedMapImpl> lookup;
  private final ToolkitSubtypeStatusImpl                            status;

  public DestroyableToolkitSortedMap(ToolkitObjectFactory<ToolkitSortedMap> factory,
                                     IsolatedClusteredObjectLookup<ToolkitSortedMapImpl> lookup,
                                     ToolkitSortedMapImpl<K, V> map, String name) {
    super(factory);
    this.lookup = lookup;
    this.map = map;
    this.name = name;
    status = new ToolkitSubtypeStatusImpl();
    map.setApplyDestroyCallback(getDestroyApplicator());
  }

  @Override
  public void rejoinStarted() {
    this.map = ToolkitInstanceProxy.newDestroyedInstanceProxy(name, ToolkitSortedMap.class);
    status.incrementRejoinCount();
  }

  @Override
  public void rejoinCompleted() {
    if (!isDestroyed()) {
      ToolkitSortedMapImpl afterRejoin = lookup.lookupOrCreateClusteredObject(name, ToolkitObjectType.SORTED_MAP, null);
      Preconditions.checkNotNull(afterRejoin);
      this.map = afterRejoin;
    }
  }

  @Override
  public void applyDestroy() {
    this.map = ToolkitInstanceProxy.newDestroyedInstanceProxy(name, ToolkitSortedMap.class);
  }

  @Override
  public void doDestroy() {
    map.destroy();
  }

  @Override
  public ToolkitReadWriteLock getReadWriteLock() {
    return map.getReadWriteLock();
  }

  @Override
  public int size() {
    return map.size();
  }

  @Override
  public boolean isEmpty() {
    return map.isEmpty();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean containsKey(Object key) {
    return map.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return map.containsValue(value);
  }

  @Override
  public V get(Object key) {
    return map.get(key);
  }

  @Override
  public V put(K key, V value) {
    return map.put(key, value);
  }

  @Override
  public V remove(Object key) {
    return map.remove(key);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    map.putAll(m);
  }

  @Override
  public void clear() {
    map.clear();
  }

  @Override
  public Set<K> keySet() {
    return new SubTypeWrapperSet<K>(map.keySet(), status, this.name, ToolkitObjectType.SORTED_MAP);
  }

  @Override
  public Collection<V> values() {
    return new SubTypeWrapperCollection<V>(map.values(), status, this.name,
                                                             ToolkitObjectType.SORTED_MAP);
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return new SubTypeWrapperSet<Entry<K, V>>(map.entrySet(), status, this.name,
                                                            ToolkitObjectType.SORTED_MAP);
  }

  @Override
  public Comparator<? super K> comparator() {
    return null;
  }

  @Override
  public K firstKey() {
    return map.firstKey();
  }

  @Override
  public K lastKey() {
    return map.lastKey();
  }

  @Override
  public SortedMap<K, V> headMap(K toKey) {
    return new SubTypeWrapperSortedMap<K, V>(map.headMap(toKey), status, this.name,
                                                               ToolkitObjectType.SORTED_MAP);
  }

  @Override
  public SortedMap<K, V> subMap(K fromKey, K toKey) {
    return new SubTypeWrapperSortedMap<K, V>(map.subMap(fromKey, toKey), status, this.name,
                                                               ToolkitObjectType.SORTED_MAP);
  }

  @Override
  public SortedMap<K, V> tailMap(K fromKey) {
    return new SubTypeWrapperSortedMap<K, V>(map.headMap(fromKey), status, this.name,
                                                               ToolkitObjectType.SORTED_MAP);
  }

}
