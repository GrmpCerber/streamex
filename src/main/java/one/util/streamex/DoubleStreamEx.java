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
import java.util.Collection;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Spliterator;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfDouble;
import java.util.Spliterators.AbstractDoubleSpliterator;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoublePredicate;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleToLongFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static one.util.streamex.StreamExInternals.*;

/**
 * A {@link DoubleStream} implementation with additional functionality
 * 
 * @author Tagir Valeev
 */
public class DoubleStreamEx implements DoubleStream {
    private static final class TDOfDouble extends AbstractDoubleSpliterator implements DoubleConsumer {
        private final DoublePredicate predicate;
        private final boolean drop;
        private boolean checked;
        private final Spliterator.OfDouble source;
        private double cur;

        TDOfDouble(Spliterator.OfDouble source, boolean drop, DoublePredicate predicate) {
            super(source.estimateSize(), source.characteristics()
                & (ORDERED | SORTED | CONCURRENT | IMMUTABLE | NONNULL | DISTINCT));
            this.drop = drop;
            this.predicate = predicate;
            this.source = source;
        }

        @Override
        public Comparator<? super Double> getComparator() {
            return source.getComparator();
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
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
        public void accept(double t) {
            this.cur = t;
        }
    }

    final DoubleStream stream;

    DoubleStreamEx(DoubleStream stream) {
        this.stream = stream;
    }

    StreamFactory strategy() {
        return StreamFactory.DEFAULT;
    }

    final DoubleStreamEx delegate(Spliterator.OfDouble spliterator) {
        return strategy().newDoubleStreamEx(
            StreamSupport.doubleStream(spliterator, stream.isParallel()).onClose(stream::close));
    }

    final DoubleStreamEx callWhile(DoublePredicate predicate, int methodId) {
        try {
            return strategy().newDoubleStreamEx(
                (DoubleStream) JDK9_METHODS[IDX_DOUBLE_STREAM][methodId].invokeExact(stream, predicate));
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new InternalError(e);
        }
    }

    @Override
    public boolean isParallel() {
        return stream.isParallel();
    }

    @Override
    public DoubleStreamEx unordered() {
        return strategy().newDoubleStreamEx(stream.unordered());
    }

    @Override
    public DoubleStreamEx onClose(Runnable closeHandler) {
        return strategy().newDoubleStreamEx(stream.onClose(closeHandler));
    }

    @Override
    public void close() {
        stream.close();
    }

