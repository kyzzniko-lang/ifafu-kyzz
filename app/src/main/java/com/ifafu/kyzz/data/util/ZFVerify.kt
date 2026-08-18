package com.ifafu.kyzz.data.util

import android.content.Context
import android.graphics.Bitmap
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.Scanner

class ZFVerify(context: Context) {

    private val appContext = context.applicationContext
    private val weight = Array(34) { Array(337) { BigDecimal.ZERO } }

    @Volatile
    var initialized: Boolean = false
        private set

    private val initLock = Any()

    /**
     * 权重文件含 34x337 个 BigDecimal 令牌，解析耗时可观。
     * 此前放在构造块里，而构造发生在主线程注入时（登录页启动卡顿）。
     * 改为首次 recognize() 时懒加载（调用方已把 recognize 放到后台线程）。
     */
    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(initLock) {
            if (initialized) return true
            try {
                appContext.assets.open("theta.dat").use { inputStream ->
                    // 必须固定 Locale：部分语言环境（德/法等）小数点是逗号，
                    // 默认 Locale 会让 nextBigDecimal 解析 "1.23E-4" 失败，
                    // 验证码识别静默初始化失败。
                    Scanner(inputStream).useLocale(Locale.US).use { scanner ->
                        for (i in 0 until 34) {
                            for (j in 0 until 337) {
                                weight[i][j] = scanner.nextBigDecimal()
                            }
                        }
                    }
                }
                initialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return initialized
    }

    fun recognize(bitmap: Bitmap): String {
        if (!ensureInitialized()) return ""
        val data = prepareData(bitmap)
        // 特征长度必须与权重矩阵列数（337 = 1 偏置 + 336 特征）匹配。
        // prepareData 的输出长度取决于验证码图片尺寸，只有特定尺寸（如 72x27）
        // 恰好等于 336；其它尺寸时越界访问会直接抛 ArrayIndexOutOfBoundsException。
        if (data.size < 4 || data[0].size < 336) return ""
        val x = Array(4) { Array(337) { BigDecimal.ZERO } }
        for (i in 0 until 4) {
            x[i][0] = BigDecimal.ONE
            for (j in 1 until 337) {
                x[i][j] = BigDecimal(data[i][j - 1].toDouble() / 255.0)
            }
        }

        val y = dot(weight, x)
        val p = sigmoid(y)

        val chr = CharArray(4)
        for (i in 0 until 4) {
            var max = 0.0
            var clas = 0
            for (j in 0 until 34) {
                if (p[i][j] > max) {
                    max = p[i][j]
                    clas = j
                }
            }
            chr[i] = if (clas <= 9) {
                (clas + 48).toChar()
            } else if (clas <= 23) {
                (clas + 87).toChar()
            } else {
                (clas + 88).toChar()
            }
        }

        return String(chr)
    }

    private fun sigmoid(y: Array<DoubleArray>): Array<DoubleArray> {
        val answer = Array(4) { DoubleArray(34) }
        for (i in y.indices) {
            for (j in y[i].indices) {
                answer[i][j] = 1.0 / (1.0 + Math.exp(-y[i][j]))
            }
        }
        return answer
    }

    private fun dot(weight: Array<Array<BigDecimal>>, x: Array<Array<BigDecimal>>): Array<DoubleArray> {
        val answer = Array(4) { DoubleArray(34) }
        for (i in 0 until 4) {
            for (j in 0 until 34) {
                var t = BigDecimal.ZERO
                for (k in 0 until 337) {
                    t = t.add(x[i][k].multiply(weight[j][k]))
                }
                answer[i][j] = t.toDouble()
            }
        }
        return answer
    }

    private fun prepareData(bitmap: Bitmap): Array<IntArray> {
        val xSize = bitmap.width
        val ySize = bitmap.height - 5
        if (xSize < 22 || ySize < 1) return Array(4) { IntArray(0) }
        val piece = (xSize - 22) / 8
        if (piece <= 0) return Array(4) { IntArray(0) }

        val centers = IntArray(4)
        for (i in 0 until 4) {
            centers[i] = 4 + piece * (2 * i + 1)
        }

        val matrix = Array(4) { IntArray((2 * piece + 4) * (ySize - 1)) }
        for (k in 0 until 4) {
            val center = centers[k]
            var ii = 0
            for (j in 1 until ySize) {
                var i = center - (piece + 2)
                while (i < center + (piece + 2)) {
                    matrix[k][ii++] = convertGreyDegree(bitmap.getPixel(i, j))
                    i++
                }
            }
        }
        return matrix
    }

    private fun convertGreyDegree(argb: Int): Int {
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff
        return (red * 30 + green * 59 + blue * 11 + 50) / 100
    }
}
