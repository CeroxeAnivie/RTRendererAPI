package top.ceroxe.mcvulkanrt.renderer.orchestration.work;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A hard-capacity priority queue suitable for {@link java.util.concurrent.ThreadPoolExecutor}.
 *
 * <p>{@link PriorityBlockingQueue} deliberately has no capacity bound, while renderer work owns
 * large immutable payloads and therefore must be byte/count bounded before admission. A semaphore
 * owns capacity independently of ordering. Every removal path releases exactly one permit,
 * including iterator removal and predicate cancellation used during world-generation changes.</p>
 */
public final class BoundedPriorityBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {
    private final PriorityBlockingQueue<E> delegate;
    private final Semaphore capacity;
    private final int maximumCapacity;

    public BoundedPriorityBlockingQueue(int maximumCapacity, Comparator<? super E> comparator) {
        if (maximumCapacity <= 0) {
            throw new IllegalArgumentException("maximumCapacity must be positive");
        }
        this.maximumCapacity = maximumCapacity;
        delegate = new PriorityBlockingQueue<>(maximumCapacity, Objects.requireNonNull(comparator, "comparator"));
        capacity = new Semaphore(maximumCapacity);
    }

    @Override
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        if (!capacity.tryAcquire()) {
            return false;
        }
        boolean added = false;
        try {
            added = delegate.offer(element);
            return added;
        } finally {
            if (!added) {
                capacity.release();
            }
        }
    }

    @Override
    public void put(E element) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        capacity.acquire();
        boolean added = false;
        try {
            delegate.put(element);
            added = true;
        } finally {
            if (!added) {
                capacity.release();
            }
        }
    }

    @Override
    public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(unit, "unit");
        if (!capacity.tryAcquire(timeout, unit)) {
            return false;
        }
        boolean added = false;
        try {
            added = delegate.offer(element);
            return added;
        } finally {
            if (!added) {
                capacity.release();
            }
        }
    }

    @Override
    public E take() throws InterruptedException {
        E element = delegate.take();
        capacity.release();
        return element;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E element = delegate.poll(timeout, Objects.requireNonNull(unit, "unit"));
        if (element != null) {
            capacity.release();
        }
        return element;
    }

    @Override
    public E poll() {
        E element = delegate.poll();
        if (element != null) {
            capacity.release();
        }
        return element;
    }

    @Override
    public E peek() {
        return delegate.peek();
    }

    @Override
    public int remainingCapacity() {
        return capacity.availablePermits();
    }

    @Override
    public int drainTo(Collection<? super E> target) {
        return drainTo(target, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> target, int maximumElements) {
        Objects.requireNonNull(target, "target");
        if (target == this) {
            throw new IllegalArgumentException("cannot drain a queue into itself");
        }
        if (maximumElements <= 0) {
            return 0;
        }
        int drained = delegate.drainTo(target, maximumElements);
        if (drained != 0) {
            capacity.release(drained);
        }
        return drained;
    }

    @Override
    public boolean remove(Object candidate) {
        if (!delegate.remove(candidate)) {
            return false;
        }
        capacity.release();
        return true;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter, "filter");
        boolean removed = false;
        for (Object candidate : delegate.toArray()) {
            @SuppressWarnings("unchecked")
            E element = (E) candidate;
            if (filter.test(element) && remove(element)) {
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public void clear() {
        while (poll() != null) {
            // poll owns the corresponding capacity release even while workers drain concurrently.
        }
        assert capacity.availablePermits() <= maximumCapacity;
    }

    @Override
    public Iterator<E> iterator() {
        Iterator<E> iterator = delegate.iterator();
        return new Iterator<>() {
            private boolean removable;

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public E next() {
                E next = iterator.next();
                removable = true;
                return next;
            }

            @Override
            public void remove() {
                if (!removable) {
                    throw new IllegalStateException("next must be called before remove");
                }
                iterator.remove();
                removable = false;
                capacity.release();
            }
        };
    }

    @Override
    public int size() {
        return delegate.size();
    }
}
