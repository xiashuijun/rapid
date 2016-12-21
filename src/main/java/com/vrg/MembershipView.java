package com.vrg;

import com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hosts K permutations of the memberlist that represent the monitoring
 * relationship between nodes; every node monitors its successor on each ring.
 *
 * TODO: too many scans of the k rings during reads. Maintain a cache.
 */
@DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.ALL)
class MembershipView {
    private final ConcurrentHashMap<Integer, ArrayList<Node>> rings;
    private final int K;
    private final HashComparator[] hashComparators;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicInteger nodeAlreadyInRingExceptionsThrown = new AtomicInteger(0);
    private final AtomicInteger nodeNotInRingExceptionsThrown = new AtomicInteger(0);

    MembershipView(final int K) {
        assert K > 0;
        this.K = K;
        this.rings = new ConcurrentHashMap<>(K);
        this.hashComparators = new HashComparator[K];
        for (int k = 0; k < K; k++) {
            this.rings.put(k, new ArrayList<>());
            hashComparators[k] = new HashComparator(Integer.toString(k));
        }
    }

    MembershipView(final int K, final Node node) {
        assert K > 0;
        this.K = K;
        this.rings = new ConcurrentHashMap<>(K);
        this.hashComparators = new HashComparator[K];
        for (int k = 0; k < K; k++) {
            final ArrayList<Node> list = new ArrayList<>();
            list.add(node);
            this.rings.put(k, list);
            hashComparators[k] = new HashComparator(Integer.toString(k));
        }
    }

    @VisibleForTesting
    void ringAdd(final Node node) throws NodeAlreadyInRingException {
        try {
            rwLock.writeLock().lock();
            for (int k = 0; k < K; k++) {
                final ArrayList<Node> list = rings.get(k);
                final int index = Collections.binarySearch(list, node, hashComparators[k]);

                if (index >= 0) {
                    throw new NodeAlreadyInRingException(node);
                }

                // Indexes being changed are (-1 * index - 1) - 1, (-1 * index - 1), (-1 * index - 1) + 1
                final int newNodeIndex = (-1 * index - 1);
                list.add(newNodeIndex, node);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @VisibleForTesting
    void ringDelete(final Node node) throws NodeNotInRingException {
        try {
            rwLock.writeLock().lock();
            for (int k = 0; k < K; k++) {
                final ArrayList<Node> list = rings.get(k);
                final int index = Collections.binarySearch(list, node, hashComparators[k]);

                if (index < 0) {
                    throw new NodeNotInRingException(node);
                }

                // Indexes being changed are index - 1, index, index + 1
                list.remove(index);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the set of monitors for {@code node}
     * @param node input node
     * @return the set of monitors for {@code node}
     * @throws NodeNotInRingException thrown if {@code node} is not in the ring
     */
    Set<Node> monitorsOf(final Node node) throws NodeNotInRingException {
        try {
            rwLock.readLock().lock();
            final Set<Node> monitors = new HashSet<>();
            for (int k = 0; k < K; k++) {
                final ArrayList<Node> list = rings.get(k);

                if (list.size() <= 1) {
                    return monitors;
                }

                final int index = Collections.binarySearch(list, node, hashComparators[k]);

                if (index < 0) {
                    throw new NodeNotInRingException(node);
                }

                monitors.add(list.get(Math.floorMod(index - 1, list.size()))); // Handles wrap around
            }
            return monitors;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Returns the set of nodes monitored by {@code node}
     * @param node input node
     * @return the set of nodes monitored by {@code node}
     * @throws NodeNotInRingException thrown if {@code node} is not in the ring
     */
    Set<Node> monitoreesOf(final Node node) throws NodeNotInRingException {
        try {
            rwLock.readLock().lock();
            final Set<Node> monitorees = new HashSet<>();
            for (int k = 0; k < K; k++) {
                final ArrayList<Node> list = rings.get(k);

                if (list.size() <= 1) {
                    return monitorees;
                }

                final int index = Collections.binarySearch(list, node, hashComparators[k]);

                if (index < 0) {
                    throw new NodeNotInRingException(node);
                }

                monitorees.add(list.get((index + 1) % list.size())); // Handles wrap around
            }
            return monitorees;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Deliver a LinkUpdateMessage
     * @param msg message to deliver
     */
    void deliver(final LinkUpdateMessage msg) {
        try {
            switch (msg.getStatus()) {
                case UP:
                    ringAdd(new Node(msg.getSrc()));
                    break;
                case DOWN:
                    ringDelete(new Node(msg.getSrc()));
                    break;
                default:
                    // Invalid message
                    assert false;
            }
        } catch (final NodeAlreadyInRingException e) {
            nodeAlreadyInRingExceptionsThrown.incrementAndGet();
        } catch (final NodeNotInRingException e) {
            nodeNotInRingExceptionsThrown.incrementAndGet();
        }

    }

    @VisibleForTesting
    List<Node> viewRing(final int k) {
        try {
            rwLock.readLock().lock();
            assert k >= 0;
            return Collections.unmodifiableList(rings.get(k));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.ALL)
    private static final class HashComparator implements Comparator<Node> {
        private final String seed;

        HashComparator(final String seed) {
            this.seed = seed;
        }

        public final int compare(final Node c1, final Node c2) {
            return Utils.sha1Hex(c1.address.toString() + seed)
                    .compareTo(Utils.sha1Hex(c2.address.toString() + seed));
        }
    }

    @DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.ALL)
    class NodeAlreadyInRingException extends Exception {
        NodeAlreadyInRingException(final Node node) {
            super(node.address.toString());
        }
    }

    @DefaultQualifier(value = NonNull.class, locations = TypeUseLocation.ALL)
    class NodeNotInRingException extends Exception {
        NodeNotInRingException(final Node node) {
            super(node.address.toString());
        }
    }
}