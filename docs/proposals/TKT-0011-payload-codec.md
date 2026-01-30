# TKT-0011: Payload Codec

Payload codecs transform payloads during serialization/deserialization. Common uses include encryption,
compression, or custom encoding schemes.

## PayloadCodec Interface

```kotlin
interface PayloadCodec {
    suspend fun encode(payloads: List<Payload>): List<Payload>
    suspend fun decode(payloads: List<Payload>): List<Payload>
}
```

## Encryption Codec

```kotlin
class EncryptionCodec(
    private val keyId: String,
    private val keyProvider: suspend () -> SecretKey
) : PayloadCodec {

    override suspend fun encode(payloads: List<Payload>): List<Payload> {
        val key = keyProvider()
        return payloads.map { payload ->
            val nonce = generateNonce()
            val encrypted = encrypt(payload.data, key, nonce)

            Payload(
                data = encrypted,
                metadata = payload.metadata + mapOf(
                    "encoding" to "binary/encrypted",
                    "encryption-cipher" to "AES/GCM/NoPadding",
                    "encryption-key-id" to keyId
                )
            )
        }
    }

    override suspend fun decode(payloads: List<Payload>): List<Payload> {
        val key = keyProvider()
        return payloads.map { payload ->
            if (payload.metadata["encoding"] != "binary/encrypted") {
                return@map payload  // Pass through non-encrypted
            }

            val decrypted = decrypt(payload.data, key)
            Payload(
                data = decrypted,
                metadata = payload.metadata - setOf("encoding", "encryption-cipher", "encryption-key-id")
            )
        }
    }
}
```

## Compression Codec

```kotlin
class CompressionCodec(
    private val algorithm: CompressionAlgorithm = CompressionAlgorithm.ZSTD
) : PayloadCodec {

    override suspend fun encode(payloads: List<Payload>): List<Payload> {
        return payloads.map { payload ->
            val compressed = algorithm.compress(payload.data)

            // Only use compression if it actually reduces size
            if (compressed.size < payload.data.size) {
                Payload(
                    data = compressed,
                    metadata = payload.metadata + mapOf(
                        "encoding" to "binary/compressed",
                        "compression-algorithm" to algorithm.name
                    )
                )
            } else {
                payload
            }
        }
    }

    override suspend fun decode(payloads: List<Payload>): List<Payload> {
        return payloads.map { payload ->
            if (payload.metadata["encoding"] != "binary/compressed") {
                return@map payload
            }

            val algorithm = CompressionAlgorithm.valueOf(
                payload.metadata["compression-algorithm"] ?: error("Missing algorithm")
            )
            Payload(
                data = algorithm.decompress(payload.data),
                metadata = payload.metadata - setOf("encoding", "compression-algorithm")
            )
        }
    }
}
```

## Chaining Codecs

Codecs can be chained (compression → encryption):

```kotlin
class ChainedCodec(
    private val codecs: List<PayloadCodec>
) : PayloadCodec {

    override suspend fun encode(payloads: List<Payload>): List<Payload> {
        return codecs.fold(payloads) { p, codec -> codec.encode(p) }
    }

    override suspend fun decode(payloads: List<Payload>): List<Payload> {
        return codecs.reversed().fold(payloads) { p, codec -> codec.decode(p) }
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
    install(PayloadCodec) {
        codec = EncryptionCodec(
            keyId = "production-key",
            keyProvider = { kmsClient.getKey("production-key") }
        )
    }

    // Or with chaining
    install(PayloadCodec) {
        compression(CompressionAlgorithm.ZSTD)
        encryption {
            keyId = "production-key"
            keyProvider = { kmsClient.getKey(it) }
        }
    }

    taskQueue("secure-queue") {
        workflow<SecureWorkflow>()
    }
}
```

## Key Management

Production systems should use a KMS (Key Management Service):

```kotlin
class KmsEncryptionCodec(
    private val kmsClient: KmsClient,
    private val keyId: String
) : PayloadCodec {

    override suspend fun encode(payloads: List<Payload>): List<Payload> {
        val dataKey = kmsClient.generateDataKey(keyId)
        return payloads.map { payload ->
            Payload(
                data = encrypt(payload.data, dataKey.plaintext),
                metadata = payload.metadata + mapOf(
                    "encoding" to "binary/encrypted",
                    "encryption-key-id" to keyId,
                    "encrypted-data-key" to dataKey.ciphertext.base64()
                )
            )
        }
    }

    override suspend fun decode(payloads: List<Payload>): List<Payload> {
        return payloads.map { payload ->
            val encryptedDataKey = payload.metadata["encrypted-data-key"]
                ?: return@map payload

            val dataKey = kmsClient.decrypt(encryptedDataKey.decodeBase64())
            Payload(
                data = decrypt(payload.data, dataKey),
                metadata = payload.metadata - setOf("encoding", "encryption-key-id", "encrypted-data-key")
            )
        }
    }
}
```

## Testing with Codecs

```kotlin
@Test
fun `test workflow with encryption`() = testTemporalApplication {
    install(PayloadCodec) {
        encryption {
            keyId = "test-key"
            keyProvider = { testKey }
        }
    }

    application {
        secureModule()
    }

    val result = executeWorkflow<SecureWorkflow, Result>(sensitiveData)
    assertEquals(expected, result)
}
```
