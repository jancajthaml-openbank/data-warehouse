package com.openbank.dwh.model


case class Account(tenant: String, name: String, currency: String, format: String, lastSynchronizedSnapshot: Int, lastSynchronizedEvent: Int)

case class AccountSnapshot(tenant: String, account: String, version: Int)

case class AccountEvent(tenant: String, account: String, status: Int, transaction: String, snapshotVersion: Int, version: Int)
