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
        // Chunked responses through a proxy have no length: fall back to the catalog size, else unknown.
        assertEquals(4096L, resumeTotal(200, 0, null, -1, expected = 4096))
        assertEquals(-1L, resumeTotal(200, 0, null, -1))
        // A 206 without Content-Length is still accepted when Content-Range is consistent.
        assertEquals(2048L, resumeTotal(206, 1024, "bytes 1024-2047/2048", -1))
    }
    @Test fun readsUnknownLengthToTheEnd() = runBlocking {
        val file = kotlin.io.path.createTempFile().toFile()
        try {
            assertEquals(5L, writeDownload(file, 0, -1, "hello".byteInputStream()))
            assertArrayEquals("hello".toByteArray(), file.readBytes())
        } finally { file.delete() }
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
