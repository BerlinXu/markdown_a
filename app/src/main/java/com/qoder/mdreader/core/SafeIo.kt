package com.qoder.mdreader.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object SafeIo {
    const val DOCUMENT_LIMIT = 10 * 1024 * 1024
    fun read(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(out.size().toLong() + n <= limit) { "文件超过 ${limit / 1024 / 1024} MB 限制" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

data class TextFormat(val charset: String = "UTF-8", val bom: Boolean = false, val newline: String = "\n") {
    fun encode(text: String): ByteArray {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline)
        val encoder = Charset.forName(charset).newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(java.nio.CharBuffer.wrap(normalized))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val prefix = if (!bom) byteArrayOf() else when (charset) {
            "UTF-8" -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
            "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
            "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
            else -> byteArrayOf()
        }
        return prefix + bytes
    }
}

object TextCodec {
    fun decode(bytes: ByteArray, forcedCharset: String? = null): Pair<String, TextFormat> {
        var offset = 0
        var bom = false
        val explicit = when {
            bytes.size >= 3 && bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> {
                offset = 3; bom = true; "UTF-8"
            }
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> {
                offset = 2; bom = true; "UTF-16LE"
            }
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> {
                offset = 2; bom = true; "UTF-16BE"
            }
            else -> null
        }
        require(forcedCharset == null || explicit == null || forcedCharset == explicit) { "所选编码与文件 BOM 不一致" }
        val candidates = forcedCharset?.let { listOf(it) } ?: explicit?.let { listOf(it) } ?: listOf("UTF-8", "GB18030", "Big5", "windows-1252")
        for (name in candidates) {
            val text = runCatching {
                Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            }.getOrNull() ?: continue
            if (text.any { it == '\u0000' } || text.count { it.code < 32 && it !in "\n\r\t" } > 2) continue
            val newline = if (text.contains("\r\n")) "\r\n" else if (text.contains('\r')) "\r" else "\n"
            return text.replace("\r\n", "\n").replace('\r', '\n') to TextFormat(name, bom, newline)
        }
        error("无法可靠解码此文件，请先在其他编辑器转换为 UTF-8")
    }
}
