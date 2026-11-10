package ir.elviran.sam

import ir.elviran.sam.core.Intent
import ir.elviran.sam.core.OfflineBrain
import ir.elviran.sam.core.SafeMathBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfflineBrainTest {

    @Before fun bindCalculator() {
        // bind the real bounded parser so offline calculation works in tests too
        SafeMathBridge.bind { expr -> SafeMath.evaluate(expr) }
    }

    @Test fun recognisesCalculation() {
        val r = OfflineBrain.interpret("\u062d\u0633\u0627\u0628 \u06a9\u0646 \u06f1\u06f2*\u06f5")
        assertTrue(r is Intent.Calculate)
        assertEquals(60.0, (r as Intent.Calculate).result, 1e-6)
    }

    @Test fun recognisesBareArithmetic() {
        val r = OfflineBrain.interpret("\u06f1\u06f0+\u06f5")
        assertTrue(r is Intent.Calculate)
        assertEquals(15.0, (r as Intent.Calculate).result, 1e-6)
    }

    @Test fun recognisesIncome() {
        val r = OfflineBrain.interpret("\u0641\u0631\u0648\u0634 \u06f5\u06f0\u06f0\u06f0\u06f0\u06f0 \u062a\u0648\u0645\u0627\u0646 \u0622\u0628\u0646\u0628\u0627\u062a")
        assertTrue(r is Intent.AddMoney)
        r as Intent.AddMoney
        assertTrue(r.isIncome)
        assertEquals(500000L, r.amount)
    }

    @Test fun recognisesTaskAndNote() {
        assertTrue(OfflineBrain.interpret("\u06cc\u0627\u062f\u0645 \u0628\u0646\u062f\u0627\u0632 \u0633\u0641\u0627\u0631\u0634 \u06a9\u0627\u0631\u062a\u0646") is Intent.AddTask)
        assertTrue(OfflineBrain.interpret("\u06cc\u0627\u062f\u062f\u0627\u0634\u062a \u06a9\u0646 \u062c\u0644\u0633\u0647 \u0628\u0627 \u062a\u0627\u0645\u06cc\u0646") is Intent.AddNote)
    }

    @Test fun recognisesQueryAndUnknown() {
        assertTrue(OfflineBrain.interpret("\u062a\u0648\u0644\u06cc\u062f \u0627\u0645\u0631\u0648\u0632 \u0686\u0642\u062f\u0631 \u0628\u0648\u062f") is Intent.Query)
        assertTrue(OfflineBrain.interpret("\u0633\u0644\u0627\u0645 \u062d\u0627\u0644\u062a \u0686\u0637\u0648\u0631\u0647") is Intent.Unknown)
    }
}
