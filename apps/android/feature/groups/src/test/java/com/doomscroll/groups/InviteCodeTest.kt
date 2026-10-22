package com.doomscroll.groups

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InviteCodeTest {
    @Test
    fun `takes the code from a bare code or any shape of link`() {
        assertEquals("abc123", inviteCode("abc123"))
        assertEquals("abc123", inviteCode("  abc123 \n"))
        assertEquals("abc123", inviteCode("https://doomscroll.app/join/abc123"))
        assertEquals("abc123", inviteCode("https://doomscroll.app/join/abc123/"))
        assertEquals("abc123", inviteCode("https://doomscroll.app/join/abc123?utm_source=x#top"))
    }

    @Test
    fun `nothing usable gives an empty code`() {
        assertEquals("", inviteCode("   "))
        assertEquals("", inviteCode("/"))
    }
}
