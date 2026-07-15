package com.ck.wwmd222

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * orderToken 結構: base64( zlib( 960 字元小寫 hex 文字 ) )
 * 已用真實鳴潮封包驗證格式正確。
 *
 * === 本版行為：任何來源地區 → 一律改成 TW ===
 *
 * iOS md222 原版是「找到 BRL 才換 TW」(一對一)。
 * 但 iOS 註解指出 region block 固定在 hex offset 416：
 *   // At hex offset 416 (binary offset 208) is a 32-byte region block.
 * 既然位置固定，就無條件把該 slot 覆蓋成 TW，即可支援任意來源區。
 */
object OrderTokenPatcher {

    // 目標地區 block (32 bytes = 64 hex chars)。要改成別的目標區就改這裡。
    private const val TW_BLOCK =
        "04fcba8ebfccc7ee1a2df7d3561ae27e2867dd17d120e5c5cf407d89ee3aae57"

    // region block 在 hex 字串中的固定位置（iOS 註解: offset 416）
    private const val REGION_OFFSET = 416
    private const val REGION_LEN = 64  // 32 bytes = 64 hex chars

    /**
     * 唯讀：解出 orderToken 內部 offset 416 的地區 block（不修改）。
     * 失敗回傳 null。
     */
    fun readRegion(orderToken: String?): String? {
        if (orderToken.isNullOrEmpty()) return null
        return try {
            val compressed = android.util.Base64.decode(orderToken, android.util.Base64.DEFAULT)
            if (compressed.size < 10) return null
            val hexStr = String(inflate(compressed), Charsets.US_ASCII)
            if (hexStr.length != 960) return null
            hexStr.substring(REGION_OFFSET, REGION_OFFSET + REGION_LEN)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根據 region block 猜測地區。目前只認得 TW（iOS 抄來的樣本）。
     * 其他區都是未知密文，只能標記 UNKNOWN + 前綴供人工比對。
     */
    fun guessRegion(regionBlock: String?): String {
        if (regionBlock == null) return "?"
        return when (regionBlock) {
            TW_BLOCK -> "TW(台灣)"
            else -> "UNKNOWN(${regionBlock.take(12)}…)"
        }
    }

    fun patch(orderToken: String?, log: (String) -> Unit = {}): String? {
        if (orderToken.isNullOrEmpty()) return null

        // 1. base64 decode
        val compressed = try {
            android.util.Base64.decode(orderToken, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            log("orderToken base64 decode failed: ${e.message}")
            return null
        }
        if (compressed.size < 10) return null

        // 2. zlib 解壓
        val decompressed = try {
            inflate(compressed)
        } catch (e: Exception) {
            log("orderToken zlib decompress failed: ${e.message}")
            return null
        }

        // 3. 應為 960 字元 hex 文字
        val hexStr = String(decompressed, Charsets.US_ASCII)
        if (hexStr.length != 960) {
            log("orderToken unexpected hex length: ${hexStr.length}")
            return null
        }

        // 4. 檢查是否已經是 TW（避免重複處理）
        val current = hexStr.substring(REGION_OFFSET, REGION_OFFSET + REGION_LEN)
        if (current == TW_BLOCK) {
            log("orderToken region already TW, skip")
            return null
        }
        log("orderToken region @$REGION_OFFSET: $current -> TW")

        // 5. 無條件覆蓋固定位置的 region slot 成 TW
        val patchedHex = StringBuilder(hexStr)
            .replace(REGION_OFFSET, REGION_OFFSET + REGION_LEN, TW_BLOCK)
            .toString()
        if (patchedHex.length != 960) {
            log("orderToken length mismatch after patch")
            return null
        }

        // 6. 直接壓縮 960 字元 hex 文字
        //    （已用真實封包驗證：App 壓縮的是 hex 文字本身，非轉 binary。
        //     壓出長度與原始 orderToken 完全吻合 537 bytes，差距 0。）
        val recompressed = try {
            deflate(patchedHex.toByteArray(Charsets.US_ASCII))
        } catch (e: Exception) {
            log("orderToken recompress failed: ${e.message}")
            return null
        }

        // 8. base64 encode
        val newToken = android.util.Base64.encodeToString(
            recompressed, android.util.Base64.NO_WRAP
        )
        log("orderToken patched (${orderToken.length} -> ${newToken.length})")
        return newToken
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(8192)
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) break
                    if (inflater.needsInput()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        try {
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }
}
