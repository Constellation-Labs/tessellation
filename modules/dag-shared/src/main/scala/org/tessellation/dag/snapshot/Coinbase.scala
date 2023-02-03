package org.tessellation.dag.snapshot

import org.tessellation.security.hash.Hash

object Coinbase {

  val value: String = "Nature loves courage. You make the commitment and nature will respond to that commitment " +
    "by removing impossible obstacles. Dream the impossible dream and the world will not grind you under, it will " +
    "lift you up. This is the trick. This is what all these teachers and philosophers who really counted, who really " +
    "touched the alchemical gold, this is what they understood. This is the shamanic dance in the waterfall. This is " +
    "how magick is done. By hurling yourself into the abyss and discovering it's a feather bed."

  val hash: Hash = Hash("9f3ed34c012794ef8dbc5ee6efa82228424259146c0b389f55982fc21197b421")
}
