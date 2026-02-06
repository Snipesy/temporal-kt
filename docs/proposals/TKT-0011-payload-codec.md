# TKT-0011: Payload Codec

Payload codecs transform payloads during serialization/deserialization. Common uses include encryption,
compression, or custom encoding schemes.

## PayloadCodec Interface

```kotlin
interface PayloadCodec {
    suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads
    suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads
}
```

`EncodedTemporalPayloads` is a distinct value class from `TemporalPayloads`, providing compile-time
safety: any code path that passes unencoded payloads where encoded ones are expected (or vice versa)
will produce a compiler error.

## Encryption Codec

```kotlin
class EncryptionCodec(
    private val keyId: String,
    private val keyProvider: suspend () -> SecretKey
) : PayloadCodec {
    companion object {
        private const val ENCODING_ENCRYPTED = "binary/encrypted"
        private val ENCODING_ENCRYPTED_BYTES = TemporalByteString.fromUtf8(ENCODING_ENCRYPTED)
        private val CIPHER_BYTES = TemporalByteString.fromUtf8("AES/GCM/NoPadding")
        private val KEY_ID_KEY = "encryption-key-id"
    }

    override suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads {
        val key = keyProvider()
        return EncodedTemporalPayloads(TemporalPayloads.of(payloads.payloads.map { payload ->
            val nonce = generateNonce()
            val encrypted = encrypt(payload.data, key, nonce)

            val meta = payload.metadataByteStrings.toMutableMap()
            meta[TemporalPayload.METADATA_ENCODING] = ENCODING_ENCRYPTED_BYTES
            meta["encryption-cipher"] = CIPHER_BYTES
            meta[KEY_ID_KEY] = TemporalByteString.fromUtf8(keyId)
            TemporalPayload.create(encrypted, meta)
        }).proto)
    }

    override suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads {
        val key = keyProvider()
        return TemporalPayloads.of(TemporalPayloads(payloads.proto).payloads.map { payload ->
            if (payload.encoding != ENCODING_ENCRYPTED) {
                return@map payload  // Pass through non-encrypted
            }

            val decrypted = decrypt(payload.data, key)
            val meta = payload.metadataByteStrings.toMutableMap()
            meta.remove(TemporalPayload.METADATA_ENCODING)
            meta.remove("encryption-cipher")
            meta.remove(KEY_ID_KEY)
            TemporalPayload.create(decrypted, meta)
        })
    }
}
```

## Compression Codec

Built-in `CompressionCodec` with configurable threshold:

```kotlin
class CompressionCodec(
    private val threshold: Int = 256
) : PayloadCodec {
    // Payloads smaller than threshold are passed through.
    // Compression is only applied if it actually reduces size.
    // See codec/CompressionCodec.kt for full implementation.
}
```

## Chaining Codecs

Codecs can be chained (compression -> encryption):

```kotlin
class ChainedCodec(
    private val codecs: List<PayloadCodec>
) : PayloadCodec {

    override suspend fun encode(payloads: TemporalPayloads): EncodedTemporalPayloads {
        var result = payloads
        for (codec in codecs) {
            result = TemporalPayloads(codec.encode(result).proto)
        }
        return EncodedTemporalPayloads(result.proto)
    }

    override suspend fun decode(payloads: EncodedTemporalPayloads): TemporalPayloads {
        var result = payloads
        for (codec in codecs.reversed()) {
            result = EncodedTemporalPayloads(codec.decode(result).proto)
        }
        return TemporalPayloads(result.proto)
    }
}

// Usage
val codec = ChainedCodec(listOf(
    CompressionCodec(),
    EncryptionCodec(keyId = "key-1") { fetchKeyFromKms("key-1") }
))
```

## Installation

```kotlin
fun TemporalApplication.module() {
    install(CodecPlugin) {
        custom(EncryptionCodec(
            keyId = "production-key",
            keyProvider = { kmsClient.getKey("production-key") }
        ))
    }

    // Or with chaining
    install(CodecPlugin) {
        chained {
            compression(threshold = 512)
            codec(EncryptionCodec(keyId = "production-key") { kmsClient.getKey(it) })
        }
    }

    taskQueue("secure-queue") {
        workflow<SecureWorkflow>()
    }
}
```

## Key Types

- `TemporalPayload` - Value class wrapping a single serialized payload. Use `TemporalPayload.create()`
  factories and `payload.encoding`, `payload.data`, `payload.dataSize`, `payload.metadataByteStrings`
  for zero-copy construction and inspection.
- `TemporalPayloads` - Value class wrapping a list of **unencoded** payloads (before `encode` / after `decode`).
- `EncodedTemporalPayloads` - Value class wrapping a list of **encoded** payloads (after `encode` / before `decode`).
  Distinct from `TemporalPayloads` to provide compile-time safety against missed encode/decode calls.
- `TemporalByteString` - Zero-cost wrapper around binary data, used for metadata values.

## Testing with Codecs

```kotlin
@Test
fun `test workflow with encryption`() = testTemporalApplication {
    install(CodecPlugin) {
        custom(EncryptionCodec(keyId = "test-key") { testKey })
    }

    application {
        secureModule()
    }

    val result = executeWorkflow<SecureWorkflow, Result>(sensitiveData)
    assertEquals(expected, result)
}
```
