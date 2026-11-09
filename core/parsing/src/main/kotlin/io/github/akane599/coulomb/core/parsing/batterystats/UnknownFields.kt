// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.squareup.wire.FieldEncoding
import com.squareup.wire.Message
import com.squareup.wire.ProtoReader
import io.github.akane599.coulomb.core.parsing.ParseContext
import okio.Buffer
import okio.ByteString

/**
 * Counts each unknown field of [message] as `name.tag`; tags only, never values. [enumTag] names a
 * known enum field whose unknown values the caller counts itself, by value.
 */
internal fun ParseContext.unknownFields(
    name: String,
    message: Message<*, *>,
    enumTag: Int? = null,
) {
    val fields = message.unknownFields
    if (fields.size == 0) return
    forEachField(fields) { tag, _ -> if (tag != enumTag) unknown("$name.$tag") }
}

/** The raw value of an unknown varint field, e.g. an enum value the schema does not know. */
internal fun unknownVarint(
    message: Message<*, *>,
    tag: Int,
): Long? {
    val fields = message.unknownFields
    if (fields.size == 0) return null
    var value: Long? = null
    forEachField(fields) { t, v -> if (t == tag && v != null) value = v }
    return value
}

/** Calls [onField] with each field's tag and, for varints, its value. */
private inline fun forEachField(
    fields: ByteString,
    onField: (Int, Long?) -> Unit,
) {
    val reader = ProtoReader(Buffer().write(fields))
    val token = reader.beginMessage()
    while (true) {
        val tag = reader.nextTag()
        if (tag == -1) break
        if (reader.peekFieldEncoding() == FieldEncoding.VARINT) {
            onField(tag, reader.readVarint64())
        } else {
            onField(tag, null)
            reader.skip()
        }
    }
    reader.endMessageAndGetUnknownFields(token)
}
