/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

import com.sun.jdi.connect.spi.ClosedConnectionException;
import net.openhft.chronicle.hash.RemoteCallTimeoutException;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.thread.NamedThreadFactory;
import net.openhft.lang.threadlocal.ThreadLocalCopies;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.ByteBuffer.allocateDirect;
import static net.openhft.chronicle.map.AbstractChannelReplicator.SIZE_OF_SIZE;
import static net.openhft.chronicle.map.AbstractChannelReplicator.SIZE_OF_TRANSACTION_ID;
import static net.openhft.chronicle.map.StatelessChronicleMap.EventId.*;

/**
 * @author Rob Austin.
 */
class StatelessChronicleMap<K, V> implements ChronicleMap<K, V>, Closeable, Cloneable {


    public static final String START_OF = "Attempt to write ";
    private static final Logger LOG = LoggerFactory.getLogger(StatelessChronicleMap.class);
    public static final byte STATELESS_CLIENT_IDENTIFIER = (byte) -127;

    private final byte[] connectionByte = new byte[1];
    private final ByteBuffer connectionOutBuffer = ByteBuffer.wrap(connectionByte);
    private final String name;
    private final AbstractChronicleMapBuilder chronicleMapBuilder;

    private ByteBuffer buffer;
    private ByteBufferBytes bytes;

    private final KeyValueSerializer<K, V> keyValueSerializer;
    private volatile SocketChannel clientChannel;

    private CloseablesManager closeables;
    private final StatelessMapConfig config;
    private int maxEntrySize;
    private final Class<K> kClass;
    private final Class<V> vClass;
    private final boolean putReturnsNull;
    private final boolean removeReturnsNull;

    private ExecutorService executorService;


    static enum EventId {
        HEARTBEAT,
        STATEFUL_UPDATE,
        LONG_SIZE, SIZE,
        IS_EMPTY,
        CONTAINS_KEY,
        CONTAINS_VALUE,
        GET,
        PUT,
        PUT_WITHOUT_ACC,
        REMOVE,
        REMOVE_WITHOUT_ACC,
        CLEAR,
        KEY_SET,
        VALUES,
        ENTRY_SET,
        REPLACE,
        REPLACE_WITH_OLD_AND_NEW_VALUE,
        PUT_IF_ABSENT,
        REMOVE_WITH_VALUE,
        TO_STRING,
        PUT_ALL,
        PUT_ALL_WITHOUT_ACC,
        HASH_CODE,
        MAP_FOR_KEY,
        UPDATE_FOR_KEY
    }

    private long transactionID;

    StatelessChronicleMap(final StatelessMapConfig config,
                          final AbstractChronicleMapBuilder chronicleMapBuilder) throws IOException {
        this.chronicleMapBuilder = chronicleMapBuilder;
        this.config = config;
        this.keyValueSerializer = new KeyValueSerializer<K, V>(chronicleMapBuilder.keyBuilder,
                chronicleMapBuilder.valueBuilder);
        this.putReturnsNull = chronicleMapBuilder.putReturnsNull();
        this.removeReturnsNull = chronicleMapBuilder.removeReturnsNull();
        this.maxEntrySize = chronicleMapBuilder.entrySize(true);

        if (maxEntrySize < 128)
            maxEntrySize = 128; // the stateless client will not work with extreemly small buffers

        this.vClass = chronicleMapBuilder.valueBuilder.eClass;
        this.kClass = chronicleMapBuilder.keyBuilder.eClass;
        this.name = chronicleMapBuilder.name();
        attemptConnect(config.remoteAddress());

        buffer = allocateDirect(maxEntrySize).order(ByteOrder.nativeOrder());
        bytes = new ByteBufferBytes(buffer.slice());
    }


    @Override
    public Future<V> getLater(@NotNull final K key) {
        return lazyExecutorService().submit(new Callable<V>() {
            @Override
            public V call() throws Exception {
                return StatelessChronicleMap.this.get(key);
            }
        });
    }

