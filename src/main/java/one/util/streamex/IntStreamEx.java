/*
 * Copyright 2015 Tagir Valeev
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
package one.util.streamex;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.Spliterator;
import java.util.Spliterators.AbstractIntSpliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.LongPredicate;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static one.util.streamex.StreamExInternals.*;

/**
 * An {@link IntStream} implementation with additional functionality
 * 
 * @author Tagir Valeev
 */
public class IntStreamEx implements IntStream {
    private static final class TDOfInt extends AbstractIntSpliterator implements IntConsumer {
        private final IntPredicate predicate;
        private final boolean drop;
        private boolean checked;
        private final Spliterator.OfInt source;
        private int cur;

        TDOfInt(Spliterator.OfInt source, boolean drop, IntPredicate predicate) {
            super(source.estimateSize(), source.characteristics()
                & (ORDERED | SORTED | CONCURRENT | IMMUTABLE | NONNULL | DISTINCT));
            this.drop = drop;
            this.predicate = predicate;
            this.source = source;
        }

        @Override
        public Comparator<? super Integer> getComparator() {
            return source.getComparator();
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            if (drop) {
                if (checked)
                    return source.tryAdvance(action);
                while (source.tryAdvance(this)) {
                    if (!predicate.test(cur)) {
                        checked = true;
                        action.accept(cur);
                        return true;
                    }
                }
                return false;
            }
            if (!checked && source.tryAdvance(this) && predicate.test(cur)) {
                action.accept(cur);
                return true;
            }
            checked = true;
            return false;
        }

        @Override
        public void accept(int t) {
            this.cur = t;
        }
    }

    final IntStream stream;

    IntStreamEx(IntStream stream) {
        this.stream = stream;
    }

    StreamFactory strategy() {
        return StreamFactory.DEFAULT;
    }

    final IntStreamEx delegate(Spliterator.OfInt spliterator) {
        return strategy().newIntStreamEx(
            StreamSupport.intStream(spliterator, stream.isParallel()).onClose(stream::close));
    }

