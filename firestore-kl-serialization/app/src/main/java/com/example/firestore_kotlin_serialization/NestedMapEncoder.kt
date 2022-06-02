// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.example.firestore_kotlin_serialization

import com.example.firestore_kotlin_serialization.annotations.KDocumentId
import com.example.firestore_kotlin_serialization.annotations.KServerTimestamp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

class NestedMapEncoder(
    private var map: MutableMap<Int, MutableMap<String, Any?>> = mutableMapOf(),
    private var depth: Int = 0,
    private var list: MutableList<Any> = mutableListOf(),
    private var descriptor: SerialDescriptor? = null,
) : AbstractEncoder() {

    private val ROOT_LEVEL: Int = 1

    init {
        if (depth == ROOT_LEVEL) map[ROOT_LEVEL] = mutableMapOf()
    }

    fun serializedResult() = map[ROOT_LEVEL]!!

    private var elementIndex: Int = 0

    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        debugPrint("LogTest", ">>>>>>> There is a Enum for me to encode")
        debugPrint("LogTest", ">>>>>>> $enumDescriptor")
        val encodeValue = enumDescriptor.elementNames
        debugPrint("LogTest", ">>>>>>>elements names are ${encodeValue.toList()}")
        debugPrint("LogTest", ">>>>>>>elements annotations are ${enumDescriptor.getElementAnnotations(0)}")
        encodeValue(encodeValue.toList().get(index))
    }

    fun encodeTimestamp(value: Timestamp) {
        val key: String = list[elementIndex++] as String
        map[depth]!!.put(key, value)
    }

    override fun encodeNull() {
        debugPrint("LogTest", ">>>>>>> There is a Null I need to endocde ================")
        // If the null value is annoted with KServerTimestamp, I put the field value
        val elementAnnotations: List<Annotation> = descriptor!!.getElementAnnotations(elementIndex)
        val elementKind = descriptor!!.getElementDescriptor(elementIndex).kind
        val elementName = descriptor!!.serialName
        val replaceServerTimestamp = elementAnnotations?.any { it is KServerTimestamp }
        val key: String = list[elementIndex++] as String
        if (replaceServerTimestamp) {
            debugPrint("LogTest", ">>>========= Going to put server timestamp field value ==========================")

            map[depth]!!.put(key, FieldValue.serverTimestamp())
        } else {
            map[depth]!!.put(key, null)
        }
    }

    override fun encodeValue(value: Any) {
        debugPrint("LogTest", "========= encodeValue is: $value ==========================")
        val elementAnnotations: List<Annotation> = descriptor!!.getElementAnnotations(elementIndex)
        val elementKind = descriptor!!.getElementDescriptor(elementIndex).kind
        val elementName = descriptor!!.serialName
        debugPrint("LogTest", "========= encode element Kind and serialName is: $elementKind  and $elementName ==========================")
        val skipDocumentId = elementAnnotations?.any { it is KDocumentId }
        if (skipDocumentId && elementKind != PrimitiveKind.STRING) {
            // TODO: DocumentReference is not a primitive type, so I need to make it @Serializable so I can have it
            throw IllegalArgumentException("Field is annotated with @DocumentId but is class $elementKind instead of String or DocumentReference.")
        }

        if (skipDocumentId && depth == ROOT_LEVEL) {
            elementIndex++ // skip encoding any field annotated with @DocumentId at root level
            // TODO: Andy want to to skip encoding any field with @DocumentId, not only at root level, but also at any level
        } else {
            val key: String = list[elementIndex++] as String
            map[depth]!!.put(key, value)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (depth != ROOT_LEVEL) {
            map.remove(depth--)
        }
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        // TODO: @DocumentID and @ServerTimeStamp should not be applied on Structures
        var listOfElementsToBeEncoded: MutableList<Any> = mutableListOf()
        debugPrint("LogTest", "========= Map's Descriptor is: $descriptor ==========================")
        if (!descriptor.elementNames.toList().isNullOrEmpty()) {
            listOfElementsToBeEncoded = descriptor.elementNames.toList() as MutableList<Any>
            debugPrint("LogTest", "========= listOfElementsToBeEncoded is: $listOfElementsToBeEncoded ==========================")
            // save the descriptor for each of the elements need to be encoded:
            for (i in 0..listOfElementsToBeEncoded.size - 1) {
                debugPrint("LogTest", "========= element $i's descriptor is: ${descriptor.getElementDescriptor(i).kind} ==========================")
            }
        }

        if (depth == 0) {
            return NestedMapEncoder(
                map,
                depth + 1,
                listOfElementsToBeEncoded,
                descriptor = descriptor
            )
        }
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                var nextDepth = depth + 1
                map[nextDepth] = mutableMapOf()
                val innerMapKey: String = list[elementIndex++] as String
                map[depth]!!.put(innerMapKey, map[nextDepth])
                return NestedMapEncoder(
                    map,
                    depth + 1,
                    listOfElementsToBeEncoded,
                    descriptor = descriptor
                )
            }
            StructureKind.LIST -> {
                var emptyList = mutableListOf<Any>()
                val innerListKey: String = list[elementIndex++] as String
                map[depth]!!.put(innerListKey, emptyList)
                return NestListEncoder(map, depth + 0, emptyList)
            }
            else -> {
                throw Exception(
                    "Incorrect format of nested object provided: <$descriptor.kind>"
                )
            }
        }
    }
}

class NestListEncoder(
    private var map: MutableMap<Int, MutableMap<String, Any?>> = mutableMapOf(),
    private var depth: Int = 0,
    private var list: MutableList<Any> = mutableListOf(),
) : AbstractEncoder() {

    override val serializersModule: SerializersModule = EmptySerializersModule

    private var elementIndex: Int = 0

    override fun encodeValue(value: Any) {
        list.add(value)
        elementIndex++
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                var nextDepth = depth + 1
                map[nextDepth] = mutableMapOf()
                list.add(map[nextDepth]!!)
                return NestedMapEncoder(
                    map, nextDepth,
                    descriptor.elementNames.toList() as MutableList<Any>,
                    descriptor = descriptor,
                )
            }
            else -> {
                throw Exception(
                    "Incorrect format of nested object provided: <$descriptor.kind>"
                )
            }
        }
    }
}

fun <T> encodeToMap(serializer: SerializationStrategy<T>, value: T): MutableMap<String, Any?> {
    val encoder = NestedMapEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.serializedResult()
}

inline fun <reified T> encodeToMap(value: T): MutableMap<String, Any?> =
    encodeToMap(serializer(), value)

inline fun <reified T> DocumentReference.set(value: T) {
    val encodedMap = encodeToMap<T>(value)
    set(encodedMap)
}

inline fun <reified T> DocumentReference.setData(value: T) {
    val encodedMap = encodeToMap<T>(value)
    set(encodedMap)
}

inline fun <reified T> DocumentReference.serialSet(value: T) {
    val encodedMap = encodeToMap<T>(value)
    set(encodedMap)
}