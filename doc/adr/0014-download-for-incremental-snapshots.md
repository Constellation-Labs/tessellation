# 14. Download for incremental snapshots

Date: 2023-04-18

## Status

Accepted

## Context

Currently, the nodes joining the network are required to download the latest state of the network. However, in case of an extended absence, the node needs to have all incremental snapshots present on disk to determine the latest state of the network. This change requires a modification to the download process, as nodes need to fetch all missing incremental snapshots.

## Decision

After careful consideration of the current network state and the challenges of ensuring consistent and accurate data across all nodes, we have decided to modify the download process for joining nodes to fetch all missing incremental snapshots. This decision is based on the understanding that nodes need to have a complete and validated chain of incremental snapshots to determine the state of the network, especially when they have been absent for an extended period.

The modified download process involves several steps to ensure that nodes have a complete and validated chain of incremental snapshots. The first step is to determine the starting point by querying a peer node for the latest snapshot hash and ordinal. 

```mermaid
flowchart TD
  subgraph Determine starting point
    A[Get latest snapshot from the Joinee]
  end
  subgraph Download process
	B[Get snapshot with hash and ordinal X=(aaaa, 1234)]
  end
  A --> |latest snapshot: (aaaa, 1234)| B
```

The node then checks if it has all the incremental snapshots needed to reach the starting point and if not it begins downloading missing snapshots recursively from the latest snapshot to the genesis. All validated snapshots are stored in permanent storage, which contains a correct and validated chain of incremental snapshots. Temporary storage may contain potentially invalid or conflicting snapshots.

Once all the incremental snapshots have been downloaded, the node performs chain validation by applying subsequent incremental snapshots from the genesis to the latest snapshot to build the network state and validate the chain. If the latest snapshot on the network has been updated and the batch is not small enough (determined by the `k` parameter), the node repeats the download process for the next batch of snapshots. Otherwise, it can register for consensus.

```mermaid
flowchart TD
  subgraph Download process
	  A([Get snapshot X with hash and ordinal])
	  A --> B
	  B{"check if X is on disk<br/>in perm-storage<br/>(validated)"}
	  B --> |"Yes<br/><br/>pass map Ordinal->Hash for data from temp-storage"| C
	  B --> |No| D

	  subgraph validation [Chain validation]
	    C[[start chain validation]]
	    C1([end process - critical error])

	    C2{"is batch of size 1 or less<br/>(k parameter)"}
	    C --> |fails| C1
	    C --> |succeed| C2

	    C2C1([register for consensus])
	    C2C2{{repeat process for next batch}}
	    C2 --> |Yes| C2C1
	    C2 --> |No| C2C2
	  end
	  
	  C2C2 -.-> A

	  subgraph download [Download]
	    D{check if X<br/>is in temp storage}
	    E[read from temp-storage]
	    F[download X from network]

	    D --> |Yes| E
	    D --> |No| F

	    G{validate that hash bytes matches X}
	    F --> G

	    H[save it to temp-storage]

	    I{{repeat process for X.lastSnapshotHash,<br/>unless X is full snapshot}}

	    E --> I
	    H --> I

	    J[retry download from different source<br/>if validation failed]
	    G --> |Yes| H
	    G --> |No| J
	    J --> F
	  end
	  
	  I -.-> A
  end
```

This modified download process ensures that all joining nodes have a complete and validated chain of incremental snapshots and are able to determine the latest state of the network accurately. Although the initial node joining process may take longer, it ensures consistency and accuracy across all nodes and mitigates the risk of inconsistent and invalid data.

## Consequences

The modified download process for incremental snapshots will result in a longer initial node joining process. Fetching all missing incremental snapshots (and potentially the entire history in the worst case) will take longer than downloading a single latest snapshot.