    final IntStreamEx callWhile(IntPredicate predicate, int methodId) {
        try {
            return strategy().newIntStreamEx(
                (IntStream) JDK9_METHODS[IDX_INT_STREAM][methodId].invokeExact(stream, predicate));
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    <A> A collectSized(Supplier<A> supplier, ObjIntConsumer<A> accumulator, BiConsumer<A, A> combiner,
            IntFunction<A> sizedSupplier, ObjIntConsumer<A> sizedAccumulator) {
        if (isParallel())
            return collect(supplier, accumulator, combiner);
        java.util.Spliterator.OfInt spliterator = stream.spliterator();
        long size = spliterator.getExactSizeIfKnown();
        A intermediate;
        if (size >= 0 && size <= Integer.MAX_VALUE) {
            intermediate = sizedSupplier.apply((int) size);
            spliterator.forEachRemaining((IntConsumer) i -> sizedAccumulator.accept(intermediate, i));
        } else {
            intermediate = supplier.get();
            spliterator.forEachRemaining((IntConsumer) i -> accumulator.accept(intermediate, i));
        }
        return intermediate;
    }

    @Override
    public boolean isParallel() {
        return stream.isParallel();
    }

    @Override
    public IntStreamEx unordered() {
        return strategy().newIntStreamEx(stream.unordered());
    }

    @Override
    public IntStreamEx onClose(Runnable closeHandler) {
        return strategy().newIntStreamEx(stream.onClose(closeHandler));
    }

    @Override
    public void close() {
        stream.close();
    }

    @Override
    public IntStreamEx filter(IntPredicate predicate) {
        return strategy().newIntStreamEx(stream.filter(predicate));
    }

    /**
     * Returns a stream consisting of the elements of this stream that don't
     * match the given predicate.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param predicate
     *            a non-interfering, stateless predicate to apply to each
     *            element to determine if it should be excluded
     * @return the new stream
     */
    public IntStreamEx remove(IntPredicate predicate) {
        return filter(predicate.negate());
    }

    /**
     * Returns true if this stream contains the specified value.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     * 
     * @param value
     *            the value too look for in the stream
     * @return true if this stream contains the specified value
     * @see IntStream#anyMatch(IntPredicate)
     */
    public boolean has(int value) {
        return anyMatch(x -> x == value);
    }

    /**
     * Returns a stream consisting of the elements of this stream that don't
     * equal to the given value.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param value
     *            the value to remove from the stream.
     * @return the new stream
     * @since 0.2.2
     */
    public IntStreamEx without(int value) {
        return filter(val -> val != value);
    }

    /**
     * Returns a stream consisting of the elements of this stream that strictly
     * greater than the specified value.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param value
     *            a value to compare to
     * @return the new stream
     * @since 0.2.3
     */
    public IntStreamEx greater(int value) {
        return filter(val -> val > value);
    }

    /**
     * Returns a stream consisting of the elements of this stream that greater
     * than or equal to the specified value.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param value
     *            a value to compare to
     * @return the new stream
     * @since 0.2.3
     */
    public IntStreamEx atLeast(int value) {
        return filter(val -> val >= value);
    }

    /**
     * Returns a stream consisting of the elements of this stream that strictly
     * less than the specified value.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param value
     *            a value to compare to
     * @return the new stream
     * @since 0.2.3
     */
    public IntStreamEx less(int value) {
        return filter(val -> val < value);
    }

    /**
     * Returns a stream consisting of the elements of this stream that less than
     * or equal to the specified value.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param value
     *            a value to compare to
     * @return the new stream
     * @since 0.2.3
     */
    public IntStreamEx atMost(int value) {
        return filter(val -> val <= value);
    }

    @Override
    public IntStreamEx map(IntUnaryOperator mapper) {
        return strategy().newIntStreamEx(stream.map(mapper));
    }

    @Override
    public <U> StreamEx<U> mapToObj(IntFunction<? extends U> mapper) {
        return strategy().newStreamEx(stream.mapToObj(mapper));
    }

    @Override
    public LongStreamEx mapToLong(IntToLongFunction mapper) {
        return strategy().newLongStreamEx(stream.mapToLong(mapper));
    }

    @Override
    public DoubleStreamEx mapToDouble(IntToDoubleFunction mapper) {
        return strategy().newDoubleStreamEx(stream.mapToDouble(mapper));
    }

    /**
     * Returns an {@link EntryStream} consisting of the {@link Entry} objects
     * which keys and values are results of applying the given functions to the
     * elements of this stream.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param <K>
     *            The {@code Entry} key type
     * @param <V>
     *            The {@code Entry} value type
     * @param keyMapper
     *            a non-interfering, stateless function to apply to each element
     * @param valueMapper
     *            a non-interfering, stateless function to apply to each element
     * @return the new stream
     * @since 0.3.1
     */
    public <K, V> EntryStream<K, V> mapToEntry(IntFunction<? extends K> keyMapper, IntFunction<? extends V> valueMapper) {
        return strategy().newEntryStream(
            stream.mapToObj(t -> new AbstractMap.SimpleImmutableEntry<>(keyMapper.apply(t), valueMapper.apply(t))));
    }

    @Override
    public IntStreamEx flatMap(IntFunction<? extends IntStream> mapper) {
        return strategy().newIntStreamEx(stream.flatMap(mapper));
    }

    /**
     * Returns a {@link LongStreamEx} consisting of the results of replacing
     * each element of this stream with the contents of a mapped stream produced
     * by applying the provided mapping function to each element. Each mapped
     * stream is closed after its contents have been placed into this stream.
     * (If a mapped stream is {@code null} an empty stream is used, instead.)
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param mapper
     *            a non-interfering, stateless function to apply to each element
     *            which produces a {@code LongStream} of new values
     * @return the new stream
     * @since 0.3.0
     */
    public LongStreamEx flatMapToLong(IntFunction<? extends LongStream> mapper) {
        return strategy().newLongStreamEx(stream.mapToObj(mapper).flatMapToLong(Function.identity()));
    }

    /**
     * Returns a {@link DoubleStreamEx} consisting of the results of replacing
     * each element of this stream with the contents of a mapped stream produced
     * by applying the provided mapping function to each element. Each mapped
     * stream is closed after its contents have been placed into this stream.
     * (If a mapped stream is {@code null} an empty stream is used, instead.)
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param mapper
     *            a non-interfering, stateless function to apply to each element
     *            which produces a {@code DoubleStream} of new values
     * @return the new stream
     * @since 0.3.0
     */
    public DoubleStreamEx flatMapToDouble(IntFunction<? extends DoubleStream> mapper) {
        return strategy().newDoubleStreamEx(stream.mapToObj(mapper).flatMapToDouble(Function.identity()));
    }

    /**
     * Returns a {@link StreamEx} consisting of the results of replacing each
     * element of this stream with the contents of a mapped stream produced by
     * applying the provided mapping function to each element. Each mapped
     * stream is closed after its contents have been placed into this stream.
     * (If a mapped stream is {@code null} an empty stream is used, instead.)
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param <R>
     *            The element type of the new stream
     * @param mapper
     *            a non-interfering, stateless function to apply to each element
     *            which produces a {@code Stream} of new values
     * @return the new stream
     * @since 0.3.0
     */
    public <R> StreamEx<R> flatMapToObj(IntFunction<? extends Stream<R>> mapper) {
        return strategy().newStreamEx(stream.mapToObj(mapper).flatMap(Function.identity()));
    }

    @Override
    public IntStreamEx distinct() {
        return strategy().newIntStreamEx(stream.distinct());
    }

    @Override
    public IntStreamEx sorted() {
        return strategy().newIntStreamEx(stream.sorted());
    }

    /**
     * Returns a stream consisting of the elements of this stream sorted
     * according to the given comparator. Stream elements are boxed before
     * passing to the comparator.
     *
     * <p>
     * For ordered streams, the sort is stable. For unordered streams, no
     * stability guarantees are made.
     *
     * <p>
     * This is a <a href="package-summary.html#StreamOps">stateful intermediate
     * operation</a>.
     *
     * @param comparator
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            {@code Comparator} to be used to compare stream elements
     * @return the new stream
     */
    public IntStreamEx sorted(Comparator<Integer> comparator) {
        return strategy().newIntStreamEx(stream.boxed().sorted(comparator).mapToInt(Integer::intValue));
    }

    /**
     * Returns a stream consisting of the elements of this stream in reverse
     * sorted order.
     *
     * <p>
     * This is a stateful intermediate operation.
     *
     * @return the new stream
     * @since 0.0.8
     */
    public IntStreamEx reverseSorted() {
        return sorted(Comparator.reverseOrder());
    }

    /**
     * Returns a stream consisting of the elements of this stream, sorted
     * according to the natural order of the keys extracted by provided
     * function.
     *
     * <p>
     * For ordered streams, the sort is stable. For unordered streams, no
     * stability guarantees are made.
     *
     * <p>
     * This is a <a href="package-summary.html#StreamOps">stateful intermediate
     * operation</a>.
     *
     * @param <V>
     *            the type of the {@code Comparable} sort key
     * @param keyExtractor
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to be used to extract sorting keys
     * @return the new stream
     */
    public <V extends Comparable<? super V>> IntStreamEx sortedBy(IntFunction<V> keyExtractor) {
        return sorted(Comparator.comparing(i -> keyExtractor.apply(i)));
    }

    /**
     * Returns a stream consisting of the elements of this stream, sorted
     * according to the int values extracted by provided function.
     *
     * <p>
     * For ordered streams, the sort is stable. For unordered streams, no
     * stability guarantees are made.
     *
     * <p>
     * This is a <a href="package-summary.html#StreamOps">stateful intermediate
     * operation</a>.
     *
     * @param keyExtractor
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to be used to extract sorting keys
     * @return the new stream
     */
    public IntStreamEx sortedByInt(IntUnaryOperator keyExtractor) {
        return sorted(Comparator.comparingInt(i -> keyExtractor.applyAsInt(i)));
    }

    /**
     * Returns a stream consisting of the elements of this stream, sorted
     * according to the long values extracted by provided function.
     *
     * <p>
     * For ordered streams, the sort is stable. For unordered streams, no
     * stability guarantees are made.
     *
     * <p>
     * This is a <a href="package-summary.html#StreamOps">stateful intermediate
     * operation</a>.
     *
     * @param keyExtractor
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to be used to extract sorting keys
     * @return the new stream
     */
    public IntStreamEx sortedByLong(IntToLongFunction keyExtractor) {
        return sorted(Comparator.comparingLong(i -> keyExtractor.applyAsLong(i)));
    }

    /**
     * Returns a stream consisting of the elements of this stream, sorted
     * according to the double values extracted by provided function.
     *
     * <p>
     * For ordered streams, the sort is stable. For unordered streams, no
     * stability guarantees are made.
     *
     * <p>
     * This is a <a href="package-summary.html#StreamOps">stateful intermediate
     * operation</a>.
     *
     * @param keyExtractor
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to be used to extract sorting keys
     * @return the new stream
     */
    public IntStreamEx sortedByDouble(IntToDoubleFunction keyExtractor) {
        return sorted(Comparator.comparingDouble(i -> keyExtractor.applyAsDouble(i)));
    }

    @Override
    public IntStreamEx peek(IntConsumer action) {
        return strategy().newIntStreamEx(stream.peek(action));
    }

    @Override
    public IntStreamEx limit(long maxSize) {
        return strategy().newIntStreamEx(stream.limit(maxSize));
    }

    @Override
    public IntStreamEx skip(long n) {
        return strategy().newIntStreamEx(stream.skip(n));
    }

    /**
     * Returns a stream consisting of the remaining elements of this stream
     * after discarding the first {@code n} elements of the stream. If this
     * stream contains fewer than {@code n} elements then an empty stream will
     * be returned.
     *
     * <p>
     * This is a stateful quasi-intermediate operation. Unlike
     * {@link #skip(long)} it skips the first elements even if the stream is
     * unordered. The main purpose of this method is to workaround the problem
     * of skipping the first elements from non-sized source with further
     * parallel processing and unordered terminal operation (such as
     * {@link #forEach(IntConsumer)}). This problem was fixed in OracleJDK 8u60.
     * 
     * <p>
     * Also it behaves much better with infinite streams processed in parallel.
     * For example,
     * {@code IntStreamEx.iterate(0, i->i+1).skip(1).limit(100).parallel().toArray()}
     * will likely to fail with {@code OutOfMemoryError}, but will work nicely
     * if {@code skip} is replaced with {@code skipOrdered}.
     *
     * <p>
     * For sequential streams this method behaves exactly like
     * {@link #skip(long)}.
     *
     * @param n
     *            the number of leading elements to skip
     * @return the new stream
     * @throws IllegalArgumentException
     *             if {@code n} is negative
     * @see #skip(long)
     * @since 0.3.2
     */
    public IntStreamEx skipOrdered(long n) {
        Spliterator.OfInt spliterator = (stream.isParallel() ? StreamSupport.intStream(stream.spliterator(), false)
                : stream).skip(n).spliterator();
        return delegate(spliterator);
    }

    @Override
    public void forEach(IntConsumer action) {
        stream.forEach(action);
    }

    @Override
    public void forEachOrdered(IntConsumer action) {
        stream.forEachOrdered(action);
    }

    @Override
    public int[] toArray() {
        return stream.toArray();
    }

    /**
     * Returns a {@code byte[]} array containing the elements of this stream
     * which are converted to bytes using {@code (byte)} cast operation.
     *
     * <p>
     * This is a terminal operation.
     *
     * @return an array containing the elements of this stream
     * @since 0.3.0
     */
    public byte[] toByteArray() {
        return collectSized(ByteBuffer::new, ByteBuffer::add, ByteBuffer::addAll, ByteBuffer::new,
            ByteBuffer::addUnsafe).toArray();
    }

    /**
     * Returns a {@code char[]} array containing the elements of this stream
     * which are converted to chars using {@code (char)} cast operation.
     *
     * <p>
     * This is a terminal operation.
     *
     * @return an array containing the elements of this stream
     * @since 0.3.0
     */
    public char[] toCharArray() {
        return collectSized(CharBuffer::new, CharBuffer::add, CharBuffer::addAll, CharBuffer::new,
            CharBuffer::addUnsafe).toArray();
    }

    /**
     * Returns a {@code short[]} array containing the elements of this stream
     * which are converted to shorts using {@code (short)} cast operation.
     *
     * <p>
     * This is a terminal operation.
     *
     * @return an array containing the elements of this stream
     * @since 0.3.0
     */
    public short[] toShortArray() {
        return collectSized(ShortBuffer::new, ShortBuffer::add, ShortBuffer::addAll, ShortBuffer::new,
            ShortBuffer::addUnsafe).toArray();
    }

    /**
     * Returns a {@link BitSet} containing the elements of this stream.
     *
     * <p>
     * This is a terminal operation.
     *
     * @return a {@code BitSet} which set bits correspond to the elements of
     *         this stream.
     * @since 0.2.0
     */
    public BitSet toBitSet() {
        return collect(BitSet::new, BitSet::set, BitSet::or);
    }

    @Override
    public int reduce(int identity, IntBinaryOperator op) {
        return stream.reduce(identity, op);
    }

    @Override
    public OptionalInt reduce(IntBinaryOperator op) {
        return stream.reduce(op);
    }

    /**
     * Folds the elements of this stream using the provided identity object and
     * accumulation function, going left to right. This is equivalent to:
     * 
     * <pre>
     * {@code
     *     int result = identity;
     *     for (int element : this stream)
     *         result = accumulator.apply(result, element)
     *     return result;
     * }
     * </pre>
     *
     * <p>
     * This is a terminal operation.
     * 
     * <p>
     * This method may work slowly on parallel streams as it must process
     * elements strictly left to right. If your accumulator function is
     * associative, consider using {@link #reduce(int, IntBinaryOperator)}
     * method.
     * 
     * <p>
     * For parallel stream it's not guaranteed that accumulator will always be
     * executed in the same thread.
     *
     * @param identity
     *            the identity value
     * @param accumulator
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function for incorporating an additional element into a result
     * @return the result of the folding
     * @see #reduce(int, IntBinaryOperator)
     * @see #foldLeft(IntBinaryOperator)
     * @since 0.4.0
     */
    public int foldLeft(int identity, IntBinaryOperator accumulator) {
        int[] box = new int[] { identity };
        stream.forEachOrdered(t -> box[0] = accumulator.applyAsInt(box[0], t));
        return box[0];
    }

    /**
     * Folds the elements of this stream using the provided accumulation
     * function, going left to right. This is equivalent to:
     * 
     * <pre>
     * {@code
     *     boolean foundAny = false;
     *     int result = 0;
     *     for (int element : this stream) {
     *         if (!foundAny) {
     *             foundAny = true;
     *             result = element;
     *         }
     *         else
     *             result = accumulator.apply(result, element);
     *     }
     *     return foundAny ? OptionalInt.of(result) : OptionalInt.empty();
     * }
     * </pre>
     * 
     * <p>
     * This is a terminal operation.
     * 
     * <p>
     * This method may work slowly on parallel streams as it must process
     * elements strictly left to right. If your accumulator function is
     * associative, consider using {@link #reduce(IntBinaryOperator)} method.
     * 
     * <p>
     * For parallel stream it's not guaranteed that accumulator will always be
     * executed in the same thread.
     *
     * @param accumulator
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function for incorporating an additional element into a result
     * @return the result of the folding
     * @see #foldLeft(int, IntBinaryOperator)
     * @see #reduce(IntBinaryOperator)
     * @since 0.4.0
     */
    public OptionalInt foldLeft(IntBinaryOperator accumulator) {
        PrimitiveBox b = new PrimitiveBox();
        stream.forEachOrdered(t -> {
            if (b.b)
                b.i = accumulator.applyAsInt(b.i, t);
            else {
                b.i = t;
                b.b = true;
            }
        });
        return b.asInt();
    }

    /**
     * {@inheritDoc}
     * 
     * @see #collect(IntCollector)
     */
    @Override
    public <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        return stream.collect(supplier, accumulator, combiner);
    }

    /**
     * Performs a mutable reduction operation on the elements of this stream
     * using an {@link IntCollector} which encapsulates the supplier,
     * accumulator and merger functions making easier to reuse collection
     * strategies.
     *
     * <p>
     * Like {@link #reduce(int, IntBinaryOperator)}, {@code collect} operations
     * can be parallelized without requiring additional synchronization.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param <A>
     *            the intermediate accumulation type of the {@code IntCollector}
     * @param <R>
     *            type of the result
     * @param collector
     *            the {@code IntCollector} describing the reduction
     * @return the result of the reduction
     * @see #collect(Supplier, ObjIntConsumer, BiConsumer)
     * @since 0.3.0
     */
    @SuppressWarnings("unchecked")
    public <A, R> R collect(IntCollector<A, R> collector) {
        if (collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH))
            return (R) collect(collector.supplier(), collector.intAccumulator(), collector.merger());
        return collector.finisher()
                .apply(collect(collector.supplier(), collector.intAccumulator(), collector.merger()));
    }

