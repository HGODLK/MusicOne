package des.c5inco.torph.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** The characters a locale uses to write numbers. */
public data class NumberSymbols(
    val decimalSeparator: Char,
    val groupingSeparator: Char,
    val minusSign: Char,
) {
    public companion object {
        private val cache = HashMap<Locale, NumberSymbols>()

        public fun forLocale(locale: Locale): NumberSymbols = synchronized(cache) {
            cache.getOrPut(locale) {
                val s = DecimalFormatSymbols.getInstance(locale)
                NumberSymbols(
                    decimalSeparator = s.decimalSeparator,
                    groupingSeparator = s.groupingSeparator,
                    minusSign = s.minusSign,
                )
            }
        }
    }
}

/**
 * Formats [value] the way `Intl.NumberFormat` would for [locale]: grouping on, half-up rounding,
 * and exactly [decimals] fraction digits, or when [decimals] is null (torph's default) as many as
 * needed up to 3.
 */
public fun formatNumber(value: Number, decimals: Int? = null, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.isGroupingUsed = true
    format.minimumFractionDigits = decimals ?: 0
    format.maximumFractionDigits = decimals ?: 3
    format.roundingMode = RoundingMode.HALF_UP
    // Round the shortest decimal form, as Intl does: 1.005 is 1.00499999... in binary, but Intl
    // (and so torph) formats it as 1.01 with two decimals.
    val v: Number = when (value) {
        is Float -> BigDecimal(value.toString())
        is Double -> BigDecimal.valueOf(value)
        else -> value
    }
    return format.format(v)
}

/** Parses the digits of [text] (any Unicode digit script) into a double; symbols other than the locale decimal separator and sign are ignored. */
internal fun parseNumericWord(text: String, symbols: NumberSymbols): Double {
    val sb = StringBuilder()
    var negative = false
    var sawDecimal = false
    for (ch in text) {
        when {
            Character.isDigit(ch) -> sb.append(Character.getNumericValue(ch))
            ch == symbols.decimalSeparator && !sawDecimal -> { sb.append('.'); sawDecimal = true }
            ch == symbols.minusSign || ch == '-' || ch == '−' -> negative = sb.isEmpty()
        }
    }
    val v = sb.toString().toDoubleOrNull() ?: return 0.0
    return if (negative) -v else v
}
