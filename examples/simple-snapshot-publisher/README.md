# Simple Snapshot Publisher

A state channel that maps each received input to an instance of [org.example.SimpleSnapshot](src/main/scala/org/example/SimpleSnapshot.scala)

## Building a jar

Assembling a jar file with all it's `Compile` dependencies under the `target/scala-2.13` directory
```
sbt assembly
```

## Generating sample input files

Generating 10 binary input files, from `0.bin` to `9.bin` under the `/tmp` directory
```
sbt "generateInput /tmp 10"
```

Full command reference
```
sbt "generateInput --help"
```

## Posting inputs to the cluster

Uploading a `/tmp/0.bin` input file to a node running on `localhost`
```
curl -X POST http://localhost:9000/state-channel/DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS/input --data-binary "@/tmp/0.bin"
```