/*
 * Portions of this file are derived from Ryu (https://github.com/ulfjack/ryu), Copyright 2018
 * Ulf Adams, licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * those portions except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * Provenance (task #73, spec msgs 8ac9a961/346d5cb2/59630072): the shortest-round-trip digit
 * selection below is a faithful Kotlin port of upstream RyuDouble.java (ulfjack/ryu, master,
 * src/main/java/info/adams/ryu/RyuDouble.java + RoundingMode.java, Apache-2.0), including its
 * ROUND_EVEN boundary acceptance, which is what ECMA-262 Number::toString step 5 requires and
 * RFC 8785 Appendix B pins. The upstream presentation layer (Double.toString semantics) is
 * replaced: the core exposes digits + decimal exponent, and CanonicalJson.renderEs applies the
 * ECMAScript fixed/exponential form. Upstream table generation is reproduced verbatim so the
 * lookup tables stay mechanically derived from the published algorithm.
 */
package au.com.shiftyjelly.pocketcasts.voicecontrol.upload.record

import java.math.BigInteger

/**
 * Upstream Ryu tables and helpers (Apache-2.0, (c) 2018 Ulf Adams). Derived at class-init from
 * BigInteger exactly as upstream's PrintDoubleLookupTable/RyuDouble static block does.
 */
internal object RyuTables {
    const val POS_TABLE_SIZE = 326
    const val NEG_TABLE_SIZE = 342
    const val POW5_BITCOUNT = 121 // max 3*31 = 124
    const val POW5_INV_BITCOUNT = 122 // max 3*31 = 124
    const val POW5_QUARTER_BITCOUNT = 31
    const val POW5_INV_QUARTER_BITCOUNT = 31

    val POW5_SPLIT = Array(POS_TABLE_SIZE) { IntArray(4) }
    val POW5_INV_SPLIT = Array(NEG_TABLE_SIZE) { IntArray(4) }

    init {
        val mask = BigInteger.ONE.shiftLeft(POW5_QUARTER_BITCOUNT).subtract(BigInteger.ONE)
        val invMask = BigInteger.ONE.shiftLeft(POW5_INV_QUARTER_BITCOUNT).subtract(BigInteger.ONE)
        for (i in 0 until maxOf(POS_TABLE_SIZE, NEG_TABLE_SIZE)) {
            val pow = BigInteger.valueOf(5).pow(i)
            val pow5len = pow.bitLength()
            check(pow5bits(i) == pow5len) { "$pow5len != ${pow5bits(i)}" }
            if (i < POS_TABLE_SIZE) {
                for (j in 0 until 4) {
                    POW5_SPLIT[i][j] = pow
                        .shiftRight(pow5len - POW5_BITCOUNT + (3 - j) * POW5_QUARTER_BITCOUNT)
                        .and(mask)
                        .intValueExact()
                }
            }
            if (i < NEG_TABLE_SIZE) {
                // floor(log_2 5^q) is pow5len - 1.
                val j = pow5len - 1 + POW5_INV_BITCOUNT
                val inv = BigInteger.ONE.shiftLeft(j).divide(pow).add(BigInteger.ONE)
                for (k in 0 until 4) {
                    POW5_INV_SPLIT[i][k] =
                        if (k == 0) {
                            inv.shiftRight((3 - k) * POW5_INV_QUARTER_BITCOUNT).intValueExact()
                        } else {
                            inv.shiftRight((3 - k) * POW5_INV_QUARTER_BITCOUNT).and(invMask).intValueExact()
                        }
                }
            }
        }
    }

    fun pow5bits(e: Int): Int = ((e * 1217359) ushr 19) + 1
}

/** ROUND_EVEN boundary acceptance, verbatim semantics from upstream RoundingMode.ROUND_EVEN. */
internal object RyuRoundEven {
    fun acceptUpperBound(even: Boolean): Boolean = even
    fun acceptLowerBound(even: Boolean): Boolean = even
}

/**
 * Kotlin port of upstream RyuDouble for double, stripped to the digit-selection core. Returns the
 * shortest round-tripping digits [RyuShortest.Result.digits] (no trailing zeros, sign excluded)
 * and the decimal place of their leading digit [RyuShortest.Result.leadingExp].
 */
internal object RyuShortest {

    private const val DOUBLE_MANTISSA_BITS = 52
    private const val DOUBLE_EXPONENT_BITS = 11
    private const val DOUBLE_BIAS = 1023
    private const val DOUBLE_MANTISSA_MASK = (1L shl DOUBLE_MANTISSA_BITS) - 1
    private const val DOUBLE_EXPONENT_MASK = (1 shl DOUBLE_EXPONENT_BITS) - 1

