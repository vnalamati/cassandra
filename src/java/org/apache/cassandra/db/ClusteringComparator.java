/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.google.common.base.Joiner;

import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.apache.cassandra.io.sstable.IndexHelper.IndexInfo;

/**
 * A comparator of clustering prefixes (or more generally of {@link Clusterable}}.
 * <p>
 * This is essentially just a composite comparator that the clustering values of the provided
 * clustering prefixes in lexicographical order, with each component being compared based on
 * the type of the clustering column this is a value of.
 */
public class ClusteringComparator implements Comparator<Clusterable>
{
    private final List<AbstractType<?>> clusteringTypes;
    private final boolean isByteOrderComparable;

    private final Comparator<IndexInfo> indexComparator;
    private final Comparator<IndexInfo> indexReverseComparator;
    private final Comparator<Clusterable> reverseComparator;

    public ClusteringComparator(AbstractType<?>... clusteringTypes)
    {
        this(Arrays.<AbstractType<?>>asList(clusteringTypes));
    }

    public ClusteringComparator(List<AbstractType<?>> clusteringTypes)
    {
        this.clusteringTypes = clusteringTypes;
        this.isByteOrderComparable = isByteOrderComparable(clusteringTypes);

        this.indexComparator = new Comparator<IndexInfo>()
        {
            public int compare(IndexInfo o1, IndexInfo o2)
            {
                return ClusteringComparator.this.compare(o1.lastName, o2.lastName);
            }
        };
        this.indexReverseComparator = new Comparator<IndexInfo>()
        {
            public int compare(IndexInfo o1, IndexInfo o2)
            {
                return ClusteringComparator.this.compare(o1.firstName, o2.firstName);
            }
        };
        this.reverseComparator = new Comparator<Clusterable>()
        {
            public int compare(Clusterable c1, Clusterable c2)
            {
                return ClusteringComparator.this.compare(c2, c1);
            }
        };
    }

    private static boolean isByteOrderComparable(Iterable<AbstractType<?>> types)
    {
        boolean isByteOrderComparable = true;
        for (AbstractType<?> type : types)
            isByteOrderComparable &= type.isByteOrderComparable();
        return isByteOrderComparable;
    }

    /**
     * The number of clustering columns for the table this is the comparator of.
     */
    public int size()
    {
        return clusteringTypes.size();
    }

    /**
     * The "subtypes" of this clustering comparator, that is the types of the clustering
     * columns for the table this is a comparator of.
     */
    public List<AbstractType<?>> subtypes()
    {
        return clusteringTypes;
    }

    /**
     * Returns the type of the ith clustering column of the table.
     */
    public AbstractType<?> subtype(int i)
    {
        return clusteringTypes.get(i);
    }

    /**
     * Creates a row clustering based on the clustering values.
     * <p>
     * Every argument can either be a {@code ByteBuffer}, in which case it is used as-is, or a object
     * corresponding to the type of the corresponding clustering column, in which case it will be
     * converted to a byte buffer using the column type.
     *
     * @param values the values to use for the created clustering. There should be exactly {@code size()}
     * values which must be either byte buffers or of the type the column expect.
     *
     * @return the newly created clustering.
     */
    public Clustering make(Object... values)
    {
        if (values.length != size())
            throw new IllegalArgumentException(String.format("Invalid number of components, expecting %d but got %d", size(), values.length));

        CBuilder builder = CBuilder.create(this);
        for (int i = 0; i < values.length; i++)
        {
            Object val = values[i];
            if (val instanceof ByteBuffer)
                builder.add((ByteBuffer)val);
            else
                builder.add(val);
        }
        return builder.build();
    }

    public int compare(Clusterable c1, Clusterable c2)
    {
        return compare(c1.clustering(), c2.clustering());
    }

    public int compare(ClusteringPrefix c1, ClusteringPrefix c2)
    {
        int s1 = c1.size();
        int s2 = c2.size();
        int minSize = Math.min(s1, s2);

        for (int i = 0; i < minSize; i++)
        {
            int cmp = compareComponent(i, c1.get(i), c2.get(i));
            if (cmp != 0)
                return cmp;
        }

        if (s1 == s2)
            return ClusteringPrefix.Kind.compare(c1.kind(), c2.kind());

        return s1 < s2 ? c1.kind().comparedToClustering : -c2.kind().comparedToClustering;
    }

    public int compare(Clustering c1, Clustering c2)
    {
        for (int i = 0; i < size(); i++)
        {
            int cmp = compareComponent(i, c1.get(i), c2.get(i));
            if (cmp != 0)
                return cmp;
        }
        return 0;
    }

    public int compareComponent(int i, ByteBuffer v1, ByteBuffer v2)
    {
        if (v1 == null)
            return v1 == null ? 0 : -1;
        if (v2 == null)
            return 1;

        return isByteOrderComparable
             ? ByteBufferUtil.compareUnsigned(v1, v2)
             : clusteringTypes.get(i).compare(v1, v2);
    }

    /**
     * Returns whether this clustering comparator is compatible with the provided one,
     * that is if the provided one can be safely replaced by this new one.
     *
     * @param previous the previous comparator that we want to replace and test
     * compatibility with.
     *
     * @return whether {@code previous} can be safely replaced by this comparator.
     */
    public boolean isCompatibleWith(ClusteringComparator previous)
    {
        if (this == previous)
            return true;

        // Extending with new components is fine, shrinking is not
        if (size() < previous.size())
            return false;

        for (int i = 0; i < previous.size(); i++)
        {
            AbstractType<?> tprev = previous.subtype(i);
            AbstractType<?> tnew = subtype(i);
            if (!tnew.isCompatibleWith(tprev))
                return false;
        }
        return true;
    }

    /**
     * Validates the provided prefix for corrupted data.
     *
     * @param clustering the clustering prefix to validate.
     *
     * @throws MarshalException if {@code clustering} contains some invalid data.
     */
    public void validate(ClusteringPrefix clustering)
    {
        for (int i = 0; i < clustering.size(); i++)
        {
            ByteBuffer value = clustering.get(i);
            if (value != null)
                subtype(i).validate(value);
        }
    }

    public Comparator<IndexInfo> indexComparator(boolean reversed)
    {
        return reversed ? indexReverseComparator : indexComparator;
    }

    public Comparator<Clusterable> reversed()
    {
        return reverseComparator;
    }

    /**
     * Whether the two provided clustering prefix are on the same clustering values.
     *
     * @param c1 the first prefix.
     * @param c2 the second prefix.
     * @return whether {@code c1} and {@code c2} have the same clustering values (but not necessarily
     * the same "kind") or not.
     */
    public boolean isOnSameClustering(ClusteringPrefix c1, ClusteringPrefix c2)
    {
        if (c1.size() != c2.size())
            return false;

        for (int i = 0; i < c1.size(); i++)
        {
            if (compareComponent(i, c1.get(i), c2.get(i)) != 0)
                return false;
        }
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("comparator(%s)", Joiner.on(", ").join(clusteringTypes));
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;

        if (!(o instanceof ClusteringComparator))
            return false;

        ClusteringComparator that = (ClusteringComparator)o;
        return this.clusteringTypes.equals(that.clusteringTypes);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(clusteringTypes);
    }
}
