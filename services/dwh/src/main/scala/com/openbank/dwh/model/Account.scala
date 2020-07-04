package com.openbank.dwh.model


case class Account(tenant: String, name: String, currency: String, format: String, lastSynchronizedSnapshot: Int)

case class AccountSnapshot(tenant: String, account: String, version: Int, last: Boolean)
