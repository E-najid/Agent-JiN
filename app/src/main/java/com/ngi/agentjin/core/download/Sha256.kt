package com.ngi.agentjin.core.download

import com.ngi.agentjin.core.crypto.toHex
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun ofFile(file: File): String = file.inputStream().use { ofStream(it) }

    fun ofStream(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1024 * 256)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }
}