    @Override
    public Future<V> putLater(@NotNull final K key, @NotNull final V value) {
        return lazyExecutorService().submit(new Callable<V>() {
            @Override
            public V call() throws Exception {

                V oldValue = StatelessChronicleMap.this.put(key, value);
                return oldValue;
            }
        });
    }

    @Override
    public Future<V> removeLater(@NotNull final K key) {
        return lazyExecutorService().submit(new Callable<V>() {
            @Override
            public V call() throws Exception {

                V oldValue = StatelessChronicleMap.this.remove(key);
                return oldValue;
            }
        });
    }

    @Override
    public void getAll(File toFile) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(File fromFile) {
        throw new UnsupportedOperationException();
    }


    private ExecutorService lazyExecutorService() {

        if (executorService == null) {
            synchronized (this) {
                if (executorService == null)
                    executorService = Executors.newSingleThreadExecutor(new NamedThreadFactory(chronicleMapBuilder.name() +
                            "-stateless-client-async",
                            true));
            }
        }

        return executorService;
    }


    private SocketChannel lazyConnect(final long timeoutMs,
                                      final InetSocketAddress remoteAddress) throws IOException {

        if (LOG.isDebugEnabled())
            LOG.debug("attempting to connect to " + remoteAddress);

        SocketChannel result = null;

        long timeoutAt = System.currentTimeMillis() + timeoutMs;

        for (; ; ) {

            checkTimeout(timeoutAt);

            // ensures that the excising connection are closed
            closeExisting();

            try {
                result = AbstractChannelReplicator.openSocketChannel(closeables);
                result.connect(config.remoteAddress());
                result.socket().setTcpNoDelay(true);


                doHandShaking(result);
                break;
            } catch (IOException e) {
                closeables.closeQuietly();
            } catch (Exception e) {
                closeables.closeQuietly();
                throw e;
            }

        }

        return result;
    }


    /**
     * attempts a single connect without a timeout and eat any IOException used typically in the
     * constructor, its possible the server is not running with this instance is created, {@link
     * net.openhft.chronicle.map.StatelessChronicleMap#lazyConnect(long,
     * java.net.InetSocketAddress)} will attempt to establish the connection when the client make
     * the first map method call.
     *
     * @param remoteAddress
     * @return
     * @throws java.io.IOException
     * @see net.openhft.chronicle.map.StatelessChronicleMap#lazyConnect(long,
     * java.net.InetSocketAddress)
     */

    private void attemptConnect(final InetSocketAddress remoteAddress) throws IOException {

        // ensures that the excising connection are closed
        closeExisting();

        try {
            clientChannel = AbstractChannelReplicator.openSocketChannel(closeables);
            clientChannel.connect(remoteAddress);
            doHandShaking(clientChannel);

        } catch (IOException e) {
            closeables.closeQuietly();
        }

    }


    /**
     * closes the existing connections and establishes a new closeables
     */
    private void closeExisting() {

        // ensure that any excising connection are first closed
        if (closeables != null)
            closeables.closeQuietly();

        closeables = new CloseablesManager();
    }


    /**
     * initiates a very simple level of handshaking with the remote server, we send a special ID of
     * -127 ( when the server receives this it knows its dealing with a stateless client, receive
     * back an identifier from the server
     *
     * @param clientChannel
     * @throws java.io.IOException
     */
    private void doHandShaking(@NotNull final SocketChannel clientChannel) throws IOException {

        connectionByte[0] = STATELESS_CLIENT_IDENTIFIER;
        this.connectionOutBuffer.clear();

        long timeoutTime = System.currentTimeMillis() + config.timeoutMs();

        // write a single byte
        while (connectionOutBuffer.hasRemaining()) {
            clientChannel.write(connectionOutBuffer);
            checkTimeout(timeoutTime);
        }

        this.connectionOutBuffer.clear();

        // read a single  byte back
        while (this.connectionOutBuffer.position() <= 0) {
            clientChannel.read(this.connectionOutBuffer);
            checkTimeout(timeoutTime);
        }

        byte remoteIdentifier = connectionByte[0];

        if (LOG.isDebugEnabled())
            LOG.debug("Attached to a map with a remote identifier=" + remoteIdentifier);

    }

