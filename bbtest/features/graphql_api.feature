Feature: Graphql

  Scenario: Tenants Query

    Given Directory /data/t_TENANT exists

    When I request HTTP http://127.0.0.1/graphql
      | key    | value |
      | method | POST  |
      """
      query {
        tenants(limit: 1000, offset: 0) {
          name
        }
      }
      """
    Then HTTP response is
      | key    | value |
      | status | 200   |
      """
      {
        "data": {
          "tenants": [
            {
              "name": "TENANT"
            }
          ]
        }
      }
      """

  Scenario: Accounts Query

    Given Directory /data/t_TENANT_ACC/account/ACCOUNT/snapshot exists
    And   File /data/t_TENANT_ACC/account/ACCOUNT/snapshot/0000000000 contains
    """
    CZK FORMAT_T
    """

    When I request HTTP http://127.0.0.1/graphql
      | key    | value |
      | method | POST  |
      """
      query {
        accounts(tenant: "TENANT_ACC", limit: 1000, offset: 0) {
          name,
          currency,
          balance
        }
      }
      """
    Then HTTP response is
      | key    | value |
      | status | 200   |
      """
      {
        "data": {
          "accounts": [
            {
              "name": "ACCOUNT",
              "currency": "CZK",
              "balance": 0
            }
          ]
        }
      }
      """

  Scenario: Transfers Query

    Given Directory /data/t_TENANT_TRN/account/CREDIT/snapshot exists
    And   File /data/t_TENANT_TRN/account/CREDIT/snapshot/0000000000 contains
    """
    CZK FORMAT_F
    """
    And   Directory /data/t_TENANT_TRN/account/CREDIT/snapshot exists
    And   File /data/t_TENANT_TRN/account/DEBIT/snapshot/0000000000 contains
    """
    CZK FORMAT_F
    """
    And   File /data/t_TENANT_TRN/transaction/TRN contains
    """
    committed
    TRX TENANT_TRN CREDIT TENANT_TRN DEBIT 2020-01-01T00:00:00Z 1 CZK
    """
    And   File /data/t_TENANT_TRN/account/DEBIT/events/0000000000/1_-1_TRN contains
    """
    1
    """
    And   File /data/t_TENANT_TRN/account/CREDIT/events/0000000000/1_1_TRN contains
    """
    1
    """

    When I request HTTP http://127.0.0.1/graphql
      | key    | value |
      | method | POST  |
      """
      query {

        accounts(tenant: "TENANT_TRN", limit: 1000, offset: 0) {
          name,
          currency,
          balance
        }

        transfers(tenant: "TENANT_TRN", limit: 1000, offset: 0) {
          transaction,
          status
          transfer
        }

      }
      """
    Then HTTP response is
      | key    | value |
      | status | 200   |
      """
      {
        "data": {
          "accounts": [
            {
              "name": "CREDIT",
              "currency": "CZK",
              "balance": 1
            },
            {
              "name": "DEBIT",
              "currency": "CZK",
              "balance": -1
            }
          ],
          "transfers": [
            {
              "status": 1,
              "transaction": "TRN",
              "transfer": "TRX"
            }
          ]
        }
      }
      """
