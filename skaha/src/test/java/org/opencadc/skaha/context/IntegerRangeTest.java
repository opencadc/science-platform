package org.opencadc.skaha.context;

import java.util.Iterator;
import org.junit.Assert;
import org.junit.Test;

public class IntegerRangeTest {
    @Test
    public void testConstructor() {
        IntegerRange range = new IntegerRange(1, 5);
        Assert.assertEquals("Wrong min", 1, range.minimum);
        Assert.assertEquals("Wrong max", 5, range.maximum);
    }

    @Test
    public void testIterator() {
        IntegerRange range = new IntegerRange(1, 7);
        int expected = 1;
        for (final Iterator<Integer> it = range.iterator(); it.hasNext(); ) {
            final Integer value = it.next();
            assert value == expected;

            if (it.hasNext()) {
                expected++;
            }
        }
        Assert.assertEquals("Wrong output", 7, expected); // Ensure we iterated through all values
    }
}
