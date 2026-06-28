package com.thermalglider.util;

/**
 * RingBuffer — кольцевой буфер фиксированного размера.
 * GC-free: хранит Object[] без аллокаций после прогрева.
 *
 * Раздел 2.7 ТЗ.
 */
public class RingBuffer<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head;
    private int count;

    public RingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        this.head = 0;
        this.count = 0;
    }

    public void add(T item) {
        buffer[head] = item;
        head = (head + 1) % capacity;
        if (count < capacity) count++;
    }

    /** 0 = самый новый */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        if (index < 0 || index >= count) return null;
        int pos = (head - 1 - index + capacity) % capacity;
        return (T) buffer[pos];
    }

    /** Самый старый */
    @SuppressWarnings("unchecked")
    public T getOldest() {
        if (count == 0) return null;
        return (T) buffer[(head - count + capacity) % capacity];
    }

    public int size() { return count; }
    public int capacity() { return capacity; }
    public boolean isEmpty() { return count == 0; }
    public boolean isFull() { return count >= capacity; }

    public void clear() {
        for (int i = 0; i < capacity; i++) buffer[i] = null;
        head = 0;
        count = 0;
    }
}
