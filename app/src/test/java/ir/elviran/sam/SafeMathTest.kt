package ir.elviran.sam

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeMathTest {
    @Test fun respectsPrecedenceAndParentheses() {
        assertEquals(14.0, SafeMath.evaluate("۲+۳×۴"), 0.0001)
        assertEquals(20.0, SafeMath.evaluate("(۲+۳)×۴"), 0.0001)
        assertEquals(-4.0, SafeMath.evaluate("۲−۶"), 0.0001)
    }
}
