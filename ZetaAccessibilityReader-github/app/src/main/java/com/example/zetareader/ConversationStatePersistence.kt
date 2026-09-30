package com.example.zetareader

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** 앱 재시작 뒤에도 A/B/C 대화방 상태를 각각 복원하기 위한 내부 저장 형식. */
internal object ConversationStatePersistence {
    private const val FILE_SUFFIX = ".conversation"
    private const val MAGIC = 0x5A435331 // ZCS1
    private const val VERSION = 2
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024
    private const val MAX_MESSAGES = 100_000

    fun loadAll(directory: File): List<ConversationState> {
        if (!directory.exists()) return emptyList()
        return directory
            .listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
    }

    fun save(directory: File, state: ConversationState) {
        directory.mkdirs()
        val destination = fileFor(directory, state.conversationId)
        val temporary = File(directory, "${destination.name}.tmp")

        DataOutputStream(
            BufferedOutputStream(FileOutputStream(temporary)),
        ).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeString(state.conversationId)
            output.writeLong(state.createdAtEpochMs)
            output.writeLong(state.updatedAtEpochMs)
            output.writeNullableString(state.jevSessionDate)
            output.writeNullableString(state.jevStartMessageId)
            output.writeNullableLong(state.lastVisitEndedAtEpochMs)
            output.writeInt(state.messages.size)
            state.messages.forEach { stored ->
                output.writeNullableString(stored.record.id)
                output.writeInt(stored.record.role.ordinal)
                output.writeString(stored.record.speaker)
                output.writeString(stored.record.text)
                output.writeInt(stored.record.kind.ordinal)
                output.writeInt(stored.record.segmentIndex)
                output.writeLong(stored.firstSeenAtEpochMs)
                output.writeLong(stored.lastUpdatedAtEpochMs)
            }
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            error("기존 대화 상태 파일을 교체할 수 없습니다: ${destination.name}")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            error("대화 상태 파일을 저장할 수 없습니다: ${destination.name}")
        }
    }

    fun delete(directory: File, conversationId: String) {
        val file = fileFor(directory, conversationId)
        if (file.exists()) file.delete()
    }

    private fun read(file: File): ConversationState = DataInputStream(
        BufferedInputStream(FileInputStream(file)),
    ).use { input ->
        check(input.readInt() == MAGIC) { "알 수 없는 대화 상태 파일입니다." }
        val version = input.readInt()
        check(version in 1..VERSION) { "지원하지 않는 대화 상태 버전입니다." }
        val conversationId = input.readString()
        val createdAt = input.readLong()
        val updatedAt = input.readLong()
        val jevSessionDate = if (version >= 2) input.readNullableString() else null
        val jevStartMessageId = if (version >= 2) input.readNullableString() else null
        val lastVisitEndedAt = if (version >= 2) input.readNullableLong() else null
        val count = input.readInt()
        check(count in 0..MAX_MESSAGES) { "메시지 개수가 올바르지 않습니다: $count" }

        val messages = MutableList(count) {
            val id = input.readNullableString()
            val role = enumValue(input.readInt(), MessageRole.entries)
            val speaker = input.readString()
            val text = input.readString()
            val kind = enumValue(input.readInt(), MessageKind.entries)
            val segmentIndex = input.readInt()
            val firstSeenAt = input.readLong()
            val lastUpdatedAt = input.readLong()
            StoredMessage(
                record = MessageRecord(
                    id = id,
                    role = role,
                    speaker = speaker,
                    text = text,
                    kind = kind,
                    segmentIndex = segmentIndex,
                ),
                firstSeenAtEpochMs = firstSeenAt,
                lastUpdatedAtEpochMs = lastUpdatedAt,
            )
        }

        ConversationState(
            conversationId = conversationId,
            createdAtEpochMs = createdAt,
            messages = messages,
            updatedAtEpochMs = updatedAt,
            jevSessionDate = jevSessionDate,
            jevStartMessageId = jevStartMessageId,
            lastVisitEndedAtEpochMs = lastVisitEndedAt,
        )
    }

    private fun fileFor(directory: File, conversationId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest$FILE_SUFFIX")
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size !in 0..MAX_STRING_BYTES) {
            throw EOFException("문자열 길이가 올바르지 않습니다: $size")
        }
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun <T> enumValue(index: Int, values: List<T>): T =
        values.getOrNull(index) ?: throw EOFException("enum 값이 올바르지 않습니다: $index")
}
