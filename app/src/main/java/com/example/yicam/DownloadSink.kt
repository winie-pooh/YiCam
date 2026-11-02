/**
 * Responsible for: the contract of a place that receives the bytes of one download (length, truncate, write, finish).
 * Not responsible for: HTTP (Downloader.kt), MediaStore or a picked folder (MediaStoreSink.kt, TreeDownloadSink.kt) or choosing the destination (FileDownload.kt).
 */
package com.example.yicam

interface DownloadSink {
    fun length(): Long
    fun etag(): String?
    fun setEtag(value: String?)
    fun truncate()
    fun write(buffer: ByteArray, offset: Int, count: Int)
    fun close()
    /** Call only when the written size equals the size the server announced. */
    fun markComplete(): String
}

interface DownloadTarget {
    fun availableBytes(): Long
    fun existingComplete(file: FileEntry): String?
    fun open(file: FileEntry): DownloadSink
    fun removeComplete(file: FileEntry) {}
}
