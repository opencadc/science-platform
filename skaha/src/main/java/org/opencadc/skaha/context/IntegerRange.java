package org.opencadc.skaha.context;

import java.util.Iterator;

public class IntegerRange {
    final int minimum;
    final int maximum;

    public IntegerRange(int minimum, int maximum) {
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public boolean valueOutside(int value) {
        return value < minimum || value > maximum;
    }

    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private int current = minimum;

            @Override
            public boolean hasNext() {
                return current <= maximum;
            }

            @Override
            public Integer next() {
                return current++;
            }
        };
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IntegerRange
                && this.minimum == ((IntegerRange) obj).minimum
                && this.maximum == ((IntegerRange) obj).maximum;
    }

    @Override
    public String toString() {
        return String.format("%d-%d", minimum, maximum);
    }
}
