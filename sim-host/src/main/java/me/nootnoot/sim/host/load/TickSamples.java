package me.nootnoot.sim.host.load;

import java.util.Arrays;

public final class TickSamples {

    private long[] samples;
    private int count;
    private long total;
    private long max = Long.MIN_VALUE;
    private long[] sorted;

    public TickSamples(int expected) {
        this.samples = new long[Math.max(16, expected)];
    }

    public void add(long value) {
        if (count == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[count++] = value;
        total += value;
        if (value > max) {
            max = value;
        }
        sorted = null;
    }

    public int count() {
        return count;
    }

    public long total() {
        return total;
    }

    public long max() {
        return count == 0 ? 0 : max;
    }

    public double mean() {
        return count == 0 ? 0.0 : (double) total / count;
    }

    public long percentile(double p) {
        if (count == 0) {
            return 0;
        }
        if (sorted == null) {
            sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
        }
        int idx = (int) Math.ceil(p / 100.0 * count) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= count) {
            idx = count - 1;
        }
        return sorted[idx];
    }

    public int countAbove(long threshold) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (samples[i] > threshold) {
                n++;
            }
        }
        return n;
    }
}
