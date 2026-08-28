package com.ifafu.kyzz.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorIdTest {

    @Test
    fun `同盐同学号结果稳定`() {
        val a = AuthorId.hashed("u_", "3241100001", "salt123")
        val b = AuthorId.hashed("u_", "3241100001", "salt123")
        assertEquals(a, b)
    }

    @Test
    fun `不同盐产生不同 ID 防枚举`() {
        // 攻击者即使知道学号，也因拿不到盐而无法离线枚举出目标 ID
        val a = AuthorId.hashed("u_", "3241100001", "saltA")
        val b = AuthorId.hashed("u_", "3241100001", "saltB")
        assertNotEquals(a, b)
    }

    @Test
    fun `输出格式与长度符合历史格式`() {
        val id = AuthorId.hashed("u_", "3241100001", "salt123")
        assertTrue(id.startsWith("u_"))
        assertEquals("u_".length + 16, id.length)
        assertTrue(id.substring(2).all { it in "0123456789abcdef" })
    }

    @Test
    fun `输出不含学号原文`() {
        val id = AuthorId.hashed("d_", "3241100001", "salt123")
        assertFalse(id.contains("3241100001"))
    }

    @Test
    fun `空学号返回空串`() {
        assertEquals("", AuthorId.hashed("u_", "", "salt123"))
    }

    @Test
    fun `与旧版无盐哈希结果不同`() {
        // 旧实现 = SHA-256(学号) 前 16 hex；加盐后必须不复现旧值（否则盐失效）
        val old = java.security.MessageDigest.getInstance("SHA-256")
            .digest("3241100001".toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val new = AuthorId.hashed("u_", "3241100001", "salt123")
        assertNotEquals("u_$old", new)
    }
}