    @Override
    public DoubleStreamEx filter(DoublePredicate predicate) {
        return strategy().newDoubleStreamEx(stream.filter(predicate));
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
    public DoubleStreamEx remove(DoublePredicate predicate) {
        return filter(predicate.negate());
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
    public DoubleStreamEx greater(double value) {
        return filter(val -> val > value);
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
    public DoubleStreamEx less(double value) {
        return filter(val -> val < value);
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
    public DoubleStreamEx atLeast(double value) {
        return filter(val -> val >= value);
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
    public DoubleStreamEx atMost(double value) {
        return filter(val -> val <= value);
    }

    @Override
    public DoubleStreamEx map(DoubleUnaryOperator mapper) {
        return strategy().newDoubleStreamEx(stream.map(mapper));
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
    public DoubleStreamEx mapFirst(DoubleUnaryOperator mapper) {
        return boxed().mapFirst(mapper::applyAsDouble).mapToDouble(Double::doubleValue);
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
    public DoubleStreamEx mapLast(DoubleUnaryOperator mapper) {
        return boxed().mapLast(mapper::applyAsDouble).mapToDouble(Double::doubleValue);
    }

    @Override
    public <U> StreamEx<U> mapToObj(DoubleFunction<? extends U> mapper) {
        return strategy().newStreamEx(stream.mapToObj(mapper));
    }

    @Override
    public IntStreamEx mapToInt(DoubleToIntFunction mapper) {
        return strategy().newIntStreamEx(stream.mapToInt(mapper));
    }

    @Override
    public LongStreamEx mapToLong(DoubleToLongFunction mapper) {
        return strategy().newLongStreamEx(stream.mapToLong(mapper));
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
    public <K, V> EntryStream<K, V> mapToEntry(DoubleFunction<? extends K> keyMapper,
            DoubleFunction<? extends V> valueMapper) {
        return strategy().newEntryStream(
            stream.mapToObj(t -> new AbstractMap.SimpleImmutableEntry<>(keyMapper.apply(t), valueMapper.apply(t))));
    }

    @Override
    public DoubleStreamEx flatMap(DoubleFunction<? extends DoubleStream> mapper) {
        return strategy().newDoubleStreamEx(stream.flatMap(mapper));
    }

    /**
     * Returns an {@link IntStreamEx} consisting of the results of replacing
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
     *            which produces an {@code IntStream} of new values
     * @return the new stream
     * @since 0.3.0
     */
    public IntStreamEx flatMapToInt(DoubleFunction<? extends IntStream> mapper) {
        return strategy().newIntStreamEx(stream.mapToObj(mapper).flatMapToInt(Function.identity()));
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
    public LongStreamEx flatMapToLong(DoubleFunction<? extends LongStream> mapper) {
        return strategy().newLongStreamEx(stream.mapToObj(mapper).flatMapToLong(Function.identity()));
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
    public <R> StreamEx<R> flatMapToObj(DoubleFunction<? extends Stream<R>> mapper) {
        return strategy().newStreamEx(stream.mapToObj(mapper).flatMap(Function.identity()));
    }

    @Override
    public DoubleStreamEx distinct() {
        return strategy().newDoubleStreamEx(stream.distinct());
    }

    @Override
    public DoubleStreamEx sorted() {
        return strategy().newDoubleStreamEx(stream.sorted());
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
    public DoubleStreamEx sorted(Comparator<Double> comparator) {
        return strategy().newDoubleStreamEx(stream.boxed().sorted(comparator).mapToDouble(Double::doubleValue));
    }

    /**
     * Returns a stream consisting of the elements of this stream in reverse
     * sorted order. The elements are compared for equality according to
     * {@link java.lang.Double#compare(double, double)}.
     *
     * <p>
     * This is a stateful intermediate operation.
     *
     * @return the new stream
     * @since 0.0.8
     */
    public DoubleStreamEx reverseSorted() {
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
    public <V extends Comparable<? super V>> DoubleStreamEx sortedBy(DoubleFunction<V> keyExtractor) {
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
    public DoubleStreamEx sortedByInt(DoubleToIntFunction keyExtractor) {
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
    public DoubleStreamEx sortedByLong(DoubleToLongFunction keyExtractor) {
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
    public DoubleStreamEx sortedByDouble(DoubleUnaryOperator keyExtractor) {
        return sorted(Comparator.comparingDouble(i -> keyExtractor.applyAsDouble(i)));
    }

    @Override
    public DoubleStreamEx peek(DoubleConsumer action) {
        return strategy().newDoubleStreamEx(stream.peek(action));
    }

    @Override
    public DoubleStreamEx limit(long maxSize) {
        return strategy().newDoubleStreamEx(stream.limit(maxSize));
    }

    @Override
    public DoubleStreamEx skip(long n) {
        return strategy().newDoubleStreamEx(stream.skip(n));
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
     * {@link #forEach(DoubleConsumer)}). This problem was fixed in OracleJDK
     * 8u60.
     * 
     * <p>
     * Also it behaves much better with infinite streams processed in parallel.
     * For example,
     * {@code DoubleStreamEx.iterate(0.0, i->i+1).skip(1).limit(100).parallel().toArray()}
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
    public DoubleStreamEx skipOrdered(long n) {
        Spliterator.OfDouble spliterator = (stream.isParallel() ? StreamSupport.doubleStream(stream.spliterator(),
            false) : stream).skip(n).spliterator();
        return delegate(spliterator);
    }

    @Override
    public void forEach(DoubleConsumer action) {
        stream.forEach(action);
    }

    @Override
    public void forEachOrdered(DoubleConsumer action) {
        stream.forEachOrdered(action);
    }

    @Override
    public double[] toArray() {
        return stream.toArray();
    }

    /**
     * Returns a {@code float[]} array containing the elements of this stream
     * which are converted to floats using {@code (float)} cast operation.
     *
     * <p>
     * This is a terminal operation.
     *
     * @return an array containing the elements of this stream
     * @since 0.3.0
     */
    public float[] toFloatArray() {
        if (isParallel())
            return collect(DoubleCollector.toFloatArray());
        java.util.Spliterator.OfDouble spliterator = stream.spliterator();
        long size = spliterator.getExactSizeIfKnown();
        FloatBuffer buf;
        if (size >= 0 && size <= Integer.MAX_VALUE) {
            buf = new FloatBuffer((int) size);
            spliterator.forEachRemaining((DoubleConsumer) buf::addUnsafe);
        } else {
            buf = new FloatBuffer();
            spliterator.forEachRemaining((DoubleConsumer) buf::add);
        }
        return buf.toArray();
    }

    @Override
    public double reduce(double identity, DoubleBinaryOperator op) {
        return stream.reduce(identity, op);
    }

    @Override
    public OptionalDouble reduce(DoubleBinaryOperator op) {
        return stream.reduce(op);
    }

    /**
     * Folds the elements of this stream using the provided identity object and
     * accumulation function, going left to right. This is equivalent to:
     * 
     * <pre>
     * {@code
     *     double result = identity;
     *     for (double element : this stream)
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
     * associative, consider using {@link #reduce(double, DoubleBinaryOperator)}
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
     * @see #reduce(double, DoubleBinaryOperator)
     * @see #foldLeft(DoubleBinaryOperator)
     * @since 0.4.0
     */
    public double foldLeft(double identity, DoubleBinaryOperator accumulator) {
        double[] box = new double[] { identity };
        stream.forEachOrdered(t -> box[0] = accumulator.applyAsDouble(box[0], t));
        return box[0];
    }

    /**
     * Folds the elements of this stream using the provided accumulation
     * function, going left to right. This is equivalent to:
     * 
     * <pre>
     * {@code
     *     boolean foundAny = false;
     *     double result = 0;
     *     for (double element : this stream) {
     *         if (!foundAny) {
     *             foundAny = true;
     *             result = element;
     *         }
     *         else
     *             result = accumulator.apply(result, element);
     *     }
     *     return foundAny ? OptionalDouble.of(result) : OptionalDouble.empty();
     * }
     * </pre>
     * 
     * <p>
     * This is a terminal operation.
     * 
     * <p>
     * This method may work slowly on parallel streams as it must process
     * elements strictly left to right. If your accumulator function is
     * associative, consider using {@link #reduce(DoubleBinaryOperator)} method.
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
     * @see #foldLeft(double, DoubleBinaryOperator)
     * @see #reduce(DoubleBinaryOperator)
     * @since 0.4.0
     */
    public OptionalDouble foldLeft(DoubleBinaryOperator accumulator) {
        PrimitiveBox b = new PrimitiveBox();
        stream.forEachOrdered(t -> {
            if (b.b)
                b.d = accumulator.applyAsDouble(b.d, t);
            else {
                b.d = t;
                b.b = true;
            }
        });
        return b.asDouble();
    }

    /**
     * {@inheritDoc}
     * 
     * @see #collect(DoubleCollector)
     */
    @Override
    public <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        return stream.collect(supplier, accumulator, combiner);
    }

    /**
     * Performs a mutable reduction operation on the elements of this stream
     * using an {@link DoubleCollector} which encapsulates the supplier,
     * accumulator and merger functions making easier to reuse collection
     * strategies.
     *
     * <p>
     * Like {@link #reduce(double, DoubleBinaryOperator)}, {@code collect}
     * operations can be parallelized without requiring additional
     * synchronization.
     *
     * <p>
     * This is a terminal operation.
     *
     * @param <A>
     *            the intermediate accumulation type of the
     *            {@code DoubleCollector}
     * @param <R>
     *            type of the result
     * @param collector
     *            the {@code DoubleCollector} describing the reduction
     * @return the result of the reduction
     * @see #collect(Supplier, ObjDoubleConsumer, BiConsumer)
     * @since 0.3.0
     */
    @SuppressWarnings("unchecked")
    public <A, R> R collect(DoubleCollector<A, R> collector) {
        if (collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH))
            return (R) collect(collector.supplier(), collector.doubleAccumulator(), collector.merger());
        return collector.finisher().apply(
            collect(collector.supplier(), collector.doubleAccumulator(), collector.merger()));
    }

    @Override
    public double sum() {
        return stream.sum();
    }

    @Override
    public OptionalDouble min() {
        return reduce(Math::min);
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
     * @return an {@code OptionalDouble} describing the minimum element of this
     *         stream, or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble min(Comparator<Double> comparator) {
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the lowest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public <V extends Comparable<? super V>> OptionalDouble minBy(DoubleFunction<V> keyExtractor) {
        ObjDoubleBox<V> result = collect(() -> new ObjDoubleBox<>(null, 0), (box, i) -> {
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
        return result.a == null ? OptionalDouble.empty() : OptionalDouble.of(result.b);
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the lowest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble minByInt(DoubleToIntFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, d) -> {
            int key = keyExtractor.applyAsInt(d);
            if (!box.b || box.i > key) {
                box.b = true;
                box.i = key;
                box.d = d;
            }
        }, PrimitiveBox.MIN_INT).asDouble();
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the lowest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble minByLong(DoubleToLongFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, d) -> {
            long key = keyExtractor.applyAsLong(d);
            if (!box.b || box.l > key) {
                box.b = true;
                box.l = key;
                box.d = d;
            }
        }, PrimitiveBox.MIN_LONG).asDouble();
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the lowest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble minByDouble(DoubleUnaryOperator keyExtractor) {
        double[] result = collect(() -> new double[3], (acc, d) -> {
            double key = keyExtractor.applyAsDouble(d);
            if (acc[2] == 0 || Double.compare(acc[1], key) > 0) {
                acc[0] = d;
                acc[1] = key;
                acc[2] = 1;
            }
        }, (acc1, acc2) -> {
            if (acc2[2] == 1 && (acc1[2] == 0 || Double.compare(acc1[1], acc2[1]) > 0))
                System.arraycopy(acc2, 0, acc1, 0, 3);
        });
        return result[2] == 1 ? OptionalDouble.of(result[0]) : OptionalDouble.empty();
    }

    @Override
    public OptionalDouble max() {
        return reduce(Math::max);
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
     * @return an {@code OptionalDouble} describing the maximum element of this
     *         stream, or an empty {@code OptionalDouble} if the stream is empty
     */
    public OptionalDouble max(Comparator<Double> comparator) {
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public <V extends Comparable<? super V>> OptionalDouble maxBy(DoubleFunction<V> keyExtractor) {
        ObjDoubleBox<V> result = collect(() -> new ObjDoubleBox<>(null, 0), (box, i) -> {
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
        return result.a == null ? OptionalDouble.empty() : OptionalDouble.of(result.b);
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble maxByInt(DoubleToIntFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, d) -> {
            int key = keyExtractor.applyAsInt(d);
            if (!box.b || box.i < key) {
                box.b = true;
                box.i = key;
                box.d = d;
            }
        }, PrimitiveBox.MAX_INT).asDouble();
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble maxByLong(DoubleToLongFunction keyExtractor) {
        return collect(PrimitiveBox::new, (box, d) -> {
            long key = keyExtractor.applyAsLong(d);
            if (!box.b || box.l < key) {
                box.b = true;
                box.l = key;
                box.d = d;
            }
        }, PrimitiveBox.MAX_LONG).asDouble();
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
     * @return an {@code OptionalDouble} describing the first element of this
     *         stream for which the highest value was returned by key extractor,
     *         or an empty {@code OptionalDouble} if the stream is empty
     * @since 0.1.2
     */
    public OptionalDouble maxByDouble(DoubleUnaryOperator keyExtractor) {
        double[] result = collect(() -> new double[3], (acc, d) -> {
            double key = keyExtractor.applyAsDouble(d);
            if (acc[2] == 0 || Double.compare(acc[1], key) < 0) {
                acc[0] = d;
                acc[1] = key;
                acc[2] = 1;
            }
        }, (acc1, acc2) -> {
            if (acc2[2] == 1 && (acc1[2] == 0 || Double.compare(acc1[1], acc2[1]) < 0))
                System.arraycopy(acc2, 0, acc1, 0, 3);
        });
        return result[2] == 1 ? OptionalDouble.of(result[0]) : OptionalDouble.empty();
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
    public DoubleSummaryStatistics summaryStatistics() {
        return collect(DoubleSummaryStatistics::new, DoubleSummaryStatistics::accept, DoubleSummaryStatistics::combine);
    }

    @Override
    public boolean anyMatch(DoublePredicate predicate) {
        return stream.anyMatch(predicate);
    }

    @Override
    public boolean allMatch(DoublePredicate predicate) {
        return stream.allMatch(predicate);
    }

    @Override
    public boolean noneMatch(DoublePredicate predicate) {
        return !anyMatch(predicate);
    }

    @Override
    public OptionalDouble findFirst() {
        return stream.findFirst();
    }

    /**
     * Returns an {@link OptionalDouble} describing the first element of this
     * stream, which matches given predicate, or an empty {@code OptionalDouble}
     * if there's no matching element.
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
     * @return an {@code OptionalDouble} describing the first matching element
     *         of this stream, or an empty {@code OptionalDouble} if there's no
     *         matching element
     * @see #findFirst()
     */
    public OptionalDouble findFirst(DoublePredicate predicate) {
        return filter(predicate).findFirst();
    }

    @Override
    public OptionalDouble findAny() {
        return stream.findAny();
    }

    /**
     * Returns an {@link OptionalDouble} describing some element of the stream,
     * which matches given predicate, or an empty {@code OptionalDouble} if
     * there's no matching element.
     *
     * <p>
     * This is a short-circuiting terminal operation.
     *
     * <p>
     * The behavior of this operation is explicitly nondeterministic; it is free
     * to select any element in the stream. This is to allow for maximal
     * performance in parallel operations; the cost is that multiple invocations
     * on the same source may not return the same result. (If a stable result is
     * desired, use {@link #findFirst(DoublePredicate)} instead.)
     *
     * @param predicate
     *            a <a
     *            href="package-summary.html#NonInterference">non-interfering
     *            </a>, <a
     *            href="package-summary.html#Statelessness">stateless</a>
     *            predicate which returned value should match
     * @return an {@code OptionalDouble} describing some matching element of
     *         this stream, or an empty {@code OptionalDouble} if there's no
     *         matching element
     * @see #findAny()
     * @see #findFirst(DoublePredicate)
     */
    public OptionalDouble findAny(DoublePredicate predicate) {
        return filter(predicate).findAny();
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
     * @see #findFirst(DoublePredicate)
     * @since 0.4.0
     */
    public OptionalLong indexOf(DoublePredicate predicate) {
        return boxed().indexOf(predicate::test);
    }

    @Override
    public StreamEx<Double> boxed() {
        return strategy().newStreamEx(stream.boxed());
    }

    @Override
    public DoubleStreamEx sequential() {
        return StreamFactory.DEFAULT.newDoubleStreamEx(stream.sequential());
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
    public DoubleStreamEx parallel() {
        return StreamFactory.DEFAULT.newDoubleStreamEx(stream.parallel());
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
    public DoubleStreamEx parallel(ForkJoinPool fjp) {
        return StreamFactory.forCustomPool(fjp).newDoubleStreamEx(stream.parallel());
    }

    @Override
    public OfDouble iterator() {
        return stream.iterator();
    }

    @Override
    public java.util.Spliterator.OfDouble spliterator() {
        return stream.spliterator();
    }

    /**
     * Returns a new {@code DoubleStreamEx} which is a concatenation of this
     * stream and the stream containing supplied values
     * 
     * @param values
     *            the values to append to the stream
     * @return the new stream
     */
    public DoubleStreamEx append(double... values) {
        if (values.length == 0)
            return this;
        return strategy().newDoubleStreamEx(DoubleStream.concat(stream, DoubleStream.of(values)));
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
     * @see DoubleStream#concat(DoubleStream, DoubleStream)
     */
    public DoubleStreamEx append(DoubleStream other) {
        return strategy().newDoubleStreamEx(DoubleStream.concat(stream, other));
    }

    /**
     * Returns a new {@code DoubleStreamEx} which is a concatenation of the
     * stream containing supplied values and this stream
     * 
     * @param values
     *            the values to prepend to the stream
     * @return the new stream
     */
    public DoubleStreamEx prepend(double... values) {
        if (values.length == 0)
            return this;
        return strategy().newDoubleStreamEx(DoubleStream.concat(DoubleStream.of(values), stream));
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
     * @see DoubleStream#concat(DoubleStream, DoubleStream)
     */
    public DoubleStreamEx prepend(DoubleStream other) {
        return strategy().newDoubleStreamEx(DoubleStream.concat(other, stream));
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
    public DoubleStreamEx pairMap(DoubleBinaryOperator mapper) {
        return delegate(new PairSpliterator.PSOfDouble(mapper, stream.spliterator()));
    }

    /**
     * Returns a {@link String} which contains the results of calling
     * {@link String#valueOf(double)} on each element of this stream, separated
     * by the specified delimiter, in encounter order.
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
        return collect(DoubleCollector.joining(delimiter));
    }

    /**
     * Returns a {@link String} which contains the results of calling
     * {@link String#valueOf(double)} on each element of this stream, separated
     * by the specified delimiter, with the specified prefix and suffix in
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
        return collect(DoubleCollector.joining(delimiter, prefix, suffix));
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
    public DoubleStreamEx takeWhile(DoublePredicate predicate) {
        Objects.requireNonNull(predicate);
        if (JDK9_METHODS != null) {
            return callWhile(predicate, IDX_TAKE_WHILE);
        }
        return delegate(new DoubleStreamEx.TDOfDouble(stream.spliterator(), false, predicate));
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
    public DoubleStreamEx dropWhile(DoublePredicate predicate) {
        Objects.requireNonNull(predicate);
        if (JDK9_METHODS != null) {
            return callWhile(predicate, IDX_DROP_WHILE);
        }
        return delegate(new DoubleStreamEx.TDOfDouble(stream.spliterator(), true, predicate));
    }

    /**
     * Returns an empty sequential {@code DoubleStreamEx}.
     *
     * @return an empty sequential stream
     */
    public static DoubleStreamEx empty() {
        return of(DoubleStream.empty());
    }

    /**
     * Returns a sequential {@code DoubleStreamEx} containing a single element.
     *
     * @param element
     *            the single element
     * @return a singleton sequential stream
     */
    public static DoubleStreamEx of(double element) {
        return of(DoubleStream.of(element));
    }

    /**
     * Returns a sequential ordered {@code DoubleStreamEx} whose elements are
     * the specified values.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     */
    public static DoubleStreamEx of(double... elements) {
        return of(DoubleStream.of(elements));
    }

    /**
     * Returns a sequential {@link DoubleStreamEx} with the specified range of
     * the specified array as its source.
     *
     * @param array
     *            the array, assumed to be unmodified during use
     * @param startInclusive
     *            the first index to cover, inclusive
     * @param endExclusive
     *            index immediately past the last index to cover
     * @return an {@code DoubleStreamEx} for the array range
     * @throws ArrayIndexOutOfBoundsException
     *             if {@code startInclusive} is negative, {@code endExclusive}
     *             is less than {@code startInclusive}, or {@code endExclusive}
     *             is greater than the array size
     * @since 0.1.1
     * @see Arrays#stream(double[], int, int)
     */
    public static DoubleStreamEx of(double[] array, int startInclusive, int endExclusive) {
        return of(Arrays.stream(array, startInclusive, endExclusive));
    }

    /**
     * Returns a sequential ordered {@code DoubleStreamEx} whose elements are
     * the unboxed elements of supplied array.
     *
     * @param array
     *            the array to create the stream from.
     * @return the new stream
     * @see Arrays#stream(Object[])
     * @since 0.5.0
     */
    public static DoubleStreamEx of(Double[] array) {
        return of(Arrays.stream(array).mapToDouble(Double::doubleValue));
    }

    /**
     * Returns a sequential ordered {@code DoubleStreamEx} whose elements are
     * the specified float values casted to double.
     *
     * @param elements
     *            the elements of the new stream
     * @return the new stream
     * @since 0.2.0
     */
    public static DoubleStreamEx of(float... elements) {
        return of(elements, 0, elements.length);
    }

    /**
     * Returns a sequential {@link DoubleStreamEx} with the specified range of
     * the specified array as its source. Array values will be casted to double.
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
    public static DoubleStreamEx of(float[] array, int startInclusive, int endExclusive) {
        rangeCheck(array.length, startInclusive, endExclusive);
        return of(new RangeBasedSpliterator.OfFloat(startInclusive, endExclusive, array));
    }

    /**
     * Returns a {@code DoubleStreamEx} object which wraps given
     * {@link DoubleStream}
     * 
     * @param stream
     *            original stream
     * @return the wrapped stream
     * @since 0.0.8
     */
    public static DoubleStreamEx of(DoubleStream stream) {
        return stream instanceof DoubleStreamEx ? (DoubleStreamEx) stream : new DoubleStreamEx(stream);
    }

    /**
     * Returns a sequential {@link DoubleStreamEx} created from given
     * {@link java.util.Spliterator.OfDouble}.
     * 
     * @param spliterator
     *            a spliterator to create the stream from.
     * @return the new stream
     * @since 0.3.4
     */
    public static DoubleStreamEx of(Spliterator.OfDouble spliterator) {
        return of(StreamSupport.doubleStream(spliterator, false));
    }

    /**
     * Returns a sequential {@code DoubleStreamEx} containing an
     * {@link OptionalDouble} value, if present, otherwise returns an empty
     * {@code DoubleStreamEx}.
     *
     * @param optional
     *            the optional to create a stream of
     * @return a stream with an {@code OptionalDouble} value if present,
     *         otherwise an empty stream
     * @since 0.1.1
     */
    public static DoubleStreamEx of(OptionalDouble optional) {
        return optional.isPresent() ? of(optional.getAsDouble()) : empty();
    }

    /**
     * Returns a sequential ordered {@code DoubleStreamEx} whose elements are
     * the unboxed elements of supplied collection.
     *
     * @param collection
     *            the collection to create the stream from.
     * @return the new stream
     * @see Collection#stream()
     */
    public static DoubleStreamEx of(Collection<Double> collection) {
        return of(collection.stream().mapToDouble(Double::doubleValue));
    }

    /**
     * Returns an effectively unlimited stream of pseudorandom {@code double}
     * values, each between zero (inclusive) and one (exclusive) produced by
     * given {@link Random} object.
     *
     * <p>
     * A pseudorandom {@code double} value is generated as if it's the result of
     * calling the method {@link Random#nextDouble()}.
     *
     * @param random
     *            a {@link Random} object to produce the stream from
     * @return a stream of pseudorandom {@code double} values
     * @see Random#doubles()
     */
    public static DoubleStreamEx of(Random random) {
        return of(random.doubles());
    }

    public static DoubleStreamEx of(Random random, long streamSize) {
        return of(random.doubles(streamSize));
    }

    public static DoubleStreamEx of(Random random, double randomNumberOrigin, double randomNumberBound) {
        return of(random.doubles(randomNumberOrigin, randomNumberBound));
    }

    public static DoubleStreamEx of(Random random, long streamSize, double randomNumberOrigin, double randomNumberBound) {
        return of(random.doubles(streamSize, randomNumberOrigin, randomNumberBound));
    }

    /**
     * Returns an infinite sequential ordered {@code DoubleStreamEx} produced by
     * iterative application of a function {@code f} to an initial element
     * {@code seed}, producing a stream consisting of {@code seed},
     * {@code f(seed)}, {@code f(f(seed))}, etc.
     *
     * <p>
     * The first element (position {@code 0}) in the {@code DoubleStreamEx} will
     * be the provided {@code seed}. For {@code n > 0}, the element at position
     * {@code n}, will be the result of applying the function {@code f} to the
     * element at position {@code n - 1}.
     *
     * @param seed
     *            the initial element
     * @param f
     *            a function to be applied to to the previous element to produce
     *            a new element
     * @return A new sequential {@code DoubleStream}
     * @see DoubleStream#iterate(double, DoubleUnaryOperator)
     */
    public static DoubleStreamEx iterate(final double seed, final DoubleUnaryOperator f) {
        return of(DoubleStream.iterate(seed, f));
    }

    /**
     * Returns an infinite sequential unordered stream where each element is
     * generated by the provided {@code DoubleSupplier}. This is suitable for
     * generating constant streams, streams of random elements, etc.
     *
     * @param s
     *            the {@code DoubleSupplier} for generated elements
     * @return a new infinite sequential unordered {@code DoubleStreamEx}
     * @see DoubleStream#generate(DoubleSupplier)
     */
    public static DoubleStreamEx generate(DoubleSupplier s) {
        return of(DoubleStream.generate(s));
    }

    /**
     * Returns a sequential unordered {@code DoubleStreamEx} of given length
     * which elements are equal to supplied value.
     * 
     * @param value
     *            the constant value
     * @param length
     *            the length of the stream
     * @return a new {@code DoubleStreamEx}
     * @since 0.1.2
     */
    public static DoubleStreamEx constant(double value, long length) {
        return of(new ConstSpliterator.OfDouble(value, length));
    }

    /**
     * Returns a sequential {@code DoubleStreamEx} containing the results of
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
     * @return a new {@code DoubleStreamEx}
     * @throws IllegalArgumentException
     *             if length of the arrays differs.
     * @since 0.2.1
     */
    public static DoubleStreamEx zip(double[] first, double[] second, DoubleBinaryOperator mapper) {
        return of(new RangeBasedSpliterator.ZipDouble(0, checkLength(first.length, second.length), mapper, first,
                second));
    }
}