    @Override
    public int sum() {
        return reduce(0, Integer::sum);
    }

    @Override
    public OptionalInt min() {
        return reduce(Integer::min);
    }

    /**
     * Returns the minimum element of this stream according to the provided
     * {@code Comparator}.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param comparator
     *            a non-interfering, stateless {@link Comparator} to compare
     *            elements of this stream
     * @return an {@code OptionalInt} describing the minimum element of this
     *         stream, or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt min(Comparator<Integer> comparator) {
        return reduce((a, b) -> comparator.compare(a, b) > 0 ? b : a);
    }

    /**
     * Returns the minimum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param <V>
     *            the type of the {@code Comparable} sort key
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing some element of this stream for
     *         which the lowest value was returned by key extractor, or an empty
     *         {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public <V extends Comparable<? super V>> OptionalInt minBy(IntFunction<V> keyExtractor) {
        ObjIntBox<V> result = collect(() -> new ObjIntBox<>(null, 0), (box, i) -> {
            V val = Objects.requireNonNull(keyExtractor.apply(i));
            if (box.a == null || box.a.compareTo(val) > 0) {
                box.a = val;
                box.b = i;
            }
        }, (box1, box2) -> {
            if (box2.a != null && (box1.a == null || box1.a.compareTo(box2.a) > 0)) {
                box1.a = box2.a;
                box1.b = box2.b;
            }
        });
        return result.a == null ? OptionalInt.empty() : OptionalInt.of(result.b);
    }

    /**
     * Returns the minimum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing the first element of this
     *         stream for which the lowest value was returned by key extractor,
     *         or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt minByInt(IntUnaryOperator keyExtractor) {
        int[] result = collect(() -> new int[3], (acc, i) -> {
            int key = keyExtractor.applyAsInt(i);
            if (acc[2] == 0 || acc[1] > key) {
                acc[0] = i;
                acc[1] = key;
                acc[2] = 1;
            }
        }, (acc1, acc2) -> {
            if (acc2[2] == 1 && (acc1[2] == 0 || acc1[1] > acc2[1]))
                System.arraycopy(acc2, 0, acc1, 0, 3);
        });
        return result[2] == 1 ? OptionalInt.of(result[0]) : OptionalInt.empty();
    }

    /**
     * Returns the minimum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing some element of this stream for
     *         which the lowest value was returned by key extractor, or an empty
     *         {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt minByLong(IntToLongFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, i) -> {
            long key = keyExtractor.applyAsLong(i);
            if (!box.b || box.l > key) {
                box.b = true;
                box.l = key;
                box.i = i;
            }
        }, PrimitiveBox.MIN_LONG).asInt();
    }

    /**
     * Returns the minimum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing some element of this stream for
     *         which the lowest value was returned by key extractor, or an empty
     *         {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt minByDouble(IntToDoubleFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, i) -> {
            double key = keyExtractor.applyAsDouble(i);
            if (!box.b || Double.compare(box.d, key) > 0) {
                box.b = true;
                box.d = key;
                box.i = i;
            }
        }, PrimitiveBox.MIN_DOUBLE).asInt();
    }

    @Override
    public OptionalInt max() {
        return reduce(Integer::max);
    }

    /**
     * Returns the maximum element of this stream according to the provided
     * {@code Comparator}.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param comparator
     *            a non-interfering, stateless {@link Comparator} to compare
     *            elements of this stream
     * @return an {@code OptionalInt} describing the maximum element of this
     *         stream, or an empty {@code OptionalInt} if the stream is empty
     */
    public OptionalInt max(Comparator<Integer> comparator) {
        return reduce((a, b) -> comparator.compare(a, b) >= 0 ? a : b);
    }

