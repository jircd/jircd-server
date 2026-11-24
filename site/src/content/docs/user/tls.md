---
title: TLS connections
description: Connect securely to a jircd listener configured for TLS.
sidebar:
  order: 2
---

jircd accepts TLS connections on listeners that the administrator has configured with `tls: true`. TLS is not enabled by the default empty configuration. Ask the administrator for the TLS address and port; `6697` is a common choice, but the configured port is authoritative.

Select TLS in your IRC client's server connection settings before connecting. The client must trust the certificate presented by the server and use the hostname covered by that certificate. jircd loads the certificate and private key from the paths configured by the administrator. For a PEM file, jircd loads only the first certificate (the server certificate) and ignores any intermediate certificates in that file; a client may reject the connection if it does not already trust the required intermediate ([issue #167](https://github.com/jircd/jircd-server/issues/167)). The certificate file is PEM encoded and the private key must be PKCS#8.

A listener configuration has this shape:

```yaml
listeners:
  - port: 6697
    tls: true
    certPath: /path/to/cert.pem
    keyPath: /path/to/key.pem
```

The server does not upgrade an existing plaintext connection to TLS. Connect directly to the TLS listener. See the [container sample configuration](https://github.com/jircd/jircd-server/blob/main/deploy/container/jircd.yaml) for an example. The server expects the operator to provide a certificate and PKCS#8 private key in PEM files, or a PKCS12 keystore with its password; it does not issue certificates. With PEM, only the first certificate (the server certificate) is loaded; additional intermediate certificates in the file are ignored, so a client may reject the connection if it does not already trust the required intermediate ([issue #167](https://github.com/jircd/jircd-server/issues/167)). Operators normally obtain and renew certificates with their existing certificate authority or certificate-management tooling. See [listener configuration](../admin/configuration.md#listeners) and [container file permissions](../admin/install-container.md#what-to-know). For general connection steps, see [Connect to jircd](connecting.md).
