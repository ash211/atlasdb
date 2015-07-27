// Copyright 2015 Palantir Technologies
//
// Licensed under the BSD-3 License (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.palantir.atlasdb.keyvalue.impl;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.RowResult;
import com.palantir.atlasdb.table.description.TableMetadata;
import com.palantir.atlasdb.transaction.api.TransactionConflictException.CellConflict;
import com.palantir.atlasdb.transaction.impl.TransactionConstants;
import com.palantir.common.annotation.Output;
import com.palantir.common.collect.IterableView;
import com.palantir.util.Pair;

public class Cells {
    private static final Logger log = LoggerFactory.getLogger(Cells.class);

    private Cells() { /* */ }

    public static Function<Cell, byte[]> getRowFunction() {
        return new Function<Cell, byte[]>() {
            @Override
            public byte[] apply(Cell from) {
                return from.getRowName();
            }
        };
    }

    public static Function<Cell, byte[]> getColumnFunction() {
        return new Function<Cell, byte[]>() {
            @Override
            public byte[] apply(Cell from) {
                return from.getColumnName();
            }
        };
    }

    public static SortedSet<byte[]> getRows(Iterable<Cell> cells) {
        return IterableView.of(cells).transform(getRowFunction())
                           .copyInto(Sets.newTreeSet(UnsignedBytes.lexicographicalComparator()));
    }

    static final byte[] SMALLEST_NAME = new byte[] { 0 };
    static final byte[] LARGEST_NAME = getLargestName();

    static byte[] getLargestName() {
        byte[] name = new byte[Cell.MAX_NAME_LENGTH];
        for (int i = 0 ; i < Cell.MAX_NAME_LENGTH ; i++) {
            name[i] = (byte) 0xff;
        }
        return name;
    }

    public static Cell createSmallestCellForRow(byte[] rowName) {
        return Cell.create(rowName, SMALLEST_NAME);
    }

    public static Cell createLargestCellForRow(byte[] rowName) {
        return Cell.create(rowName, LARGEST_NAME);
    }

    public static Set<Cell> cellsWithConstantColumn(Iterable<byte[]> rows, byte[] col) {
        return cellsWithConstantColumn(Sets.<Cell>newHashSet(), rows, col);
    }

    public static Set<Cell> cellsWithConstantColumn(Collection<byte[]> rows, byte[] col) {
        return cellsWithConstantColumn(Sets.<Cell>newHashSetWithExpectedSize(rows.size()), rows, col);
    }

    private static Set<Cell> cellsWithConstantColumn(@Output Set<Cell> collector, Iterable<byte[]> rows, byte[] col) {
        for (byte[] row : rows) {
            collector.add(Cell.create(row, col));
        }
        return collector;
    }

    public static <T> NavigableMap<byte[], SortedMap<byte[], T>> breakCellsUpByRow(Iterable<Map.Entry<Cell, T>> map) {
        NavigableMap<byte[], SortedMap<byte[], T>> ret = Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
        for (Map.Entry<Cell, T> e : map) {
            byte[] row = e.getKey().getRowName();
            SortedMap<byte[], T> sortedMap = ret.get(row);
            if (sortedMap == null) {
                sortedMap = Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
                ret.put(row, sortedMap);
            }
            sortedMap.put(e.getKey().getColumnName(), e.getValue());
        }
        return ret;
    }

    public static <T> NavigableMap<byte[], SortedMap<byte[], T>> breakCellsUpByRow(Map<Cell, T> map) {
        return breakCellsUpByRow(map.entrySet());
    }

    /**
     * The Collection provided to this function has to be sorted and strictly increasing.
     */
    public static <T> Iterator<RowResult<T>> createRowView(final Collection<Map.Entry<Cell, T>> sortedIterator) {
        final PeekingIterator<Entry<Cell, T>> it = Iterators.peekingIterator(sortedIterator.iterator());
        Iterator<Map.Entry<byte[], SortedMap<byte[], T>>> resultIt = new AbstractIterator<Map.Entry<byte[], SortedMap<byte[], T>>>() {
            byte[] row = null;
            SortedMap<byte[], T> map = null;
            @Override
            protected Entry<byte[], SortedMap<byte[], T>> computeNext() {
                if (!it.hasNext()) {
                    return endOfData();
                }
                row = it.peek().getKey().getRowName();
                map = Maps.newTreeMap(UnsignedBytes.lexicographicalComparator());
                while (it.hasNext()) {
                    Entry<Cell, T> peek = it.peek();
                    if (!Arrays.equals(peek.getKey().getRowName(), row)) {
                        break;
                    }
                    map.put(peek.getKey().getColumnName(), peek.getValue());
                    it.next();
                }
                return Maps.immutableEntry(row, map);
            }
        };
        return RowResults.viewOfEntries(resultIt);
    }

    public static <T> Map<Cell, T> convertRowResultsToCells(Collection<RowResult<T>> rows) {
        Map<Cell, T> ret = Maps.newHashMapWithExpectedSize(rows.size());
        for (RowResult<T> row : rows) {
            byte[] rowName = row.getRowName();
            for (Map.Entry<byte[], T> col : row.getColumns().entrySet()) {
                Cell cell = Cell.create(rowName, col.getKey());
                ret.put(cell, col.getValue());
            }
        }
        return ret;
    }

