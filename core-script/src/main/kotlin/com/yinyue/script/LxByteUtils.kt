package com.yinyue.script

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 落雪 `lx.utils` 的等价实现（Node Buffer / crypto / zlib 的纯 JVM 版）。
 *
 * 字节统一用 [UByteArray] 与 JS 的 `Uint8Array` 互转（quickjs-kt 原生支持），
 * 脚本侧可当 Node Buffer 用（`.length`、下标、传给 buffer.from/request）。
 */
object LxByteUtils {

    private val rng = SecureRandom()

    // ── buffer ──

    /** Buffer.from(value[, encoding])：支持 字符串(含编码) / 字节数组 / 数字数组 */
    fun bufferFrom(value: Any?, encoding: String?): UByteArray? = when (value) {
        null -> null
        is String -> encode(value, encoding ?: "utf8")
        is UByteArray -> value
        is ByteArray -> value.toUByteArray()
        is Number -> ubyteArrayOf(value.toByte().toUByte())
        // JS 数组（Array<Number>）→ 逐字节
        is List<*> -> value.mapNotNull { (it as? Number)?.toByte()?.toUByte() }.toUByteArray()
        is Array<*> -> value.mapNotNull { (it as? Number)?.toByte()?.toUByte() }.toUByteArray()
        else -> null
    }

    /** bufToString(buf, format)：对齐落雪 `Buffer.from(buf, 'binary').toString(format)` */
    fun bufToString(value: Any?, format: String?): String {
        val bytes = when (value) {
            is String -> latin1Bytes(value) // 'binary' 语义：字符串按 latin1 取字节
            is UByteArray -> value.toByteArray()
            is ByteArray -> value
            is List<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
            is Array<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
            else -> ByteArray(0)
        }
        return decode(bytes, format ?: "utf8")
    }

    // ── crypto ──

    /** aesEncrypt(buffer, mode, key, iv)：mode 形如 'aes-128-ecb' / 'aes-128-cbc'（对齐 Node createCipheriv） */
    fun aesEncrypt(buffer: Any?, mode: String?, key: Any?, iv: Any?): UByteArray {
        val data = toBytes(buffer)
        val keyBytes = toBytes(key)
        val transformed = (mode ?: "aes-128-ecb").uppercase()
            .replace("AES-", "AES/")
            .let { m ->
                val parts = m.split("-")
                // aes-128-ecb → AES/ECB/NoPadding；aes-128-cbc → AES/CBC/NoPadding
                val modePart = parts.getOrElse(2) { "ECB" }
                "AES/$modePart/NoPadding"
            }
        val cipher = Cipher.getInstance(transformed)
        val secret = SecretKeySpec(keyBytes, "AES")
        if (transformed.contains("/CBC/")) {
            cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(toBytes(iv)))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secret)
        }
        return cipher.doFinal(data).toUByteArray()
    }

    /** rsaEncrypt(buffer, pemKey)：Node 的 RSA_NO_PADDING + 左侧补零到 128 字节 */
    fun rsaEncrypt(buffer: Any?, pemKey: String?): UByteArray {
        val data = toBytes(buffer)
        val padded = if (data.size < 128) ByteArray(128 - data.size) + data else data
        val pem = (pemKey ?: "").substringAfter("-----BEGIN PUBLIC KEY-----")
            .substringBefore("-----END PUBLIC KEY-----")
            .replace(Regex("\\s"), "")
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        return cipher.doFinal(padded).toUByteArray()
    }

    fun randomBytes(size: Int): UByteArray {
        if (size <= 0) return UByteArray(0)
        val out = ByteArray(size)
        rng.nextBytes(out)
        return out.toUByteArray()
    }

    // ── zlib（Node zlib.inflate/deflate 对应 zlib 包装格式，java 默认即 zlib）──

    fun zlibInflate(value: Any?): UByteArray {
        val data = toBytes(value)
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray().toUByteArray()
    }

    fun zlibDeflate(value: Any?): UByteArray {
        val data = toBytes(value)
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(data.size + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray().toUByteArray()
    }

    // ── 编码 ──

    private fun encode(s: String, encoding: String): UByteArray = when (encoding.lowercase()) {
        "base64" -> Base64.getDecoder().decode(s.trim())
        "hex" -> ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        "latin1", "binary", "ascii" -> latin1Bytes(s)
        "utf16le", "ucs2", "ucs-2" -> s.toByteArray(Charsets.UTF_16LE)
        else -> s.toByteArray(Charsets.UTF_8) // utf8 / utf-8 / 默认
    }.toUByteArray()

    private fun decode(bytes: ByteArray, format: String): String = when (format.lowercase()) {
        "base64" -> Base64.getEncoder().encodeToString(bytes)
        "hex" -> bytes.joinToString("") { "%02x".format(it) }
        "latin1", "binary", "ascii" -> String(bytes, Charsets.ISO_8859_1)
        "utf16le", "ucs2", "ucs-2" -> String(bytes, Charsets.UTF_16LE)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** 字符串按 latin1 取字节（每个 char 低 8 位），对齐 Node 'binary' 语义 */
    private fun latin1Bytes(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private fun toBytes(value: Any?): ByteArray = when (value) {
        is UByteArray -> value.toByteArray()
        is ByteArray -> value
        is String -> value.toByteArray(Charsets.UTF_8)
        is List<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
        is Array<*> -> value.mapNotNull { (it as? Number)?.toByte() }.toByteArray()
        else -> ByteArray(0)
    }
}
