package com.relay.app.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Decimal calculator with precedence, parentheses, unary signs and percentages. */
object CalculatorMath {
    private val precision = MathContext(16, RoundingMode.HALF_UP)

    fun evaluate(expression: String): String {
        val parser = Parser(expression.replace('×', '*').replace('÷', '/').replace('−', '-'))
        val result = parser.expression()
        require(parser.atEnd()) { "Incomplete calculation" }
        require(result.abs() < BigDecimal("1E100")) { "Number too large" }
        return result.round(precision).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val input: String) {
        private var position = 0
        fun atEnd() = position == input.length
        private fun take(c: Char): Boolean =
            if (position < input.length && input[position] == c) { position++; true } else false

        fun expression(): BigDecimal {
            var result = term()
            while (true) result = when {
                take('+') -> result.add(term(), precision)
                take('-') -> result.subtract(term(), precision)
                else -> return result
            }
        }

        private fun term(): BigDecimal {
            var result = factor()
            while (true) result = when {
                take('*') -> result.multiply(factor(), precision)
                take('/') -> {
                    val divisor = factor()
                    require(divisor.signum() != 0) { "Can't divide by zero" }
                    result.divide(divisor, precision)
                }
                else -> return result
            }
        }

        private fun factor(): BigDecimal {
            var result = when {
                take('-') -> factor().negate()
                take('+') -> factor()
                take('(') -> expression().also { require(take(')')) { "Close the parentheses" } }
                else -> {
                    val start = position
                    while (position < input.length && (input[position].isDigit() || input[position] == '.')) position++
                    require(position > start) { "Incomplete calculation" }
                    input.substring(start, position).toBigDecimalOrNull()
                        ?: throw IllegalArgumentException("Check the number")
                }
            }
            while (take('%')) result = result.divide(BigDecimal(100), precision)
            return result
        }
    }
}

data class CalculatorState(
    val expression: String = "",
    val error: String? = null,
    val evaluated: Boolean = false,
) {
    /** Only four freshly typed digits followed by + are a code attempt. Results never count. */
    val codeCandidate: String?
        get() = expression.takeIf { !evaluated && error == null && it.matches(Regex("[0-9]{4}")) }

    fun press(key: String): CalculatorState {
        if (key == "C") return CalculatorState()
        if (key == "⌫") return copy(expression = expression.dropLast(1), error = null, evaluated = false)
        if (key == "=") {
            if (expression.isEmpty()) return this
            return try { CalculatorState(CalculatorMath.evaluate(expression), evaluated = true) }
            catch (e: IllegalArgumentException) { copy(error = e.message ?: "Check the calculation") }
        }
        var text = if (error != null || (evaluated && (key.firstOrNull()?.isDigit() == true || key == "."))) "" else expression
        if (text.length >= 100) return this
        when (key) {
            "()" -> text += if (text.count { it == '(' } > text.count { it == ')' } && text.lastOrNull() !in listOf('+', '−', '×', '÷', '(')) ")"
                else if (text.lastOrNull()?.let { it.isDigit() || it == ')' || it == '%' } == true) "×(" else "("
            "." -> {
                if (text.lastOrNull() == ')' || text.lastOrNull() == '%') text += "×"
                val number = text.takeLastWhile { it.isDigit() || it == '.' }
                if ('.' in number) return this
                text += if (number.isEmpty()) "0." else "."
            }
            "+/−" -> {
                if (text.isEmpty()) text = "−"
                else {
                    val negativeAtom = Regex("\\(−([0-9]+(?:\\.[0-9]*)?)\\)$").find(text)
                    if (negativeAtom != null) return CalculatorState(text.take(negativeAtom.range.first) + negativeAtom.groupValues[1])
                    val number = text.takeLastWhile { it.isDigit() || it == '.' }
                    if (number.isNotEmpty()) {
                        val before = text.dropLast(number.length)
                        text = "$before(−$number)"
                    }
                }
            }
            "+", "−", "×", "÷" -> {
                if (text.isEmpty()) return if (key == "−") CalculatorState("−") else this
                text = if (text.last() in "+−×÷") text.dropLast(1) + key else text + key
            }
            "%" -> if (text.lastOrNull()?.let { it.isDigit() || it == ')' } == true) text += "%" else return this
            else -> if (key.length == 1 && key[0].isDigit()) {
                if (text.lastOrNull() == ')' || text.lastOrNull() == '%') text += "×"
                text += key
            } else return this
        }
        return CalculatorState(text)
    }
}
