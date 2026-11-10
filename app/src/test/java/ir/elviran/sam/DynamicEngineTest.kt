package ir.elviran.sam

import ir.elviran.sam.core.DynamicValidation
import ir.elviran.sam.core.FormulaEngine
import ir.elviran.sam.data.dynamic.DynamicFieldEntity
import ir.elviran.sam.data.dynamic.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation + computed-field engine for the Dynamic Data Engine.
 * Needs the real org.json on the unit-test classpath (added via testImplementation).
 */
class DynamicEngineTest {

    private val price = DynamicFieldEntity(id = 1, moduleId = 1, name = "\u0642\u06cc\u0645\u062a", typeCode = FieldType.DECIMAL.code, required = true, minValue = 0.0)
    private val qty = DynamicFieldEntity(id = 2, moduleId = 1, name = "\u062a\u0639\u062f\u0627\u062f", typeCode = FieldType.INTEGER.code, required = true)
    private val date = DynamicFieldEntity(id = 3, moduleId = 1, name = "\u062a\u0627\u0631\u06cc\u062e", typeCode = FieldType.JDATE.code)
    private val color = DynamicFieldEntity(id = 4, moduleId = 1, name = "\u0631\u0646\u06af", typeCode = FieldType.SELECT.code, optionsJson = "[\"\u0642\u0631\u0645\u0632\",\"\u0622\u0628\u06cc\"]")
    private val total = DynamicFieldEntity(id = 5, moduleId = 1, name = "\u062c\u0645\u0639", typeCode = FieldType.COMPUTED.code, computeFormula = "{\u0642\u06cc\u0645\u062a}*{\u062a\u0639\u062f\u0627\u062f}")
    private val fields = listOf(price, qty, date, color, total)

    @Test fun validRecord_hasNoErrors() {
        val values = mapOf(1L to "\u06f1\u06f2\u06f5\u06f0\u06f0", 2L to "4", 3L to "\u06f1\u06f4\u06f0\u06f4/\u06f1\u06f2/\u06f2\u06f9", 4L to "\u0622\u0628\u06cc")
        assertTrue(DynamicValidation.validate(fields, values).isEmpty())
    }

    @Test fun invalidRecord_reportsEachProblem() {
        // price below min, qty not integer, Esfand 30 invalid in non-leap 1404, color out of options
        val values = mapOf(1L to "-5", 2L to "abc", 3L to "\u06f1\u06f4\u06f0\u06f4/\u06f1\u06f2/\u06f3\u06f0", 4L to "\u0633\u0628\u0632")
        val errors = DynamicValidation.validate(fields, values)
        assertTrue(errors.containsKey(1L))
        assertTrue(errors.containsKey(2L))
        assertTrue(errors.containsKey(3L))
        assertTrue(errors.containsKey(4L))
    }

    @Test fun requiredEmptyFields_reported() {
        val errors = DynamicValidation.validate(fields, mapOf(4L to "\u0642\u0631\u0645\u0632"))
        assertTrue(errors.containsKey(1L))
        assertTrue(errors.containsKey(2L))
        assertFalse("optional date not required", errors.containsKey(3L))
    }

    @Test fun computedField_isNotRequired() {
        val values = mapOf(1L to "100", 2L to "2")
        assertFalse(DynamicValidation.validate(fields, values).containsKey(5L))
    }

    @Test fun formula_multipliesReferencedFields() {
        val result = FormulaEngine.evaluate("{\u0642\u06cc\u0645\u062a}*{\u062a\u0639\u062f\u0627\u062f}", mapOf("\u0642\u06cc\u0645\u062a" to "\u06f1\u06f2\u06f5\u06f0\u06f0", "\u062a\u0639\u062f\u0627\u062f" to "4"))
        assertEquals(50000.0, result!!, 0.0001)
    }

    @Test fun formula_missingFieldTreatedAsZero() {
        assertEquals(5.0, FormulaEngine.evaluate("{x}+5", emptyMap())!!, 0.0001)
    }

    @Test fun formula_blankReturnsNull() {
        assertNull(FormulaEngine.evaluate("", emptyMap()))
    }

    @Test fun formula_listsReferencedNames() {
        assertEquals(listOf("a", "b"), FormulaEngine.referencedNames("{a}+{b}"))
    }
}
