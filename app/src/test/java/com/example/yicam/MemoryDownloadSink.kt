/**
 * Responsible for: an in-memory DownloadSink and DownloadTarget for JVM tests.
 * Not responsible for: assertions (DownloaderTest.kt, FileDownloadTest.kt) or Android storage.
 */
package com.example.yicam

class MemoryDownloadSink : DownloadSink {
    private val bytes = java.io.ByteArrayOutputStream()
    private var tag: String? = null
    var completeUri: String = "memory:done"
    val content: ByteArray get() = bytes.toByteArray()

    override fun length(): Long = bytes.size().toLong()
    override fun etag(): String? = tag
    override fun setEtag(value: String?) {
        tag = value
    }
    override fun truncate() {
        bytes.reset()
    }
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        bytes.write(buffer, offset, count)
    }
    override fun close() {}
    override fun markComplete(): String = completeUri
}

class MemoryDownloadTarget(
    var freeBytes: Long = Long.MAX_VALUE,
    var completeUri: String? = null,
) : DownloadTarget {
    val sink = MemoryDownloadSink()
    override fun availableBytes(): Long = freeBytes
    override fun existingComplete(file: FileEntry): String? = completeUri
    override fun open(file: FileEntry): DownloadSink = sink
    override fun removeComplete(file: FileEntry) {
        completeUri = null
        sink.truncate()
    }
}
