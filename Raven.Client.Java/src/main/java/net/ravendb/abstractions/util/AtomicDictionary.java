package net.ravendb.abstractions.util;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import net.ravendb.abstractions.basic.CleanCloseable;
import net.ravendb.abstractions.basic.Tuple;
import net.ravendb.abstractions.closure.Function1;


public class AtomicDictionary<T> implements Iterable<Entry<String, T>> {

  private final ConcurrentMap<String, Object> locks;
  private final ConcurrentMap<String, T> items;
  private final ReentrantReadWriteLock globalLocker = new ReentrantReadWriteLock(true);
  private final static String nullValue =  "Null Replacement: " + UUID.randomUUID();
  private List<T> snapshot;
  private final AtomicLong snapshotVersion = new AtomicLong(0);
  private final AtomicLong version = new AtomicLong(0);

  public AtomicDictionary(Comparator<String> comparer) {
    items = new ConcurrentSkipListMap<>(comparer);
    locks = new ConcurrentSkipListMap<>(comparer);
  }

  public AtomicDictionary() {
    items = new ConcurrentHashMap<>();
    locks = new ConcurrentHashMap<>();
  }

  public Collection<T> values() {
    return items.values();
  }

  public T getOrAdd(String key, Function1<String, T> valueGenerator) {

    ReadLock readLock = globalLocker.readLock();
    readLock.lock();
    try {
      Function1<String, T> actualGenerator = valueGenerator;
      String closureValue = key;
      if (key == null) {
        key = nullValue;
      }
      T val = items.get(key);
      if (val != null) {
        return val;
      }
      locks.putIfAbsent(key, new Object());
      synchronized (locks.get(key)) {
        val = items.get(key);
        if (val == null) {
          val = actualGenerator.apply(closureValue);
          version.incrementAndGet();
          items.put(key, val);
        }
        return val;
      }

    } finally {
      readLock.unlock();
    }
  }

  public List<T> getValuesSnapshot() {
    long currentVersion = version.get();

    if (currentVersion != snapshotVersion.get() || snapshot == null) {

      snapshot = new ArrayList<>(items.values());
      snapshotVersion.set(currentVersion);
    }

    return snapshot;
  }

  /**
   * Usage: synchronized (withLockFor(key))
   * @param key
   */
  public Object withLockFor(String key) {

    ReadLock readLock = globalLocker.readLock();
    try {
      readLock.lock();
      locks.putIfAbsent(key, new Object());
      return locks.get(key);
    } finally {
      readLock.unlock();
    }
  }

  public void remove(String key) {
    ReadLock readLock = globalLocker.readLock();
    try {
      readLock.lock();
      if (key == null) {
        key = nullValue;
      }
      Object value;
      value = locks.get(key);
      if (value == null) {
        items.remove(key);
        version.incrementAndGet();
        return ;
      }
      synchronized (value) {
          locks.remove(key);
          items.remove(key);
          version.incrementAndGet();
      }
    } finally {
      readLock.unlock();
    }

  }

  public void clear() {
    items.clear();
    locks.clear();
    version.incrementAndGet();
  }

  public T get(String key) {
    return items.get(key);
  }

  public CleanCloseable withAllLocks() {
    final WriteLock writeLock = globalLocker.writeLock();
    writeLock.lock();
    return new CleanCloseable() {

      @Override
      public void close() {
        writeLock.unlock();
      }
    };
  }

  @Override
  public Iterator<Entry<String, T>> iterator() {
    return items.entrySet().iterator();
  }

}
