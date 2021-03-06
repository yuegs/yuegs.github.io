package com.ugym;

import android.support.annotation.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import io.reactivex.internal.fuseable.SimplePlainQueue;
import io.reactivex.internal.util.Pow2;

public class  SpscLinkedArrayQueue<T> implements SimplePlainQueue<T> {
    static final int MAX_LOOK_AHEAD_STEP = Integer.getInteger("jctools.spsc.max.lookahead.step", 4096);
    final AtomicLong producerIndex = new AtomicLong();

    int producerLookAheadStep;
    long producerLookAhead;

    final int producerMask;

    AtomicReferenceArray<Object> producerBuffer;
    final int consumerMask;
    AtomicReferenceArray<Object> consumerBuffer;
    final AtomicLong consumerIndex = new AtomicLong();

    private static final Object HAS_NEXT = new Object();

    public SpscLinkedArrayQueue(final int bufferSize) {
        // 队列容量，之所以去 2 的幂，主要是加快匀速速度，比如取余，就直接是和下面的 mask 与运算即可
        int p2capacity = Pow2.roundToPowerOfTwo(Math.max(8, bufferSize));
        int mask = p2capacity - 1;
        // 生活和消费队列，提供了可以原子读取和写入的底层引用数组的操作
        AtomicReferenceArray<Object> buffer = new AtomicReferenceArray<Object>(p2capacity + 1);
        producerBuffer = buffer;
        // 掩码，主要用于快速地 取余
        producerMask = mask;
        adjustLookAheadStep(p2capacity);
        consumerBuffer = buffer;
        consumerMask = mask;
        producerLookAhead = mask - 1; // we know it's all empty to start with
        soProducerIndex(0L);
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation is correct for single producer thread use only.
     */
    @Override
    public boolean offer(final T e) {
        if (null == e) {
            throw new NullPointerException("Null is not a valid element");
        }
        // local load of field to avoid repeated loads after volatile reads
        final AtomicReferenceArray<Object> buffer = producerBuffer;
        // 每次生产一个，producerIndex++ ；
        final long index = lpProducerIndex();
        final int mask = producerMask;
        final int offset = calcWrappedOffset(index, mask);
        // 如果生产者队列中还有空余
        if (index < producerLookAhead) {
            return writeToQueue(buffer, e, index, offset);
        } else {
            // 生产者队列已经满了
            final int lookAheadStep = producerLookAheadStep;
            // go around the buffer or resize if full (unless we hit max capacity)
            int lookAheadElementOffset = calcWrappedOffset(index + lookAheadStep, mask);
            // 环形队列，看看已经消费者是否已经把前面一定范围的对象消费掉
            // 如果已经被消费，可以重用那些空位
            if (null == lvElement(buffer, lookAheadElementOffset)) { // LoadLoad
                producerLookAhead = index + lookAheadStep - 1; // joy, there's plenty of room
                return writeToQueue(buffer, e, index, offset);
            } else if (null == lvElement(buffer, calcWrappedOffset(index + 1, mask))) { // buffer is not full
                return writeToQueue(buffer, e, index, offset);
            } else {
                // 如果没有被消费，则扩充 array 大小
                resize(buffer, index, offset, e, mask); // add a buffer and link old to new
                return true;
            }
        }
    }

    private boolean writeToQueue(final AtomicReferenceArray<Object> buffer, final T e, final long index, final int offset) {
        soElement(buffer, offset, e);// StoreStore
        soProducerIndex(index + 1);// this ensures atomic write of long on 32bit platforms
        return true;
    }

    private void resize(final AtomicReferenceArray<Object> oldBuffer, final long currIndex, final int offset, final T e,
                        final long mask) {
        final int capacity = oldBuffer.length();
        // 在创建一个 环形队列
        final AtomicReferenceArray<Object> newBuffer = new AtomicReferenceArray<Object>(capacity);
        // 作为新的 producerBuffer
        producerBuffer = newBuffer;
        producerLookAhead = currIndex + mask - 1;
        soElement(newBuffer, offset, e);// StoreStore
        // 将原来队列中，最后一个元素连接到下一个 buffer
        soNext(oldBuffer, newBuffer);
        // 在原队列中设置一个 flag - HAS_NEXT
        soElement(oldBuffer, offset, HAS_NEXT); // new buffer is visible after element is
        // inserted
        soProducerIndex(currIndex + 1);// this ensures correctness on 32bit platforms
    }

    private void soNext(AtomicReferenceArray<Object> curr, AtomicReferenceArray<Object> next) {
        soElement(curr, calcDirectOffset(curr.length() - 1), next);
    }
    @SuppressWarnings("unchecked")
    private AtomicReferenceArray<Object> lvNext(AtomicReferenceArray<Object> curr) {
        return (AtomicReferenceArray<Object>)lvElement(curr, calcDirectOffset(curr.length() - 1));
    }
    /**
     * {@inheritDoc}
     * <p>
     * This implementation is correct for single consumer thread use only.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    @Override
    public T poll() {
        // local load of field to avoid repeated loads after volatile reads
        final AtomicReferenceArray<Object> buffer = consumerBuffer;
        final long index = lpConsumerIndex();
        final int mask = consumerMask;
        final int offset = calcWrappedOffset(index, mask);
        final Object e = lvElement(buffer, offset);// LoadLoad
        boolean isNextBuffer = e == HAS_NEXT;
        if (null != e && !isNextBuffer) {
            soElement(buffer, offset, null);// StoreStore
            soConsumerIndex(index + 1);// this ensures correctness on 32bit platforms
            return (T) e;
        } else if (isNextBuffer) {
            return newBufferPoll(lvNext(buffer), index, mask);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private T newBufferPoll(AtomicReferenceArray<Object> nextBuffer, final long index, final int mask) {
        consumerBuffer = nextBuffer;
        final int offsetInNew = calcWrappedOffset(index, mask);
        final T n = (T) lvElement(nextBuffer, offsetInNew);// LoadLoad
        if (null != n) {
            soElement(nextBuffer, offsetInNew, null);// StoreStore
            soConsumerIndex(index + 1);// this ensures correctness on 32bit platforms
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        final AtomicReferenceArray<Object> buffer = consumerBuffer;
        final long index = lpConsumerIndex();
        final int mask = consumerMask;
        final int offset = calcWrappedOffset(index, mask);
        final Object e = lvElement(buffer, offset);// LoadLoad
        if (e == HAS_NEXT) {
            return newBufferPeek(lvNext(buffer), index, mask);
        }

        return (T) e;
    }

    @SuppressWarnings("unchecked")
    private T newBufferPeek(AtomicReferenceArray<Object> nextBuffer, final long index, final int mask) {
        consumerBuffer = nextBuffer;
        final int offsetInNew = calcWrappedOffset(index, mask);
        return (T) lvElement(nextBuffer, offsetInNew);// LoadLoad
    }
    @Override
    public void clear() {
        while (poll() != null || !isEmpty()) { } // NOPMD
    }

    public int size() {
        /*
         * It is possible for a thread to be interrupted or reschedule between the read of the producer and
         * consumer indices, therefore protection is required to ensure size is within valid range. In the
         * event of concurrent polls/offers to this method the size is OVER estimated as we read consumer
         * index BEFORE the producer index.
         */
        long after = lvConsumerIndex();
        while (true) {
            final long before = after;
            final long currentProducerIndex = lvProducerIndex();
            after = lvConsumerIndex();
            if (before == after) {
                return (int) (currentProducerIndex - after);
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return lvProducerIndex() == lvConsumerIndex();
    }

    private void adjustLookAheadStep(int capacity) {
        producerLookAheadStep = Math.min(capacity / 4, MAX_LOOK_AHEAD_STEP);
    }

    private long lvProducerIndex() {
        return producerIndex.get();
    }

    private long lvConsumerIndex() {
        return consumerIndex.get();
    }

    private long lpProducerIndex() {
        return producerIndex.get();
    }

    private long lpConsumerIndex() {
        return consumerIndex.get();
    }

    private void soProducerIndex(long v) {
        producerIndex.lazySet(v);
    }

    private void soConsumerIndex(long v) {
        consumerIndex.lazySet(v);
    }

    private static int calcWrappedOffset(long index, int mask) {
        return calcDirectOffset((int)index & mask);
    }
    private static int calcDirectOffset(int index) {
        return index;
    }
    private static void soElement(AtomicReferenceArray<Object> buffer, int offset, Object e) {
        buffer.lazySet(offset, e);
    }

    private static <E> Object lvElement(AtomicReferenceArray<Object> buffer, int offset) {
        return buffer.get(offset);
    }

    /**
     * Offer two elements at the same time.
     * <p>Don't use the regular offer() with this at all!
     * @param first the first value, not null
     * @param second the second value, not null
     * @return true if the queue accepted the two new values
     */
    @Override
    public boolean offer(T first, T second) {
        final AtomicReferenceArray<Object> buffer = producerBuffer;
        final long p = lvProducerIndex();
        final int m = producerMask;

        int pi = calcWrappedOffset(p + 2, m);

        if (null == lvElement(buffer, pi)) {
            pi = calcWrappedOffset(p, m);
            soElement(buffer, pi + 1, second);
            soElement(buffer, pi, first);
            soProducerIndex(p + 2);
        } else {
            final int capacity = buffer.length();
            final AtomicReferenceArray<Object> newBuffer = new AtomicReferenceArray<Object>(capacity);
            producerBuffer = newBuffer;

            pi = calcWrappedOffset(p, m);
            soElement(newBuffer, pi + 1, second);// StoreStore
            soElement(newBuffer, pi, first);
            soNext(buffer, newBuffer);

            soElement(buffer, pi, HAS_NEXT); // new buffer is visible after element is

            soProducerIndex(p + 2);// this ensures correctness on 32bit platforms
        }

        return true;
    }
}
