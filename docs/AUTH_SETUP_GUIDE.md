# Configuring Auth

Temporal Kotlin SDK supports TLS, mutual TLS (mTLS), and API key authentication for secure connections to Temporal Cloud and self-hosted servers.

## Add dependencies

TLS support is included in the core module:

```kotlin
dependencies {
    implementation("com.surrealdev.temporal:core:$temporalVersion")
}
```

## Authentication options

Temporal Cloud supports two authentication methods:

| Method | Use case | Complexity |
|--------|----------|------------|
| **API Key** | Recommended for most teams | Simple |
| **mTLS** | Organizations with existing PKI | Requires certificate management |

## API Key authentication

API keys are the simplest way to authenticate with Temporal Cloud. TLS is automatically enabled.

### DSL

```kotlin
fun main() {
    val app = embeddedTemporal(
        configure = {
            connection {
                target = "https://myns.abc123.tmprl.cloud:7233"
                namespace = "myns.abc123"
                apiKey = System.getenv("TEMPORAL_API_KEY")
            }
        },
        module = {
            taskQueue("my-queue") {
                workflow<MyWorkflow>()
            }
        },
    )
    app.start(wait = true)
}
```

### YAML

```yaml
temporal:
  connection:
    target: "https://myns.abc123.tmprl.cloud:7233"
    namespace: "myns.abc123"
    apiKey: ${TEMPORAL_API_KEY}
  modules:
    - com.example.ModulesKt.myModule
```

Obtain API keys from the Temporal Cloud UI via **Settings → Service Accounts**.

## mTLS authentication

For organizations that prefer certificate-based authentication.

### Using embeddedTemporal

```kotlin
fun main() {
    val app = embeddedTemporal(
        configure = {
            connection {
                target = "https://myns.abc123.tmprl.cloud:7233"
                namespace = "myns.abc123"
                tls {
                    fromFiles(
                        clientCertPath = "/path/to/client.pem",
                        clientPrivateKeyPath = "/path/to/client-key.pem",
                    )
                }
            }
        },
        module = {
            taskQueue("my-queue") {
                workflow<MyWorkflow>()
            }
        },
    )
    app.start(wait = true)
}
```

### Using YAML configuration

Configuration is loaded automatically from `application.yaml` (or `temporal.yaml`) in your resources.

Create `src/main/resources/application.yaml`:

```yaml
temporal:
  connection:
    target: "https://myns.abc123.tmprl.cloud:7233"
    namespace: "myns.abc123"
    tls:
      clientCertPath: "/path/to/client.pem"
      clientKeyPath: "/path/to/client-key.pem"
  modules:
    - com.example.ModulesKt.myModule
```

With environment variables (Hoplite substitution):

```yaml
temporal:
  connection:
    target: "https://myns.abc123.tmprl.cloud:7233"
    namespace: "myns.abc123"
    tls:
      clientCertPath: ${TEMPORAL_CLIENT_CERT_PATH}
      clientKeyPath: ${TEMPORAL_CLIENT_KEY_PATH}
```

## Configure TLS for self-hosted servers

For servers using a private Certificate Authority, include the CA certificate.

### DSL

```kotlin
connection {
    target = "https://temporal.internal:7233"
    namespace = "default"
    tls {
        fromFiles(
            serverRootCaCertPath = "/path/to/ca.pem",
            clientCertPath = "/path/to/client.pem",
            clientPrivateKeyPath = "/path/to/client-key.pem",
        )
    }
}
```

### YAML

```yaml
temporal:
  connection:
    target: "https://temporal.internal:7233"
    namespace: "default"
    tls:
      serverCaCertPath: "/path/to/ca.pem"
      clientCertPath: "/path/to/client.pem"
      clientKeyPath: "/path/to/client-key.pem"
```

## Domain override

Use `domain` when connecting via IP or through a proxy where the certificate CN doesn't match:

```kotlin
connection {
    target = "https://10.0.0.50:7233"
    namespace = "default"
    tls {
        domain = "temporal.mycompany.com"
        fromFiles(serverRootCaCertPath = "/path/to/ca.pem")
    }
}
```

## Load certificates programmatically

For integration with secret managers:

```kotlin
connection {
    target = "https://myns.abc123.tmprl.cloud:7233"
    namespace = "myns.abc123"
    tls {
        clientCert = vault.read("secret/temporal/client-cert").toByteArray()
        clientPrivateKey = vault.read("secret/temporal/client-key").toByteArray()
    }
}
```

Or construct `TlsConfig` directly:

```kotlin
val tlsConfig = TlsConfig(
    clientCert = certBytes,
    clientPrivateKey = keyBytes,
)

connection {
    target = "https://myns.abc123.tmprl.cloud:7233"
    namespace = "myns.abc123"
    tls(tlsConfig)
}
```

## Workers and TLS

Workers inherit connection TLS settings from the Temporal Application. Currently overriding this is not supported.

## Certificate format

Certificates must be PEM-encoded:

```
-----BEGIN CERTIFICATE-----
MIIBkTCB+wIJAKHBfpEgcM...
-----END CERTIFICATE-----
```

Convert from PKCS#12:

```bash
openssl pkcs12 -in cert.p12 -clcerts -nokeys -out client.pem
openssl pkcs12 -in cert.p12 -nocerts -nodes -out client-key.pem
```

## Troubleshooting

| Error | Cause | Solution |
|-------|-------|----------|
| Certificate verify failed | Missing or wrong CA cert | Set `serverRootCaCertPath` to your CA |
| Handshake failed | Cert/key mismatch | Verify cert and key are a pair |
| Permission denied | Wrong namespace or unauthorized cert | Check namespace name and cert filters in Cloud UI |
| Connection refused | Wrong address or network issue | Verify address format and connectivity |

Verify certificate and key match:

```bash
openssl x509 -in client.pem -noout -modulus | md5
openssl rsa -in client-key.pem -noout -modulus | md5
# Output should match
```

## Client Auth

Client (outside a worker) auth follows a similar pattern (but doesn't support hoplite yaml config):

```kotlin
val client = TemporalClient.connect {                                                                                                                                  
  target = "https://myns.abc123.tmprl.cloud:7233"                                                                                                                    
  namespace = "myns.abc123"                                                                                                                                          
  tls {                                                                                                                                                              
      fromFiles(                                                                                                                                                     
          clientCertPath = "/path/to/client.pem",                                                                                                                    
          clientPrivateKeyPath = "/path/to/client-key.pem",                                                                                                          
      )                                                                                                                                                              
  }                                                                                                                                                                  
}
// or with api key
val client = TemporalClient.connect {
    target = "https://myns.abc123.tmprl.cloud:7233"
    namespace = "myns.abc123"
    apiKey = System.getenv("TEMPORAL_API_KEY")
}

val handle = client.startWorkflow(
    workflowType = "MyWorkflow",
    taskQueue = "my-queue",
    arg = "input",
)
val result = handle.result<String>()

client.close()
 ```

## See also

- [TlsConfig API](../core/src/main/kotlin/com/surrealdev/temporal/client/TlsConfig.kt) — Full configuration options
