package com.readcore.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTest {
    @Test fun acceptsMatchingRangeAndRejectsMismatchedOffsets() {
        assertEquals(2048L, resumeTotal(206, 1024, "bytes 1024-2047/2048", 1024))
        assertNull(resumeTotal(206, 1024, "bytes 512-2047/2048", 1536))
        assertNull(resumeTotal(206, 1024, "bytes 1024-1535/2048", 512))
    }
    @Test fun fullResponseRestartsFromZero() {
        assertEquals(2048L, resumeTotal(200, 1024, null, 2048))
        assertNull(resumeTotal(200, 1024, null, -1))
    }
    @Test fun resumesAndReplacesThePartialFile() = runBlocking {
        val file = kotlin.io.path.createTempFile().toFile()
        try {
            file.writeBytes("abc".toByteArray())
            writeDownload(file, 3, 6, "def".byteInputStream())
            assertArrayEquals("abcdef".toByteArray(), file.readBytes())
            writeDownload(file, 0, 2, "xy".byteInputStream())
            assertArrayEquals("xy".toByteArray(), file.readBytes())
        } finally { file.delete() }
    }
}