    class Result(val digits: Long, val olength: Int, val leadingExp: Int)

    fun doubleToShortest(value: Double): Result {
        // Step 1: decode, unify normalized and subnormal (upstream Step 1).
        // Non-finite inputs are refused by the caller (canonical records must be finite).
        val bits = java.lang.Double.doubleToRawLongBits(value)
        val ieeeExponent = ((bits ushr DOUBLE_MANTISSA_BITS) and DOUBLE_EXPONENT_MASK.toLong()).toInt()
        val ieeeMantissa = bits and DOUBLE_MANTISSA_MASK
        val e2: Int
        val m2: Long
        if (ieeeExponent == 0) {
            e2 = 1 - DOUBLE_BIAS - DOUBLE_MANTISSA_BITS
            m2 = ieeeMantissa
        } else {
            e2 = ieeeExponent - DOUBLE_BIAS - DOUBLE_MANTISSA_BITS
            m2 = ieeeMantissa or (1L shl DOUBLE_MANTISSA_BITS)
        }

        // Step 2: interval of legal decimal representations (upstream Step 2).
        val even = (m2 and 1L) == 0L
        val mv = 4 * m2
        val mp = 4 * m2 + 2
        val mmShift = if (m2 != (1L shl DOUBLE_MANTISSA_BITS) || ieeeExponent <= 1) 1 else 0
        val mm = 4 * m2 - 1 - mmShift
        val e2s = e2 - 2

        // Step 3: decimal power base via 128-bit arithmetic (upstream Step 3).
        var dv: Long
        var dp: Long
        var dm: Long
        val e10: Int
        var dmIsTrailingZeros = false
        var dvIsTrailingZeros = false
        if (e2s >= 0) {
            val q = maxOf(0, ((e2s * 78913) ushr 18) - 1)
            val k = RyuTables.POW5_INV_BITCOUNT + RyuTables.pow5bits(q) - 1
            val i = -e2s + q + k
            dv = mulPow5InvDivPow2(mv, q, i)
            dp = mulPow5InvDivPow2(mp, q, i)
            dm = mulPow5InvDivPow2(mm, q, i)
            e10 = q
            if (q <= 21) {
                if (mv % 5 == 0L) {
                    dvIsTrailingZeros = multipleOfPowerOf5(mv, q)
                } else if (RyuRoundEven.acceptUpperBound(even)) {
                    dmIsTrailingZeros = multipleOfPowerOf5(mm, q)
                } else if (multipleOfPowerOf5(mp, q)) {
                    dp--
                }
            }
        } else {
            val q = maxOf(0, ((-e2s * 732923) ushr 20) - 1)
            val i = -e2s - q
            val k = RyuTables.pow5bits(i) - RyuTables.POW5_BITCOUNT
            val j = q - k
            dv = mulPow5divPow2(mv, i, j)
            dp = mulPow5divPow2(mp, i, j)
            dm = mulPow5divPow2(mm, i, j)
            e10 = q + e2s
            if (q <= 1) {
                dvIsTrailingZeros = true
                if (RyuRoundEven.acceptUpperBound(even)) {
                    dmIsTrailingZeros = mmShift == 1
                } else {
                    dp--
                }
            } else if (q < 63) {
                dvIsTrailingZeros = (mv and ((1L shl (q - 1)) - 1)) == 0L
            }
        }

        // Step 4: shortest decimal representation in the interval (upstream Step 4, ROUND_EVEN).
        val vplength = decimalLength(dp)
        val exp = e10 + vplength - 1

        var lastRemovedDigit = 0
        var output: Long
        var removed = 0
        if (dmIsTrailingZeros || dvIsTrailingZeros) {
            while (dp / 10 > dm / 10) {
                dmIsTrailingZeros = dmIsTrailingZeros and (dm % 10 == 0L)
                dvIsTrailingZeros = dvIsTrailingZeros and (lastRemovedDigit == 0)
                lastRemovedDigit = (dv % 10).toInt()
                dp /= 10
                dv /= 10
                dm /= 10
                removed++
            }
            if (dmIsTrailingZeros && RyuRoundEven.acceptLowerBound(even)) {
                while (dm % 10 == 0L) {
                    dvIsTrailingZeros = dvIsTrailingZeros and (lastRemovedDigit == 0)
                    lastRemovedDigit = (dv % 10).toInt()
                    dp /= 10
                    dv /= 10
                    dm /= 10
                    removed++
                }
            }
            if (dvIsTrailingZeros && lastRemovedDigit == 5 && dv % 2 == 0L) {
                // Round even if the exact number is .....50..0.
                lastRemovedDigit = 4
            }
            output = dv +
                (
                    (dv == dm && !(dmIsTrailingZeros && RyuRoundEven.acceptLowerBound(even))) ||
                        lastRemovedDigit >= 5
                    ).let { if (it) 1L else 0L }
        } else {
            while (dp / 10 > dm / 10) {
                lastRemovedDigit = (dv % 10).toInt()
                dp /= 10
                dv /= 10
                dm /= 10
                removed++
            }
            output = dv + (if (dv == dm || lastRemovedDigit >= 5) 1L else 0L)
        }
        var olen = vplength - removed
        // A round-up can re-introduce trailing zeros (999 -> 1000); ES6 forbids them. Stripping
        // zeros from the right shortens the digit count without moving the leading digit's place.
        while (output % 10 == 0L) {
            output /= 10
            olen--
        }
        return Result(output, olen, exp)
    }

