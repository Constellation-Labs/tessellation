package org.tessellation.schema

sealed trait Currency

trait DAG extends Currency

trait ETH extends Currency
