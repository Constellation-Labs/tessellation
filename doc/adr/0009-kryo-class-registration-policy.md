# 9. Kryo class registration policy

Date: 2022-02-17

## Status

Accepted

## Context

Having reliable serialization across different modules require using consistent and 
non-conflicting registration ids.

## Decision

* Each module containing classes that are a subject of a kryo serialization declares 
its kryo registrar - a public constant of type `Map[Class[_], Int]` representing kryo 
registration ids for specified classes.

* All modules except for `shared` can declare a kryo registrar only with classes that 
are defined in these modules. The `shared` module can additionally include classes from 
other libraries.

* Allocation of registration ids for modules is following:

| module     | min id | max id |
| ---------- | ------ | ------ |
| shared     | 100    | 299    |
| kernel     | 300    | 399    |
| sdk        | 400    | 499    |
| dag-shared | 500    | 599    |
| core       | 600    | 699    |
| dag-l1     | 700    | 799    |

* State-channels can use registration ids starting from 1000.


## Consequences

Registration ids will have to be adjusted.