    public File file() {
        throw new UnsupportedOperationException();
    }

    public synchronized void close() {
        if (closeables != null)
            closeables.closeQuietly();
        closeables = null;

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(20, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOG.error("", e);
            }
        }

    }

    /**
     * the transaction id are generated as unique timestamps
     *
     * @param time in milliseconds
     * @return a unique transactionId
     */
    private long nextUniqueTransaction(long time) {
        transactionID = (time == transactionID) ? time + 1 : time;
        return transactionID;
    }


    public synchronized V putIfAbsent(K key, V value) {
        if (key == null)
            throw new NullPointerException();
        final long sizeLocation = writeEventAnSkip(PUT_IF_ABSENT);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        writeKey(key, local);
        writeValue(value, local);
        return readValue(sizeLocation, local);
    }


    public synchronized boolean remove(Object key, Object value) {

        if (key == null)
            throw new NullPointerException("key can not be null");

        final long sizeLocation = writeEventAnSkip(REMOVE_WITH_VALUE);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        writeKey((K) key, local);
        writeValue((V) value, local);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized boolean replace(K key, V oldValue, V newValue) {
        if (key == null)
            throw new NullPointerException("key can not be null");

        final long sizeLocation = writeEventAnSkip(REPLACE_WITH_OLD_AND_NEW_VALUE);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        writeKey(key, local);
        writeValue(oldValue, local);
        writeValue(newValue, local);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized V replace(K key, V value) {
        if (key == null)
            throw new NullPointerException("key can not be null");

        final long sizeLocation = writeEventAnSkip(REPLACE);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        writeKey(key, local);
        writeValue(value, local);

        // get the data back from the server
        return readValue(sizeLocation, local);
    }


    public synchronized int size() {
        return (int) longSize();
    }


    /**
     * calling this method should be avoided at all cost, as the entire {@code object} is
     * serialized. This equals can be used to compare map that extends ChronicleMap.  So two
     * Chronicle Maps that contain the same data are considered equal, even if the instances of the
     * chronicle maps were of different types
     *
     * @param object the object that you are comparing against
     * @return true if the contain the same data
     */
    @Override
    public synchronized boolean equals(Object object) {

        if (this == object) return true;
        if (object == null || object.getClass().isAssignableFrom(Map.class))
            return false;

        final Map<? extends K, ? extends V> that = (Map<? extends K, ? extends V>) object;

        final int size = size();

        if (that.size() != size)
            return false;

        final Set<Map.Entry<K, V>> entries = entrySet();

        return that.entrySet().equals(entries);

    }


    @Override
    public synchronized int hashCode() {
        return blockingFetch(writeEventAnSkip(HASH_CODE)).readInt();
    }

    public synchronized String toString() {
        return "name=" + name + ", " + blockingFetch(writeEventAnSkip(TO_STRING)).readObject(String
                .class);
    }


    public synchronized boolean isEmpty() {
        return blockingFetch(writeEventAnSkip(IS_EMPTY)).readBoolean();
    }


    public synchronized boolean containsKey(Object key) {
        final long sizeLocation = writeEventAnSkip(CONTAINS_KEY);
        writeKey((K) key);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();

    }


    public synchronized boolean containsValue(Object value) {
        final long sizeLocation = writeEventAnSkip(CONTAINS_VALUE);
        writeValue((V) value);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized long longSize() {
        return blockingFetch(writeEventAnSkip(LONG_SIZE)).readLong();
    }


    public synchronized V get(Object key) {
        final long sizeLocation = writeEventAnSkip(GET);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        writeKey((K) key, local);

        // get the data back from the server
        return readValue(sizeLocation, local);

    }

    public synchronized V getUsing(K key, V usingValue) {
        throw new UnsupportedOperationException("getUsing() is not supported for stateless " +
                "clients");
    }


    public synchronized V acquireUsing(@NotNull K key, V usingValue) {
        throw new UnsupportedOperationException("acquireUsing() is not supported for stateless " +
                "clients");
    }

    @NotNull
    @Override
    public synchronized WriteContext<K, V> acquireUsingLocked(@NotNull K key, @NotNull V
            usingValue) {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public synchronized ReadContext<K, V> getUsingLocked(@NotNull K key, @NotNull V usingValue) {
        throw new UnsupportedOperationException();
    }


    public synchronized V remove(Object key) {

        if (key == null)
            throw new NullPointerException("key can not be null");

        final long sizeLocation = removeReturnsNull ? writeEvent(REMOVE_WITHOUT_ACC) : writeEventAnSkip(REMOVE);


        writeKey((K) key);

        if (removeReturnsNull) {
            sendWithoutAcc(bytes, sizeLocation);
            return null;
        } else
            // get the data back from the server
            return readValue(sizeLocation);
    }


    public synchronized V put(K key, V value) {
        if (key == null)
            throw new NullPointerException();

        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();

        final long sizeLocation = (putReturnsNull) ? writeEvent(PUT_WITHOUT_ACC) : writeEventAnSkip(PUT);

        writeKey(key, local);
        writeValue(value, local);


        if (putReturnsNull) {
            sendWithoutAcc(bytes, sizeLocation);
            return null;
        } else
            // get the data back from the server
            return readValue(sizeLocation, local);

    }


    public synchronized <R> R mapForKey(K key, @NotNull Function<? super V, R> function) {

        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        final long sizeLocation = writeEventAnSkip(MAP_FOR_KEY);

        writeKey(key, local);
        writeObject(function);

        final Bytes reader = blockingFetch(sizeLocation);
        return (R) keyValueSerializer.readObject(reader);
    }

    @Override
    public synchronized <R> R updateForKey(K key, @NotNull Mutator<? super V, R> mutator) {

        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        final long sizeLocation = writeEventAnSkip(UPDATE_FOR_KEY);

        writeKey(key, local);
        writeObject(mutator);

        final Bytes reader = blockingFetch(sizeLocation);
        return (R) keyValueSerializer.readObject(reader);
    }


    private synchronized <R> void writeObject(@NotNull Object function) {
        long start = bytes.position();
        for (; ; ) {
            try {
                bytes.writeObject(function);
                return;
            } catch (IllegalStateException e) {

                Throwable cause = e.getCause();

                if (cause instanceof IOException && cause.getMessage().contains("Not enough available space")) {
                    LOG.debug("resizing buffer");
                    resizeBuffer(buffer.capacity() + maxEntrySize, start);

                } else
                    throw e;

            }
        }
    }


    public synchronized void putAll(Map<? extends K, ? extends V> map) {

        final long sizeLocation = putReturnsNull ? writeEvent(PUT_ALL_WITHOUT_ACC) : writeEventAnSkip(PUT_ALL);

        writeEntries(map);

        if (putReturnsNull)
            sendWithoutAcc(bytes, sizeLocation);
        else
            blockingFetch(sizeLocation);

    }


    private synchronized void sendWithoutAcc(final Bytes bytes, long sizeLocation) {
        writeSizeAt(sizeLocation);
        long timeoutTime = config.timeoutMs() + System.currentTimeMillis();
        try {
            for (; ; ) {

                if (clientChannel == null)
                    clientChannel = lazyConnect(config.timeoutMs(), config.remoteAddress());

                try {

                    send(bytes, timeoutTime);
                    return;
                } catch (java.nio.channels.ClosedChannelException | ClosedConnectionException e) {
                    checkTimeout(timeoutTime);
                    clientChannel = lazyConnect(config.timeoutMs(), config.remoteAddress());
                }
            }
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        }
    }


    private synchronized void writeEntries(Map<? extends K, ? extends V> map) {

        final int numberOfEntries = map.size();
        int numberOfEntriesReadSoFar = 0;
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();

        bytes.writeStopBit(numberOfEntries);
        assert bytes.limit() == bytes.capacity();
        for (final Map.Entry<? extends K, ? extends V> e : map.entrySet()) {

            numberOfEntriesReadSoFar++;

            long start = bytes.position();

            // putAll if a bit more complicated than the others
            // as the volume of data could cause us to have to resize our buffers
            resizeIfRequired(numberOfEntries, numberOfEntriesReadSoFar, start);

            final K key = e.getKey();

            final Class<?> keyClass = key.getClass();
            if (!kClass.isAssignableFrom(keyClass))
                throw new ClassCastException("key=" + key + " is of type=" + keyClass + " " +
                        "and should" +
                        " be of type=" + kClass);


            writeKey(key, local);

            final V value = e.getValue();

            final Class<?> valueClass = value.getClass();
            if (!vClass.isAssignableFrom(valueClass))
                throw new ClassCastException("value=" + value + " is of type=" + valueClass +
                        " and " +
                        "should  be of type=" + vClass);


            writeValue(value, local);

            final int len = (int) (bytes.position() - start);

            if (len > maxEntrySize)
                maxEntrySize = len;
        }
    }

    private void resizeIfRequired(int numberOfEntries, int numberOfEntriesReadSoFar, final long start) {

        final long remaining = bytes.remaining();

        if (remaining < maxEntrySize) {
            long estimatedRequiredSize = estimateSize(numberOfEntries, numberOfEntriesReadSoFar);
            resizeBuffer((int) (estimatedRequiredSize + maxEntrySize), start);
        }
    }

    /**
     * estimates the size based on what been completed so far
     *
     * @param numberOfEntries
     * @param numberOfEntriesReadSoFar
     */
    private long estimateSize(int numberOfEntries, int numberOfEntriesReadSoFar) {
        // we will back the size estimate on what we have so far
        final double percentageComplete = (double) numberOfEntriesReadSoFar / (double) numberOfEntries;
        return (long) ((double) bytes.position() / percentageComplete);
    }


    private void resizeBuffer(int size, long start) {

        if (LOG.isDebugEnabled())
            LOG.debug("resizing buffer to size=" + size);

        if (size < buffer.capacity())
            throw new IllegalStateException("it not possible to resize the buffer smaller");

        assert size < Integer.MAX_VALUE;

        final ByteBuffer result = ByteBuffer.allocate((int) size).order(ByteOrder.nativeOrder());

        final long bytesPosition = bytes.position();

        bytes = new ByteBufferBytes(result.slice());

        buffer.position(0);
        buffer.limit((int) bytesPosition);

        int numberOfLongs = (int) bytesPosition / 8;

        // chunk in longs first
        for (int i = 0; i < numberOfLongs; i++) {
            bytes.writeLong(buffer.getLong());
        }

        for (int i = numberOfLongs * 8; i < bytesPosition; i++) {
            bytes.writeByte(buffer.get());
        }

        buffer = result;

        assert buffer.capacity() == bytes.capacity();

        assert buffer.capacity() == size;
        assert buffer.capacity() == bytes.capacity();
        assert bytes.limit() == bytes.capacity();
        bytes.position(start);
    }


    public synchronized void clear() {

        // get the data back from the server
        blockingFetch(writeEventAnSkip(CLEAR));
    }


    @NotNull
    public Collection<V> values() {
        final long sizeLocation = writeEventAnSkip(VALUES);

        final long startTime = System.currentTimeMillis();
        final long transactionId = nextUniqueTransaction(startTime);
        final long timeoutTime = System.currentTimeMillis() + config.timeoutMs();

        // get the data back from the server
        Bytes in = blockingFetch0(sizeLocation, transactionId, startTime);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        final Collection<V> result = new ArrayList<V>();

        for (; ; ) {

            final boolean hasMoreEntries = in.readBoolean();

            // number of entries in this chunk
            final long size = in.readInt();

            for (int i = 0; i < size; i++) {
                result.add(keyValueSerializer.readValue(in, local));
            }

            if (!hasMoreEntries)
                break;

            compact(in);

            in = blockingFetchReadOnly(timeoutTime, transactionId);
        }
        return result;
    }


    @NotNull
    public synchronized Set<Map.Entry<K, V>> entrySet() {
        final long sizeLocation = writeEventAnSkip(ENTRY_SET);

        final long startTime = System.currentTimeMillis();
        final long transactionId = nextUniqueTransaction(startTime);
        final long timeoutTime = System.currentTimeMillis() + config.timeoutMs();

        // get the data back from the server
        Bytes in = blockingFetch0(sizeLocation, transactionId, startTime);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        final Map<K, V> result = new HashMap<K, V>();

        for (; ; ) {

            final boolean hasMoreEntries = in.readBoolean();

            // number of entries in this chunk
            final long size = in.readInt();

            for (int i = 0; i < size; i++) {
                final K k = keyValueSerializer.readKey(in, local);
                final V v = keyValueSerializer.readValue(in, local);
                result.put(k, v);
            }

            if (!hasMoreEntries)
                break;

            compact(in);

            in = blockingFetchReadOnly(timeoutTime, transactionId);
        }


        return result.entrySet();
    }

    private void compact(Bytes in) {
        if (in.remaining() == 0) {
            bytes.clear();
            bytes.buffer().clear();
        } else {
            buffer.compact();
        }
    }


    @NotNull
    public synchronized Set<K> keySet() {
        final long sizeLocation = writeEventAnSkip(KEY_SET);

        final long startTime = System.currentTimeMillis();
        final long transactionId = nextUniqueTransaction(startTime);
        final long timeoutTime = startTime + config.timeoutMs();

        // get the data back from the server
        Bytes in = blockingFetch0(sizeLocation, transactionId, startTime);
        final ThreadLocalCopies local = keyValueSerializer.threadLocalCopies();
        final Set<K> result = new HashSet<>();

        for (; ; ) {

            boolean hasMoreEntries = in.readBoolean();

            // number of entries in the chunk
            long size = in.readInt();

            for (int i = 0; i < size; i++) {
                result.add(keyValueSerializer.readKey(in, local));
            }

            if (!hasMoreEntries)
                break;

            compact(in);

            in = blockingFetchReadOnly(timeoutTime, transactionId);
        }

        return result;
    }

    private long writeEvent(StatelessChronicleMap.EventId event) {
        buffer.clear();
        bytes.clear();


        bytes.write((byte) event.ordinal());
        long sizeLocation = markSizeLocation();


        return sizeLocation;
    }


    /**
     * skips for the transactionid
     *
     * @param event
     * @return
     */
    private long writeEventAnSkip(EventId event) {

        long sizeLocation = writeEvent(event);

        // skips for the transaction id
        bytes.skip(SIZE_OF_TRANSACTION_ID);
        return sizeLocation;
    }


    class Entry implements Map.Entry<K, V> {

        final K key;
        final V value;

        /**
         * Creates new entry.
         */
        Entry(K k1, V v) {
            value = v;
            key = k1;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return value;
        }

        public final V setValue(V newValue) {
            V oldValue = value;
            StatelessChronicleMap.this.put((K) getKey(), (V) newValue);
            return oldValue;
        }

        public final boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            final Map.Entry e = (Map.Entry) o;
            final Object k1 = getKey();
            final Object k2 = e.getKey();
            if (k1 == k2 || (k1 != null && k1.equals(k2))) {
                Object v1 = getValue();
                Object v2 = e.getValue();
                if (v1 == v2 || (v1 != null && v1.equals(v2)))
                    return true;
            }
            return false;
        }

        public final int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^
                    (value == null ? 0 : value.hashCode());
        }

        public final String toString() {
            return getKey() + "=" + getValue();
        }

    }

    private Bytes blockingFetch(long sizeLocation) {

        try {
            long startTime = System.currentTimeMillis();
            return blockingFetchThrowable(sizeLocation, this.config.timeoutMs(),
                    nextUniqueTransaction(startTime), startTime);
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (Exception e) {
            close();
            throw e;
        }

    }


    private Bytes blockingFetch0(long sizeLocation, final long transactionId, long startTime) {

        try {
            return blockingFetchThrowable(sizeLocation, this.config.timeoutMs(), transactionId, startTime);
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (Exception e) {
            close();
            throw e;
        }

    }


    private Bytes blockingFetchThrowable(long sizeLocation, long timeOutMs, final long transactionId, final long startTime) throws IOException {

        final long timeoutTime = startTime + timeOutMs;

        for (; ; ) {

            if (clientChannel == null)
                clientChannel = lazyConnect(config.timeoutMs(), config.remoteAddress());

            try {

                if (LOG.isDebugEnabled())
                    LOG.debug("sending data with transactionId=" + transactionId);

                writeSizeAndTransactionIdAt(sizeLocation, transactionId);

                // send out all the bytes
                send(bytes, timeoutTime);

                bytes.clear();
                bytes.buffer().clear();

                return blockingFetch(timeoutTime, transactionId);

            } catch (java.nio.channels.ClosedChannelException | ClosedConnectionException e) {
                checkTimeout(timeoutTime);
                clientChannel = lazyConnect(config.timeoutMs(), config.remoteAddress());
            }
        }
    }

    private Bytes blockingFetchReadOnly(long timeoutTime, final long transactionId) {

        try {
            return blockingFetch(timeoutTime, transactionId);
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (Exception e) {
            close();
            throw e;
        }

    }


    private Bytes blockingFetch(long timeoutTime, long transactionId) throws IOException {

        // the number of bytes in the response
        final int size = receive(SIZE_OF_SIZE, timeoutTime).readInt();

        final int requiredSize = size + SIZE_OF_SIZE;

        if (bytes.capacity() < requiredSize) {

            long pos = bytes.position();
            long limit = bytes.position();
            bytes.position(limit);
            resizeBuffer(requiredSize, pos);

        } else
            bytes.limit(bytes.capacity());

        // block until we have received all the byte in this chunk
        receive(size, timeoutTime);

        final boolean isException = bytes.readBoolean();
        final long inTransactionId = bytes.readLong();

        if (inTransactionId != transactionId)
            throw new IllegalStateException("the received transaction-id=" + inTransactionId +
                    ", does not match the expected transaction-id=" + transactionId);

        if (isException) {
            Throwable throwable = (Throwable) bytes.readObject();
            try {
                Field stackTrace = Throwable.class.getDeclaredField("stackTrace");
                stackTrace.setAccessible(true);
                List<StackTraceElement> stes = new ArrayList<>(Arrays.asList((StackTraceElement[]) stackTrace.get(throwable)));
                // prune the end of the stack.
                for (int i = stes.size() - 1; i > 0 && stes.get(i).getClassName().startsWith("Thread"); i--) {
                    stes.remove(i);
                }
                InetSocketAddress address = config.remoteAddress();
                stes.add(new StackTraceElement("~ remote", "tcp ~", address.getHostName(), address.getPort()));
                StackTraceElement[] stackTrace2 = Thread.currentThread().getStackTrace();
                for (int i = 4; i < stackTrace2.length; i++)
                    stes.add(stackTrace2[i]);
                stackTrace.set(throwable, stes.toArray(new StackTraceElement[stes.size()]));
            } catch (Exception ignore) {
            }
            NativeBytes.UNSAFE.throwException(throwable);
        }

        return bytes;
    }


    private Bytes receive(int requiredNumberOfBytes, long timeoutTime) throws IOException {

        while (bytes.buffer().position() < requiredNumberOfBytes) {

            assert requiredNumberOfBytes <= bytes.capacity();

            int len = clientChannel.read(bytes.buffer());

            if (len == -1)
                throw new IORuntimeException("Disconnected to remote server");

            checkTimeout(timeoutTime);
        }

        bytes.limit(bytes.buffer().position());
        return bytes;
    }

    private void send(final Bytes out, long timeoutTime) throws IOException {

        buffer.limit((int) out.position());
        buffer.position(0);

        while (buffer.remaining() > 0) {
            clientChannel.write(buffer);
            checkTimeout(timeoutTime);
        }

        out.clear();
        buffer.clear();
    }


    private void checkTimeout(long timeoutTime) {
        if (timeoutTime < System.currentTimeMillis())
            throw new RemoteCallTimeoutException();
    }

    private void writeSizeAndTransactionIdAt(long locationOfSize, final long transactionId) {
        final long size = bytes.position() - locationOfSize;
        final long pos = bytes.position();
        bytes.position(locationOfSize);
        bytes.writeInt((int) size - SIZE_OF_SIZE);
        bytes.writeLong(transactionId);
        bytes.position(pos);
    }


    private void writeSizeAt(long locationOfSize) {
        final long size = bytes.position() - locationOfSize;
        bytes.writeInt(locationOfSize, (int) size - SIZE_OF_SIZE);
    }


    private long markSizeLocation() {
        final long position = bytes.position();
        bytes.skip(SIZE_OF_SIZE);
        return position;
    }


    private void writeKey(K key) {
        writeKey(key, null);
    }


    private void writeKey(K key, ThreadLocalCopies local) {
        long start = bytes.position();

        for (; ; ) {
            try {
                keyValueSerializer.writeKey(key, bytes, local);
                return;
            } catch (IndexOutOfBoundsException e) {
                resizeBuffer((int) (bytes.capacity() + maxEntrySize), start);
            } catch (IllegalArgumentException e) {
                resizeToMessage(start, e);
            } catch (IllegalStateException e) {
                if (e.getMessage().contains("Not enough available space"))
                    resizeBuffer((int) (bytes.capacity() + maxEntrySize), start);
                else
                    throw e;
            }
        }
    }

    private V readValue(final long sizeLocation) {
        return keyValueSerializer.readValue(blockingFetch(sizeLocation), null);
    }

    private V readValue(final long sizeLocation, ThreadLocalCopies local) {
        return keyValueSerializer.readValue(blockingFetch(sizeLocation), local);
    }

    private void writeValue(V value) {
        writeValue(value, null);
    }

    private void writeValue(V value, ThreadLocalCopies local) {


        long start = bytes.position();
        for (; ; ) {
            try {
                assert bytes.position() == start;
                bytes.limit(bytes.capacity());
                keyValueSerializer.writeValue(value, bytes, local);

                return;
            } catch (IllegalArgumentException e) {
                resizeToMessage(start, e);


            } catch (IllegalStateException e) {
                if (e.getMessage().contains("Not enough available space"))
                    resizeBuffer((int) (bytes.capacity() + maxEntrySize), start);
                else
                    throw e;

            }
        }


    }

    private void resizeToMessage(long start, IllegalArgumentException e) {
        String message = e.getMessage();
        if (message.startsWith(START_OF)) {
            String substring = message.substring("Attempt to write ".length(), message.length());
            int i = substring.indexOf(' ');
            if (i != -1) {
                int size = Integer.parseInt(substring.substring(0, i));

                long requiresExtra = size - bytes.remaining();
                System.out.println(size + substring);
                resizeBuffer((int) (bytes.capacity() + requiresExtra), start);
            } else
                resizeBuffer((int) (bytes.capacity() + maxEntrySize), start);
        } else
            throw e;
    }
}
