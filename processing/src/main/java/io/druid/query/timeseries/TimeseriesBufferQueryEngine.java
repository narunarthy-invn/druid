/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013, 2014  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.query.timeseries;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.metamx.common.Pair;
import com.metamx.common.guava.BaseSequence;
import com.metamx.common.guava.FunctionalIterator;
import com.metamx.common.guava.Sequence;
import com.metamx.common.logger.Logger;
import io.druid.granularity.QueryGranularity;
import io.druid.query.Result;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.gpu.KernelAggregator;
import io.druid.query.aggregation.gpu.KernelAggregatorFactory;
import io.druid.segment.BufferCursor;
import io.druid.segment.QueryableIndexStorageAdapter;
import io.druid.segment.StorageAdapter;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Iterator;
import java.util.List;

public class TimeseriesBufferQueryEngine extends TimeseriesQueryEngine
{
  public static class LongBufferIterator implements Iterator<Long>
  {
    final LongBuffer buf;

    public LongBufferIterator(LongBuffer buf)
    {
      this.buf = buf;
    }

    @Override
    public boolean hasNext()
    {
      return buf.hasRemaining();
    }

    @Override
    public Long next()
    {
      return buf.get();
    }

    @Override
    public void remove()
    {
      throw new UnsupportedOperationException();
    }
  }

  private static final Logger log = new Logger(TimeseriesBufferQueryEngine.class);

  public TimeseriesBufferQueryEngine()
  {
    log.info("Creating Buffer Engine");
  }

  @Override
  public Sequence<Result<TimeseriesResultValue>> process(final TimeseriesQuery query, final StorageAdapter adapter)
  {
    if (Iterables.any(
        query.getAggregatorSpecs(), new Predicate<AggregatorFactory>()
        {
          @Override
          public boolean apply(@Nullable AggregatorFactory input)
          {
            return !(input instanceof KernelAggregatorFactory);
          }
        }
    )) {
      return super.process(query, adapter);
    }

    return new BaseSequence<>(
        new BaseSequence.IteratorMaker<Result<TimeseriesResultValue>, Iterator<Result<TimeseriesResultValue>>>()
        {
          @Override
          public Iterator<Result<TimeseriesResultValue>> make()
          {
            List<Interval> queryIntervals = query.getQuerySegmentSpec().getIntervals();
            Preconditions.checkArgument(
                queryIntervals.size() == 1, "Can only handle a single interval, got[%s]", queryIntervals
            );


            QueryableIndexStorageAdapter bufferAdapter = (QueryableIndexStorageAdapter) adapter;
            final QueryGranularity gran = query.getGranularity();
            BufferCursor cursor = bufferAdapter.makeBufferCursor(queryIntervals.get(0), gran);

            final List<AggregatorFactory> aggregatorSpecs = query.getAggregatorSpecs();

            Preconditions.checkArgument(aggregatorSpecs.size() == 1);
            final KernelAggregatorFactory kernelFactory = (KernelAggregatorFactory) aggregatorSpecs.get(0);

            KernelAggregator aggregator = kernelFactory.factorizeKernel(cursor);

            ByteOrder kernelByteOrder = kernelFactory.getByteOrder();

            Pair<LongBuffer, IntBuffer> timeAndBuckets = cursor.makeBucketOffsets(kernelByteOrder);
            ByteBuffer out = ByteBuffer.allocateDirect(
                kernelFactory.getMaxIntermediateSize()
                * timeAndBuckets.lhs.remaining()
            )
            .order(kernelByteOrder);

            while (!cursor.isDone()) {
              aggregator.copyBuffer();
              cursor.advance();
            }
            aggregator.run(timeAndBuckets.rhs, out, 0);
            aggregator.close();

            out.rewind();
            final FloatBuffer outFloat = out.asFloatBuffer();

            return FunctionalIterator
                .create(new LongBufferIterator(timeAndBuckets.lhs))
                .transform(
                    new Function<Long, Result<TimeseriesResultValue>>()
                    {
                      @Nullable
                      @Override
                      public Result<TimeseriesResultValue> apply(
                          @Nullable Long input
                      )
                      {

                        TimeseriesResultBuilder bob = new TimeseriesResultBuilder(gran.toDateTime(input));
                        bob.addMetric(kernelFactory.getName(), outFloat.get());
                        return bob.build();
                      }
                    }
                );
          }

          @Override
          public void cleanup(Iterator<Result<TimeseriesResultValue>> toClean)
          {
            // https://github.com/metamx/druid/issues/128
            while (toClean.hasNext()) {
              toClean.next();
            }
          }
        }
    );
  }
}
