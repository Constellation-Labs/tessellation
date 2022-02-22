# 9. Kryo class registration policy

Date: 2022-02-17

## Status

Accepted

## Context

Having reliable serialization across different modules require using consistent and 
non-conflicting registration ids.

## Decision

* Each module containing classes that are a subject of a Kryo serialization declares 
its Kryo registrar - a public constant of type `Map[Class[_], Int]` representing kryo 
registration ids for specified classes.

* All modules except for `shared` can declare a Kryo registrar only with classes that 
are defined in these modules. The `shared` module can additionally include classes from 
other libraries.

* Allocation of registration ids for modules is following:

| module     | min id | max id |
| ---------- | ------ | ------ |
| shared     | 300    | 399    |
| kernel     | 400    | 499    |
| sdk        | 500    | 599    |
| dag-shared | 600    | 699    |
| core       | 700    | 799    |
| dag-l1     | 800    | 899    |

Module allocation starts from 300 to avoid conflicts with classes already registered by 
`twitter-chill` - Scala extensions for Kryo library.

* State-channels can use registration ids starting from 1000.


## Consequences

Registration ids will have to be adjusted.
