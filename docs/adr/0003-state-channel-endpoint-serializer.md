# 3. State-Channel Endpoint Serializer

Date: 2022-01-12

## Status

Accepted

## Context

Question: Can state-channel L0 Cell endpoint receive data in format other than
binary serialized by Kryo?

Use case: For L0 Token it would be easier to allow for JSON input so then 3rd
party developer can use other programming languages than JVM.

## Decision

We will not allow for other serializers than Kryo, due to security reasons.

## Consequences

If someone would like to send the JSON input to the state-channel endpoint, one
needs to create for example a proxy-server that will translate JSON to binary
Kryo data and forward it to the network.
