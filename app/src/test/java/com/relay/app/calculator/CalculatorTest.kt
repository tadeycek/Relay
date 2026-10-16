package com.relay.app.calculator

import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {
    @Test fun decimalsAndPrecedence() {
        assertEquals("0.3", CalculatorMath.evaluate("0.1+0.2"))
        assertEquals("14", CalculatorMath.evaluate("2+3×4"))
        assertEquals("20", CalculatorMath.evaluate("(2+3)×4"))
        assertEquals("-3", CalculatorMath.evaluate("−6÷2"))
        assertEquals("25", CalculatorMath.evaluate("200×12.5%"))
    }

    @Test fun invalidExpressionsFail() {
        listOf("5÷0", "2+", "(4+2", "1.2.3").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CalculatorMath.evaluate(value) }
        }
    }

    @Test fun onlyFreshFourDigitsCountAsCodes() {
        var state = CalculatorState()
        "0012".forEach { state = state.press(it.toString()) }
        assertEquals("0012", state.codeCandidate)
        assertNull(state.press("=").codeCandidate)
        assertNull(CalculatorState("1000+234").press("=").codeCandidate)
        assertNull(CalculatorState("12345").codeCandidate)
        assertNull(CalculatorState("12.34").codeCandidate)
    }

    @Test fun operationsStillWorkAroundTheGate() {
        var state = CalculatorState()
        listOf("1", "2", "+", "3", "=").forEach { state = state.press(it) }
        assertEquals("15", state.expression)
        assertEquals("7", state.press("7").expression)
        assertEquals("", state.press("C").expression)
        assertEquals("1", CalculatorState("12").press("⌫").expression)
    }

    @Test fun signToggleAndImplicitMultiplication() {
        val negative = CalculatorState("2.5").press("+/−")
        assertEquals("-2.5", negative.press("=").expression)
        assertEquals("2.5", negative.press("+/−").expression)
        assertEquals("20", CalculatorState("(2+3)").press("4").press("=").expression)
        assertEquals("2.5", CalculatorState("(2+3)").press(".").press("5").press("=").expression)
    }
}