    private fun decimalLength(v: Long): Int = v.toString().length

    private fun multipleOfPowerOf5(value: Long, q: Int): Boolean = pow5Factor(value) >= q

    private fun pow5Factor(value: Long): Int {
        var v = value
        if (v % 5 != 0L) return 0
        if (v % 25 != 0L) return 1
        if (v % 125 != 0L) return 2
        if (v % 625 != 0L) return 3
        var count = 4
        v /= 625
        while (v > 0) {
            if (v % 5 != 0L) return count
            v /= 5
            count++
        }
        error("unreachable")
    }

    /** Upstream mulPow5divPow2: high digits of m * 5^i / 2^j. */
    private fun mulPow5divPow2(m: Long, i: Int, j: Int): Long {
        val mHigh = m ushr 31
        val mLow = m and 0x7fffffffL
        val bits13 = mHigh * RyuTables.POW5_SPLIT[i][0]
        val bits03 = mLow * RyuTables.POW5_SPLIT[i][0]
        val bits12 = mHigh * RyuTables.POW5_SPLIT[i][1]
        val bits02 = mLow * RyuTables.POW5_SPLIT[i][1]
        val bits11 = mHigh * RyuTables.POW5_SPLIT[i][2]
        val bits01 = mLow * RyuTables.POW5_SPLIT[i][2]
        val bits10 = mHigh * RyuTables.POW5_SPLIT[i][3]
        val bits00 = mLow * RyuTables.POW5_SPLIT[i][3]
        val actualShift = j - 3 * 31 - 21
        check(actualShift >= 0) { "$actualShift" }
        return (
            (
                (
                    (
                        (
                            (((bits00 ushr 31) + bits01 + bits10) ushr 31) +
                                bits02 + bits11
                            ) ushr 31
                        ) +
                        bits03 + bits12
                    ) ushr 21
                ) +
                (bits13 shl 10)
            ) ushr actualShift
    }

    /** Upstream mulPow5InvDivPow2: high digits of m / 5^i / 2^j. */
    private fun mulPow5InvDivPow2(m: Long, i: Int, j: Int): Long {
        val mHigh = m ushr 31
        val mLow = m and 0x7fffffffL
        val bits13 = mHigh * RyuTables.POW5_INV_SPLIT[i][0]
        val bits03 = mLow * RyuTables.POW5_INV_SPLIT[i][0]
        val bits12 = mHigh * RyuTables.POW5_INV_SPLIT[i][1]
        val bits02 = mLow * RyuTables.POW5_INV_SPLIT[i][1]
        val bits11 = mHigh * RyuTables.POW5_INV_SPLIT[i][2]
        val bits01 = mLow * RyuTables.POW5_INV_SPLIT[i][2]
        val bits10 = mHigh * RyuTables.POW5_INV_SPLIT[i][3]
        val bits00 = mLow * RyuTables.POW5_INV_SPLIT[i][3]
        val actualShift = j - 3 * 31 - 21
        check(actualShift >= 0) { "$actualShift" }
        return (
            (
                (
                    (
                        (
                            (((bits00 ushr 31) + bits01 + bits10) ushr 31) +
                                bits02 + bits11
                            ) ushr 31
                        ) +
                        bits03 + bits12
                    ) ushr 21
                ) +
                (bits13 shl 10)
            ) ushr actualShift
    }
}