    /**
     * Returns the maximum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param <V>
     *            the type of the {@code Comparable} sort key
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public <V extends Comparable<? super V>> OptionalInt maxBy(IntFunction<V> keyExtractor) {
        ObjIntBox<V> result = collect(() -> new ObjIntBox<>(null, 0), (box, i) -> {
            V val = Objects.requireNonNull(keyExtractor.apply(i));
            if (box.a == null || box.a.compareTo(val) < 0) {
                box.a = val;
                box.b = i;
            }
        }, (box1, box2) -> {
            if (box2.a != null && (box1.a == null || box1.a.compareTo(box2.a) < 0)) {
                box1.a = box2.a;
                box1.b = box2.b;
            }
        });
        return result.a == null ? OptionalInt.empty() : OptionalInt.of(result.b);
    }

    /**
     * Returns the maximum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt maxByInt(IntUnaryOperator keyExtractor) {
        int[] result = collect(() -> new int[3], (acc, i) -> {
            int key = keyExtractor.applyAsInt(i);
            if (acc[2] == 0 || acc[1] < key) {
                acc[0] = i;
                acc[1] = key;
                acc[2] = 1;
            }
        }, (acc1, acc2) -> {
            if (acc2[2] == 1 && (acc1[2] == 0 || acc1[1] < acc2[1]))
                System.arraycopy(acc2, 0, acc1, 0, 3);
        });
        return result[2] == 1 ? OptionalInt.of(result[0]) : OptionalInt.empty();
    }

    /**
     * Returns the maximum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt maxByLong(IntToLongFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, i) -> {
            long key = keyExtractor.applyAsLong(i);
            if (!box.b || box.l < key) {
                box.b = true;
                box.l = key;
                box.i = i;
            }
        }, PrimitiveBox.MAX_LONG).asInt();
    }

    /**
     * Returns the maximum element of this stream according to the provided key
     * extractor function.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param keyExtractor
     *            a non-interfering, stateless function
     * @return an {@code OptionalInt} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalInt} if the stream is empty
     * @since 0.1.2
     */
    public OptionalInt maxByDouble(IntToDoubleFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, i) -> {
            double key = keyExtractor.applyAsDouble(i);
            if (!box.b || Double.compare(box.d, key) < 0) {
                box.b = true;
                box.d = key;
                box.i = i;
            }
        }, PrimitiveBox.MAX_DOUBLE).asInt();
    }

    @Override
    public long count() {
        return stream.count();
    }

    @Override
    public OptionalDouble average() {
        return stream.average();
    }

    @Override
    public IntSummaryStatistics summaryStatistics() {
        return collect(IntSummaryStatistics::new, IntSummaryStatistics::accept, IntSummaryStatistics::combine);
    }

    @Override
    public boolean anyMatch(IntPredicate predicate) {
        return stream.anyMatch(predicate);
    }

    @Override
    public boolean allMatch(IntPredicate predicate) {
        return stream.allMatch(predicate);
    }

    @Override
    public boolean noneMatch(IntPredicate predicate) {
        return !anyMatch(predicate);
    }

    @Override
    public OptionalInt findFirst() {
        return stream.findFirst();
    }

    /**
     * Returns an {@link OptionalInt} describing the first element of this
     * stream, which matches given predicate, or an empty {@code OptionalInt} if
     * there's no matching element.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     *
     * @param predicate
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            predicate which returned value should match
     * @return an {@code OptionalInt} describing the first matching element of
     *         this stream, or an empty {@code OptionalInt} if there's no
     *         matching element
     * @see #findFirst()
     */
    public OptionalInt findFirst(IntPredicate predicate) {
        return filter(predicate).findFirst();
    }

    @Override
    public OptionalInt findAny() {
        return stream.findAny();
    }

    /**
     * Returns an {@link OptionalInt} describing some element of the stream,
     * which matches given predicate, or an empty {@code OptionalInt} if there's
     * no matching element.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     *
     * <p>
     * The behavior of this operation is explicitly nondeterministic; it is free
     * to select any element in the stream. This is to allow for maximal
     * performance in parallel operations; the cost is that multiple invocations
     * on the same source may not return the same result. (If a stable result is
     * desired, use {@link #findFirst(IntPredicate)} instead.)
     *
     * @param predicate
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            predicate which returned value should match
     * @return an {@code OptionalInt} describing some matching element of this
     *         stream, or an empty {@code OptionalInt} if there's no matching
     *         element
     * @see #findAny()
     * @see #findFirst(IntPredicate)
     */
    public OptionalInt findAny(IntPredicate predicate) {
        return filter(predicate).findAny();
    }

    /**
     * Returns an {@link OptionalLong} describing the zero-based index of the
     * first element of this stream, which equals to the given value, or an
     * empty {@code OptionalLong} if there's no matching element.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     *
     * @param value
     *            a value to look for
     * @return an {@code OptionalLong} describing the index of the first
     *         matching element of this stream, or an empty {@code OptionalLong}
     *         if there's no matching element.
     * @see #indexOf(IntPredicate)
     * @since 0.4.0
     */
    public OptionalLong indexOf(int value) {
        return boxed().indexOf(i -> i == value);
    }

    /**
     * Returns an {@link OptionalLong} describing the zero-based index of the
     * first element of this stream, which matches given predicate, or an empty
     * {@code OptionalLong} if there's no matching element.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     *
     * @param predicate
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            predicate which returned value should match
     * @return an {@code OptionalLong} describing the index of the first
     *         matching element of this stream, or an empty {@code OptionalLong}
     *         if there's no matching element.
     * @see #findFirst(IntPredicate)
     * @since 0.4.0
     */
    public OptionalLong indexOf(IntPredicate predicate) {
        return boxed().indexOf(predicate::test);
    }

    @Override
    public LongStreamEx asLongStream() {
        return strategy().newLongStreamEx(stream.asLongStream());
    }

    @Override
    public DoubleStreamEx asDoubleStream() {
        return strategy().newDoubleStreamEx(stream.asDoubleStream());
    }

    @Override
    public StreamEx<Integer> boxed() {
        return strategy().newStreamEx(stream.boxed());
    }

    @Override
    public IntStreamEx sequential() {
        return StreamFactory.DEFAULT.newIntStreamEx(stream.sequential());
    }

    /**
     * {@inheritDoc}
     * 
     * <p>
     * If this stream was created using {@link #parallel(ForkJoinPool)}, the new
     * stream forgets about supplied custom {@link ForkJoinPool} and its
     * terminal operation will be executed in common pool.
     */
    @Override
    public IntStreamEx parallel() {
        return StreamFactory.DEFAULT.newIntStreamEx(stream.parallel());
    }

    /**
     * Returns an equivalent stream that is parallel and bound to the supplied
     * {@link ForkJoinPool}.
     *
     * <p>
     * This is an intermediate operation.
     * 
     * <p>
     * The terminal operation of this stream or any derived stream (except the
     * streams created via {@link #parallel()} or {@link #sequential()} methods)
     * will be executed inside the supplied {@code ForkJoinPool}. If current
     * thread does not belong to that pool, it will wait till calculation
     * finishes.
     *
     * @param fjp
     *            a {@code ForkJoinPool} to submit the stream operation to.
     * @return a parallel stream bound to the supplied {@code ForkJoinPool}
     * @since 0.2.0
     */
    public IntStreamEx parallel(ForkJoinPool fjp) {
        return StreamFactory.forCustomPool(fjp).newIntStreamEx(stream.parallel());
    }

    @Override
    public OfInt iterator() {
        return stream.iterator();
    }

    @Override
    public java.util.Spliterator.OfInt spliterator() {
        return stream.spliterator();
    }

    /**
     * Returns a new {@code IntStreamEx} which is a concatenation of this stream
     * and the stream containing supplied values
     * 
     * @param values
     *            the values to append to the stream
     * @return the new stream
     */
    public IntStreamEx append(int... values) {
        if (values.length == 0)
            return this;
        return strategy().newIntStreamEx(IntStream.concat(stream, IntStream.of(values)));
    }

    /**
     * Creates a lazily concatenated stream whose elements are all the elements
     * of this stream followed by all the elements of the other stream. The
     * resulting stream is ordered if both of the input streams are ordered, and
     * parallel if either of the input streams is parallel. When the resulting
     * stream is closed, the close handlers for both input streams are invoked.
     *
     * @param other
     *            the other stream
     * @return this stream appended by the other stream
     * @see IntStream#concat(IntStream, IntStream)
     */
    public IntStreamEx append(IntStream other) {
        return strategy().newIntStreamEx(IntStream.concat(stream, other));
    }

    /**
     * Returns a new {@code IntStreamEx} which is a concatenation of the stream
     * containing supplied values and this stream
     * 
     * @param values
     *            the values to prepend to the stream
     * @return the new stream
     */
    public IntStreamEx prepend(int... values) {
        if (values.length == 0)
            return this;
        return strategy().newIntStreamEx(IntStream.concat(IntStream.of(values), stream));
    }

    /**
     * Creates a lazily concatenated stream whose elements are all the elements
     * of the other stream followed by all the elements of this stream. The
     * resulting stream is ordered if both of the input streams are ordered, and
     * parallel if either of the input streams is parallel. When the resulting
     * stream is closed, the close handlers for both input streams are invoked.
     *
     * @param other
     *            the other stream
     * @return this stream prepended by the other stream
     * @see IntStream#concat(IntStream, IntStream)
     */
    public IntStreamEx prepend(IntStream other) {
        return strategy().newIntStreamEx(IntStream.concat(other, stream));
    }

    /**
     * Returns an object-valued {@link StreamEx} consisting of the elements of
     * given array corresponding to the indices which appear in this stream.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param <U>
     *            the element type of the new stream
     * @param array
     *            the array to take the elements from
     * @return the new stream
     * @since 0.1.2
     */
    public <U> StreamEx<U> elements(U[] array) {
        return mapToObj(idx -> array[idx]);
    }

    /**
     * Returns an object-valued {@link StreamEx} consisting of the elements of
     * given {@link List} corresponding to the indices which appear in this
     * stream.
     *
     * <p>
     * The list elements are accessed using {@link List#get(int)}, so the list
     * should provide fast random access. The list is assumed to be unmodifiable
     * during the stream operations.
     * 
     * <p>
     * This is an intermediate operation.
     *
     * @param <U>
     *            the element type of the new stream
     * @param list
     *            the list to take the elements from
     * @return the new stream
     * @since 0.1.2
     */
    public <U> StreamEx<U> elements(List<U> list) {
        return mapToObj(list::get);
    }

    /**
     * Returns an {@link IntStreamEx} consisting of the elements of given array
     * corresponding to the indices which appear in this stream.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param array
     *            the array to take the elements from
     * @return the new stream
     * @since 0.1.2
     */
    public IntStreamEx elements(int[] array) {
        return map(idx -> array[idx]);
    }

    /**
     * Returns a {@link LongStreamEx} consisting of the elements of given array
     * corresponding to the indices which appear in this stream.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param array
     *            the array to take the elements from
     * @return the new stream
     * @since 0.1.2
     */
    public LongStreamEx elements(long[] array) {
        return mapToLong(idx -> array[idx]);
    }

    /**
     * Returns a {@link DoubleStreamEx} consisting of the elements of given
     * array corresponding to the indices which appear in this stream.
     *
     * <p>
     * This is an intermediate operation.
     *
     * @param array
     *            the array to take the elements from
     * @return the new stream
     * @since 0.1.2
     */
    public DoubleStreamEx elements(double[] array) {
        return mapToDouble(idx -> array[idx]);
    }

    /**
     * Returns a {@link String} consisting of chars from this stream.
     * 
     * <p>
     * This is a terminal operation.
     * 
     * <p>
     * During string creation stream elements are converted to chars using
     * {@code (char)} cast operation.
     * 
     * @return a new {@code String}
     * @since 0.2.1
     */
    public String charsToString() {
        return collect(StringBuilder::new, (sb, c) -> sb.append((char) c), StringBuilder::append).toString();
    }

    /**
     * Returns a {@link String} consisting of code points from this stream.
     * 
     * <p>
     * This is a terminal operation.
     * 
     * @return a new {@code String}
     * @since 0.2.1
     */
    public String codePointsToString() {
        return collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    /**
     * Returns a stream consisting of the results of applying the given function
     * to the every adjacent pair of elements of this stream.
     *
     * <p>
     * This is a quasi-intermediate operation.
     * 
     * <p>
     * The output stream will contain one element less than this stream. If this
     * stream contains zero or one element the output stream will be empty.
     *
     * @param mapper
     *            a non-interfering, stateless function to apply to each
     *            adjacent pair of this stream elements.
     * @return the new stream
     * @since 0.2.1
     */
    public IntStreamEx pairMap(IntBinaryOperator mapper) {
        return delegate(new PairSpliterator.PSOfInt(mapper, stream.spliterator()));
    }

    /**
     * Returns a {@link String} which contains the results of calling
     * {@link String#valueOf(int)} on each element of this stream, separated by
     * the specified delimiter, in encounter order.
     *
     * <p>
     * This is a terminal operation.
     * 
     * @param delimiter
     *            the delimiter to be used between each element
     * @return a {@code String}. For empty input stream empty String is
     *         returned.
     * @since 0.3.1
     */
    public String joining(CharSequence delimiter) {
        return collect(IntCollector.joining(delimiter));
    }

    /**
     * Returns a {@link String} which contains the results of calling
     * {@link String#valueOf(int)} on each element of this stream, separated by
     * the specified delimiter, with the specified prefix and suffix in
     * encounter order.
     *
     * <p>
     * This is a terminal operation.
     * 
     * @param delimiter
     *            the delimiter to be used between each element
     * @param prefix
     *            the sequence of characters to be used at the beginning of the
     *            joined result
     * @param suffix
     *            the sequence of characters to be used at the end of the joined
     *            result
     * @return a {@code String}. For empty input stream empty String is
     *         returned.
     * @since 0.3.1
     */
    public String joining(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        return collect(IntCollector.joining(delimiter, prefix, suffix));
    }

    /**
     * Returns a stream consisting of all elements from this stream until the
     * first element which does not match the given predicate is found.
     * 
     * <p>
     * This is a short-circuiting stateful operation. It can be either <a
     * href="package-summary.html#StreamOps">intermediate or
     * quasi-intermediate</a>. When using with JDK 1.9 or higher it calls the
     * corresponding JDK 1.9 implementation. When using with JDK 1.8 it uses own
     * implementation.
     * 
     * <p>
     * While this operation is quite cheap for sequential stream, it can be
     * quite expensive on parallel pipelines.
     * 
     * @param predicate
     *            a non-interfering, stateless predicate to apply to elements.
     * @return the new stream.
     * @since 0.3.6
     */
    public IntStreamEx takeWhile(IntPredicate predicate) {
        Objects.requireNonNull(predicate);
        if (JDK9_METHODS != null) {
            return callWhile(predicate, IDX_TAKE_WHILE);
        }
        return delegate(new IntStreamEx.TDOfInt(stream.spliterator(), false, predicate));
    }

    /**
     * Returns a stream consisting of all elements from this stream starting
     * from the first element which does not match the given predicate. If the
     * predicate is true for all stream elements, an empty stream is returned.
     * 
     * <p>
     * This is a stateful operation. It can be either <a
     * href="package-summary.html#StreamOps">intermediate or
     * quasi-intermediate</a>. When using with JDK 1.9 or higher it calls the
     * corresponding JDK 1.9 implementation. When using with JDK 1.8 it uses own
     * implementation.
     * 
     * <p>
     * While this operation is quite cheap for sequential stream, it can be
     * quite expensive on parallel pipelines.
     * 
     * @param predicate
     *            a non-interfering, stateless predicate to apply to elements.
     * @return the new stream.
     * @since 0.3.6
     */
    public IntStreamEx dropWhile(IntPredicate predicate) {
        Objects.requireNonNull(predicate);
        if (JDK9_METHODS != null) {
            return callWhile(predicate, IDX_DROP_WHILE);
        }
        return delegate(new IntStreamEx.TDOfInt(stream.spliterator(), true, predicate));
    }

    /**
     * Returns a stream where the first element is the replaced with the result
     * of applying the given function while the other elements are left intact.
     *
     * <p>
     * This is an <a href="package-summary.html#StreamOps">quasi-intermediate
     * operation</a>.
     *
     * @param mapper
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to apply to the first element
     * @return the new stream
     * @since 0.4.1
     */
    public IntStreamEx mapFirst(IntUnaryOperator mapper) {
        return mapToObj(Integer::new).mapFirst(mapper::applyAsInt).mapToInt(Integer::intValue);
    }

    /**
     * Returns a stream where the last element is the replaced with the result
     * of applying the given function while the other elements are left intact.
     *
     * <p>
     * This is an <a href="package-summary.html#StreamOps">quasi-intermediate
     * operation</a>.
     *
     * @param mapper
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            function to apply to the first element
     * @return the new stream
     * @since 0.4.1
     */
    public IntStreamEx mapLast(IntUnaryOperator mapper) {
        return mapToObj(Integer::new).mapLast(mapper::applyAsInt).mapToInt(Integer::intValue);
    }

    /**
     * Returns an empty sequential {@code IntStreamEx}.
     *
     * @return an empty sequential stream
     */
    public static IntStreamEx empty() {
        return of(IntStream.empty());
    }

    /**
     * Returns a sequential {@code IntStreamEx} containing a single element.
     *
     * @param element
     *            the single element
     * @return a singleton sequential stream
     */
    public static IntStreamEx of(int element) {
        return of(IntStream.of(element));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * specified values.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     */
    public static IntStreamEx of(int... elements) {
        return of(IntStream.of(elements));
    }

    /**
     * Returns a sequential {@link IntStreamEx} with the specified range of the
     * specified array as its source.
     *
     * @param array
     *            the array, assumed to be unmodified during use
     * @param startInclusive
     *            the first index to cover, inclusive
     * @param endExclusive
     *            index immediately past the last index to cover
     * @return an {@code IntStreamEx} for the array range
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code startInclusive} is negative, {@code endExclusive}
     *             is less than {@code startInclusive}, or {@code endExclusive}
     *             is greater than the array size
     * @since 0.1.1
     * @see Arrays#stream(int[], int, int)
     */
    public static IntStreamEx of(int[] array, int startInclusive, int endExclusive) {
        return of(Arrays.stream(array, startInclusive, endExclusive));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * specified values casted to int.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     * @since 0.2.0
     */
    public static IntStreamEx of(byte... elements) {
        return of(elements, 0, elements.length);
    }

    /**
     * Returns a sequential {@link IntStreamEx} with the specified range of the
     * specified array as its source. Array values will be casted to int.
     *
     * @param array
     *            the array, assumed to be unmodified during use
     * @param startInclusive
     *            the first index to cover, inclusive
     * @param endExclusive
     *            index immediately past the last index to cover
     * @return an {@code IntStreamEx} for the array range
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code startInclusive} is negative, {@code endExclusive}
     *             is less than {@code startInclusive}, or {@code endExclusive}
     *             is greater than the array size
     * @since 0.2.0
     */
    public static IntStreamEx of(byte[] array, int startInclusive, int endExclusive) {
        rangeCheck(array.length, startInclusive, endExclusive);
        return of(new RangeBasedSpliterator.OfByte(startInclusive, endExclusive, array));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * specified values casted to int.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     * @since 0.2.0
     */
    public static IntStreamEx of(char... elements) {
        return of(elements, 0, elements.length);
    }

    /**
     * Returns a sequential {@link IntStreamEx} with the specified range of the
     * specified array as its source. Array values will be casted to int.
     *
     * @param array
     *            the array, assumed to be unmodified during use
     * @param startInclusive
     *            the first index to cover, inclusive
     * @param endExclusive
     *            index immediately past the last index to cover
     * @return an {@code IntStreamEx} for the array range
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code startInclusive} is negative, {@code endExclusive}
     *             is less than {@code startInclusive}, or {@code endExclusive}
     *             is greater than the array size
     * @since 0.2.0
     */
    public static IntStreamEx of(char[] array, int startInclusive, int endExclusive) {
        rangeCheck(array.length, startInclusive, endExclusive);
        return of(new RangeBasedSpliterator.OfChar(startInclusive, endExclusive, array));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * specified values casted to int.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     * @since 0.2.0
     */
    public static IntStreamEx of(short... elements) {
        return of(elements, 0, elements.length);
    }

    /**
     * Returns a sequential {@link IntStreamEx} with the specified range of the
     * specified array as its source. Array values will be casted to int.
     *
     * @param array
     *            the array, assumed to be unmodified during use
     * @param startInclusive
     *            the first index to cover, inclusive
     * @param endExclusive
     *            index immediately past the last index to cover
     * @return an {@code IntStreamEx} for the array range
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code startInclusive} is negative, {@code endExclusive}
     *             is less than {@code startInclusive}, or {@code endExclusive}
     *             is greater than the array size
     * @since 0.2.0
     */
    public static IntStreamEx of(short[] array, int startInclusive, int endExclusive) {
        rangeCheck(array.length, startInclusive, endExclusive);
        return of(new RangeBasedSpliterator.OfShort(startInclusive, endExclusive, array));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * unboxed elements of supplied array.
     *
     * @param array
     *            the array to create the stream from.
     * @return the new stream
     * @see Arrays#stream(Object[])
     * @since 0.5.0
     */
    public static IntStreamEx of(Integer[] array) {
        return of(Arrays.stream(array).mapToInt(Integer::intValue));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied list.
     *
     * @param <T>
     *            list element type
     * @param list
     *            list to get the stream of its indices
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements starting from 0 to (not inclusive) list.size()
     * @since 0.1.1
     */
    public static <T> IntStreamEx ofIndices(List<T> list) {
        return range(list.size());
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied list elements which match given predicate.
     * 
     * <p>
     * The list elements are accessed using {@link List#get(int)}, so the list
     * should provide fast random access. The list is assumed to be unmodifiable
     * during the stream operations.
     *
     * @param <T>
     *            list element type
     * @param list
     *            list to get the stream of its indices
     * @param predicate
     *            a predicate to test list elements
     * @return a sequential {@code IntStreamEx} of the matched list indices
     * @since 0.1.1
     */
    public static <T> IntStreamEx ofIndices(List<T> list, Predicate<T> predicate) {
        return of(IntStream.range(0, list.size()).filter(i -> predicate.test(list.get(i))));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied array.
     *
     * @param <T>
     *            array element type
     * @param array
     *            array to get the stream of its indices
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements starting from 0 to (not inclusive) array.length
     * @since 0.1.1
     */
    public static <T> IntStreamEx ofIndices(T[] array) {
        return range(array.length);
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied array elements which match given predicate.
     *
     * @param <T>
     *            array element type
     * @param array
     *            array to get the stream of its indices
     * @param predicate
     *            a predicate to test array elements
     * @return a sequential {@code IntStreamEx} of the matched array indices
     * @since 0.1.1
     */
    public static <T> IntStreamEx ofIndices(T[] array, Predicate<T> predicate) {
        return of(IntStream.range(0, array.length).filter(i -> predicate.test(array[i])));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of supplied array.
     *
     * @param array
     *            array to get the stream of its indices
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements starting from 0 to (not inclusive) array.length
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(int[] array) {
        return range(array.length);
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied array elements which match given predicate.
     *
     * @param array
     *            array to get the stream of its indices
     * @param predicate
     *            a predicate to test array elements
     * @return a sequential {@code IntStreamEx} of the matched array indices
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(int[] array, IntPredicate predicate) {
        return of(IntStream.range(0, array.length).filter(i -> predicate.test(array[i])));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of supplied array.
     *
     * @param array
     *            array to get the stream of its indices
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements starting from 0 to (not inclusive) array.length
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(long[] array) {
        return range(array.length);
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied array elements which match given predicate.
     *
     * @param array
     *            array to get the stream of its indices
     * @param predicate
     *            a predicate to test array elements
     * @return a sequential {@code IntStreamEx} of the matched array indices
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(long[] array, LongPredicate predicate) {
        return of(IntStream.range(0, array.length).filter(i -> predicate.test(array[i])));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of supplied array.
     *
     * @param array
     *            array to get the stream of its indices
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements starting from 0 to (not inclusive) array.length
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(double[] array) {
        return range(array.length);
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} containing all the
     * indices of the supplied array elements which match given predicate.
     *
     * @param array
     *            array to get the stream of its indices
     * @param predicate
     *            a predicate to test array elements
     * @return a sequential {@code IntStreamEx} of the matched array indices
     * @since 0.1.1
     */
    public static IntStreamEx ofIndices(double[] array, DoublePredicate predicate) {
        return of(IntStream.range(0, array.length).filter(i -> predicate.test(array[i])));
    }

    /**
     * Returns an {@code IntStreamEx} object which wraps given {@link IntStream}
     * 
     * @param stream
     *            original stream
     * @return the wrapped stream
     * @since 0.0.8
     */
    public static IntStreamEx of(IntStream stream) {
        return stream instanceof IntStreamEx ? (IntStreamEx) stream : new IntStreamEx(stream);
    }

    /**
     * Returns a sequential {@link IntStreamEx} created from given
     * {@link java.util.Spliterator.OfInt}.
     * 
     * @param spliterator
     *            a spliterator to create the stream from.
     * @return the new stream
     * @since 0.3.4
     */
    public static IntStreamEx of(Spliterator.OfInt spliterator) {
        return of(StreamSupport.intStream(spliterator, false));
    }

    /**
     * Returns a sequential {@code IntStreamEx} containing an
     * {@link OptionalInt} value, if present, otherwise returns an empty
     * {@code IntStreamEx}.
     *
     * @param optional
     *            the optional to create a stream of
     * @return a stream with an {@code OptionalInt} value if present, otherwise
     *         an empty stream
     * @since 0.1.1
     */
    public static IntStreamEx of(OptionalInt optional) {
        return optional.isPresent() ? of(optional.getAsInt()) : empty();
    }

    /**
     * Returns an {@code IntStreamEx} of indices for which the specified
     * {@link BitSet} contains a bit in the set state. The indices are returned
     * in order, from lowest to highest. The size of the stream is the number of
     * bits in the set state, equal to the value returned by the
     * {@link BitSet#cardinality()} method.
     *
     * <p>
     * The bit set must remain constant during the execution of the terminal
     * stream operation. Otherwise, the result of the terminal stream operation
     * is undefined.
     *
     * @param bitSet
     *            a {@link BitSet} to produce the stream from
     * @return a stream of integers representing set indices
     * @see BitSet#stream()
     */
    public static IntStreamEx of(BitSet bitSet) {
        return of(bitSet.stream());
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} whose elements are the
     * unboxed elements of supplied collection.
     *
     * @param collection
     *            the collection to create the stream from.
     * @return the new stream
     * @see Collection#stream()
     */
    public static IntStreamEx of(Collection<Integer> collection) {
        return of(collection.stream().mapToInt(Integer::intValue));
    }

    /**
     * Returns an effectively unlimited stream of pseudorandom {@code int}
     * values produced by given {@link Random} object.
     *
     * <p>
     * A pseudorandom {@code int} value is generated as if it's the result of
     * calling the method {@link Random#nextInt()}.
     *
     * @param random
     *            a {@link Random} object to produce the stream from
     * @return a stream of pseudorandom {@code int} values
     * @see Random#ints()
     */
    public static IntStreamEx of(Random random) {
        return of(random.ints());
    }

    /**
     * Returns a stream producing the given {@code streamSize} number of
     * pseudorandom {@code int} values.
     *
     * <p>
     * A pseudorandom {@code int} value is generated as if it's the result of
     * calling the method {@link Random#nextInt()}.
     *
     * @param random
     *            a {@link Random} object to produce the stream from
     * @param streamSize
     *            the number of values to generate
     * @return a stream of pseudorandom {@code int} values
     * @see Random#ints(long)
     */
    public static IntStreamEx of(Random random, long streamSize) {
        return of(random.ints(streamSize));
    }

    /**
     * Returns an effectively unlimited stream of pseudorandom {@code int}
     * values, each conforming to the given origin (inclusive) and bound
     * (exclusive).
     *
     * @param random
     *            a {@link Random} object to produce the stream from
     * @param randomNumberOrigin
     *            the origin (inclusive) of each random value
     * @param randomNumberBound
     *            the bound (exclusive) of each random value
     * @return a stream of pseudorandom {@code int} values
     * @see Random#ints(long, int, int)
     */
    public static IntStreamEx of(Random random, int randomNumberOrigin, int randomNumberBound) {
        return of(random.ints(randomNumberOrigin, randomNumberBound));
    }

    /**
     * Returns a stream producing the given {@code streamSize} number of
     * pseudorandom {@code int} values, each conforming to the given origin
     * (inclusive) and bound (exclusive).
     *
     * @param random
     *            a {@link Random} object to produce the stream from
     * @param streamSize
     *            the number of values to generate
     * @param randomNumberOrigin
     *            the origin (inclusive) of each random value
     * @param randomNumberBound
     *            the bound (exclusive) of each random value
     * @return a stream of pseudorandom {@code int} values
     * @see Random#ints(long, int, int)
     */
    public static IntStreamEx of(Random random, long streamSize, int randomNumberOrigin, int randomNumberBound) {
        return of(random.ints(streamSize, randomNumberOrigin, randomNumberBound));
    }

    /**
     * Returns an {@code IntStreamEx} of {@code int} zero-extending the
     * {@code char} values from the supplied {@link CharSequence}. Any char
     * which maps to a surrogate code point is passed through uninterpreted.
     *
     * <p>
     * If the sequence is mutated while the stream is being read, the result is
     * undefined.
     *
     * @param seq
     *            sequence to read characters from
     * @return an IntStreamEx of char values from the sequence
     * @see CharSequence#chars()
     */
    public static IntStreamEx ofChars(CharSequence seq) {
        // In JDK 8 there's only default chars() method which uses
        // IteratorSpliterator
        // In JDK 9 chars() method for most of implementations is much better
        return of(IS_JDK9 ? seq.chars() : java.nio.CharBuffer.wrap(seq).chars());
    }

    /**
     * Returns an {@code IntStreamEx} of code point values from the supplied
     * {@link CharSequence}. Any surrogate pairs encountered in the sequence are
     * combined as if by {@linkplain Character#toCodePoint
     * Character.toCodePoint} and the result is passed to the stream. Any other
     * code units, including ordinary BMP characters, unpaired surrogates, and
     * undefined code units, are zero-extended to {@code int} values which are
     * then passed to the stream.
     *
     * <p>
     * If the sequence is mutated while the stream is being read, the result is
     * undefined.
     *
     * @param seq
     *            sequence to read code points from
     * @return an IntStreamEx of Unicode code points from this sequence
     * @see CharSequence#codePoints()
     */
    public static IntStreamEx ofCodePoints(CharSequence seq) {
        return of(seq.codePoints());
    }

    /**
     * Returns an infinite sequential ordered {@code IntStreamEx} produced by
     * iterative application of a function {@code f} to an initial element
     * {@code seed}, producing a stream consisting of {@code seed},
     * {@code f(seed)}, {@code f(f(seed))}, etc.
     *
     * <p>
     * The first element (position {@code 0}) in the {@code IntStreamEx} will be
     * the provided {@code seed}. For {@code n > 0}, the element at position
     * {@code n}, will be the result of applying the function {@code f} to the
     * element at position {@code n - 1}.
     *
     * @param seed
     *            the initial element
     * @param f
     *            a function to be applied to to the previous element to produce
     *            a new element
     * @return A new sequential {@code IntStream}
     * @see IntStream#iterate(int, IntUnaryOperator)
     */
    public static IntStreamEx iterate(final int seed, final IntUnaryOperator f) {
        return of(IntStream.iterate(seed, f));
    }

    /**
     * Returns an infinite sequential unordered stream where each element is
     * generated by the provided {@code IntSupplier}. This is suitable for
     * generating constant streams, streams of random elements, etc.
     *
     * @param s
     *            the {@code IntSupplier} for generated elements
     * @return a new infinite sequential unordered {@code IntStreamEx}
     * @see IntStream#generate(IntSupplier)
     */
    public static IntStreamEx generate(IntSupplier s) {
        return of(IntStream.generate(s));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} from 0 (inclusive) to
     * {@code endExclusive} (exclusive) by an incremental step of {@code 1}.
     *
     * @param endExclusive
     *            the exclusive upper bound
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements
     * @see #range(int, int)
     * @since 0.1.1
     */
    public static IntStreamEx range(int endExclusive) {
        return of(IntStream.range(0, endExclusive));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} from
     * {@code startInclusive} (inclusive) to {@code endExclusive} (exclusive) by
     * an incremental step of {@code 1}.
     *
     * @param startInclusive
     *            the (inclusive) initial value
     * @param endExclusive
     *            the exclusive upper bound
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements
     * @see IntStream#range(int, int)
     */
    public static IntStreamEx range(int startInclusive, int endExclusive) {
        return of(IntStream.range(startInclusive, endExclusive));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} from
     * {@code startInclusive} (inclusive) to {@code endExclusive} (exclusive) by
     * the specified incremental step. The negative step values are also
     * supported. In this case the {@code startInclusive} should be greater than
     * {@code endExclusive}.
     *
     * @param startInclusive
     *            the (inclusive) initial value
     * @param endExclusive
     *            the exclusive upper bound
     * @param step
     *            the non-zero value which designates the difference between the
     *            consecutive values of the resulting stream.
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements
     * @throws IllegalArgumentException
     *             if step is zero
     * @see IntStreamEx#range(int, int)
     * @since 0.4.0
     */
    public static IntStreamEx range(int startInclusive, int endExclusive, int step) {
        int endInclusive = endExclusive - Integer.signum(step);
        if (endInclusive > endExclusive && step > 0 || endInclusive < endExclusive && step < 0)
            return empty();
        return rangeClosed(startInclusive, endInclusive, step);
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} from
     * {@code startInclusive} (inclusive) to {@code endInclusive} (inclusive) by
     * an incremental step of {@code 1}.
     *
     * @param startInclusive
     *            the (inclusive) initial value
     * @param endInclusive
     *            the inclusive upper bound
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements
     * @see IntStream#rangeClosed(int, int)
     */
    public static IntStreamEx rangeClosed(int startInclusive, int endInclusive) {
        return of(IntStream.rangeClosed(startInclusive, endInclusive));
    }

    /**
     * Returns a sequential ordered {@code IntStreamEx} from
     * {@code startInclusive} (inclusive) to {@code endInclusive} (inclusive) by
     * the specified incremental step. The negative step values are also
     * supported. In this case the {@code startInclusive} should be greater than
     * {@code endInclusive}.
     * 
     * <p>
     * Note that depending on the step value the {@code endInclusive} bound may
     * still not be reached. For example
     * {@code IntStreamEx.rangeClosed(0, 5, 2)} will yield the stream of three
     * numbers: 0, 2 and 4.
     *
     * @param startInclusive
     *            the (inclusive) initial value
     * @param endInclusive
     *            the inclusive upper bound
     * @param step
     *            the non-zero value which designates the difference between the
     *            consecutive values of the resulting stream.
     * @return a sequential {@code IntStreamEx} for the range of {@code int}
     *         elements
     * @throws IllegalArgumentException
     *             if step is zero
     * @see IntStreamEx#rangeClosed(int, int)
     * @since 0.4.0
     */
    public static IntStreamEx rangeClosed(int startInclusive, int endInclusive, int step) {
        if (step == 0)
            throw new IllegalArgumentException("step = 0");
        if (step == 1)
            return of(IntStream.rangeClosed(startInclusive, endInclusive));
        if (step == -1) {
            // Handled specially as number of elements can exceed
            // Integer.MAX_VALUE
            int sum = endInclusive + startInclusive;
            return of(IntStream.rangeClosed(endInclusive, startInclusive).map(x -> sum - x));
        }
        if (endInclusive > startInclusive ^ step > 0)
            return empty();
        int limit = (endInclusive - startInclusive) * Integer.signum(step);
        limit = Integer.divideUnsigned(limit, Math.abs(step));
        return of(IntStream.rangeClosed(0, limit).map(x -> x * step + startInclusive));
    }

    /**
     * Returns a sequential unordered {@code IntStreamEx} of given length which
     * elements are equal to supplied value.
     * 
     * @param value
     *            the constant value
     * @param length
     *            the length of the stream
     * @return a new {@code IntStreamEx}
     * @since 0.1.2
     */
    public static IntStreamEx constant(int value, long length) {
        return of(new ConstSpliterator.OfInt(value, length));
    }

    /**
     * Returns a sequential {@code IntStreamEx} containing the results of
     * applying the given function to the corresponding pairs of values in given
     * two arrays.
     * 
     * @param first
     *            the first array
     * @param second
     *            the second array
     * @param mapper
     *            a non-interfering, stateless function to apply to each pair of
     *            the corresponding array elements.
     * @return a new {@code IntStreamEx}
     * @throws IllegalArgumentException
     *             if length of the arrays differs.
     * @since 0.2.1
     */
    public static IntStreamEx zip(int[] first, int[] second, IntBinaryOperator mapper) {
        return of(new RangeBasedSpliterator.ZipInt(0, checkLength(first.length, second.length), mapper, first, second));
    }
}