    // TODO: move to commons Maps2
    public static <K, V> Map<K, V> constantValueMap(Set<K> keys, V v) {
        Map<K, V> ret = Maps.newHashMapWithExpectedSize(keys.size());
        for (K k : keys) {
            ret.put(k, v);
        }
        return ret;
    }

    //TODO: carrino: move this to IteratorUtils
    /**
     * The iterators provided to this function have to be sorted and strictly increasing.
     * @deprecated
     * @see
     */
    @Deprecated
    public static <T> Iterator<T> mergeIterators(Iterator<? extends T> one, Iterator<? extends T> two,
            final Comparator<? super T> ordering, final Function<? super Pair<T, T>, ? extends T> mergeFunction) {
        Validate.notNull(mergeFunction);
        Validate.notNull(ordering);
        final PeekingIterator<T> a = Iterators.peekingIterator(one);
        final PeekingIterator<T> b = Iterators.peekingIterator(two);
        return new AbstractIterator<T>() {
            @Override
            protected T computeNext() {
                if (!a.hasNext() && !b.hasNext()) {
                    return endOfData();
                }
                if (!a.hasNext()) {
                    T ret =  b.next();
                    if (b.hasNext()) {
                        assert ordering.compare(ret, b.peek()) < 0;
                    }
                    return ret;
                }
                if (!b.hasNext()) {
                    T ret =  a.next();
                    if (a.hasNext()) {
                        assert ordering.compare(ret, a.peek()) < 0;
                    }
                    return ret;
                }
                T peekA = a.peek();
                T peekB = b.peek();
                int comp = ordering.compare(peekA, peekB);
                if (comp == 0) {
                    return mergeFunction.apply(Pair.create(a.next(), b.next()));
                } else if (comp < 0) {
                    T ret =  a.next();
                    if (a.hasNext()) {
                        assert ordering.compare(ret, a.peek()) < 0;
                    }
                    return ret;
                } else {
                    T ret =  b.next();
                    if (b.hasNext()) {
                        assert ordering.compare(ret, b.peek()) < 0;
                    }
                    return ret;
                }
            }
        };
    }

    //TODO: carrino: move this to IterableUtils
    public static <T> Iterable<T> wrapIterable(final Iterable<T> it, final Function<Iterator<T>, Iterator<T>> f) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return f.apply(it.iterator());
            }
            @Override
            public String toString() {
                return Iterables.toString(this);
            }
        };
    }

    //TODO: carrino: move this to IterableUtils
    public static <T> Iterable<T> mergeIterables(final Iterable<? extends T> one, final Iterable<? extends T> two,
            final Comparator<? super T> ordering, final Function<? super Pair<T, T>, ? extends T> mergeFunction) {
        Validate.notNull(one);
        Validate.notNull(two);
        Validate.notNull(mergeFunction);
        Validate.notNull(ordering);
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return mergeIterators(one.iterator(), two.iterator(), ordering, mergeFunction);
            }
            @Override
            public String toString() {
                return Iterables.toString(this);
            }
        };
    }

    public static long getApproxSizeOfCell(Cell cell) {
        return cell.getColumnName().length + cell.getRowName().length + TransactionConstants.APPROX_IN_MEM_CELL_OVERHEAD_BYTES;
    }

    public static Function<byte[], String> getNameFromBytesFunction() {
        return new Function<byte[], String>() {
            @Override
            public String apply(@Nullable byte[] input) {
                return getNameFromBytes(input);
            }
        };
    }

    public static String getNameFromBytes(byte[] name) {
        if (name == null) {
            return "null";
        }
        return new String(BaseEncoding.base16().lowerCase().encode(name));
    }

    public static CellConflict createConflictWithMetadata(KeyValueService kv,
                                                          String tableName,
                                                          Cell cell,
                                                          long theirStartTs,
                                                          long theirCommitTs) {
        TableMetadata metadata = KeyValueServices.getTableMetadataSafe(kv, tableName);
        return new CellConflict(cell, getHumanReadableCellName(metadata, cell), theirStartTs, theirCommitTs);
    }

    public static String getHumanReadableCellName(@Nullable TableMetadata metadata, Cell cell) {
        if (cell == null) {
            return "null";
        }
        if (metadata == null) {
            return cell.toString();
        }
        try {
            String rowName = metadata.getRowMetadata().renderToJson(cell.getRowName());
            String colName;
            if (metadata.getColumns().hasDynamicColumns()) {
                colName = metadata.getColumns().getDynamicColumn().getColumnNameDesc().renderToJson(cell.getColumnName());
            } else {
                colName = PtBytes.toString(cell.getColumnName());
            }
            return "Cell [rowName=" + rowName + ", columnName=" + colName + "]";
        } catch (Exception e) {
            log.warn("Failed to render as json", e);
            return cell.toString();
        }
    }
}