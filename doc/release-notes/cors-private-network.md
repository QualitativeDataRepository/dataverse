# CORS support for Private Networks

A new JVM setting `dataverse.cors.allow-private-network` has been added to support Cross-Origin Resource Sharing (CORS) when Dataverse is deployed on a private or internal network.

When set to `true`, Dataverse will include the `Access-Control-Allow-Private-Network: true` header in CORS responses. This is required by some modern browsers when a website on a public network (like `gdcc.github.io`) attempts to make a request to a server on a private network.

**Caution:** This setting should only be used when strictly necessary, for example, when using browser-based previewers from `gdcc.github.io` to access a Dataverse instance running on an internal test server.
