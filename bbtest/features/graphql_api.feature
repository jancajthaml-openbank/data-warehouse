Feature: Graphql

  Scenario: Tenants Query

    Given Directory reports/blackbox-tests/meta/t_TENANT exists

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

    Given Directory reports/blackbox-tests/meta/t_TENANT_ACC/account/ACCOUNT/snapshot exists
    And   File reports/blackbox-tests/meta/t_TENANT_ACC/account/ACCOUNT/snapshot/0000000000 contains
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

    Given Directory reports/blackbox-tests/meta/t_TENANT_TRN/account/CREDIT/snapshot exists
    And   File reports/blackbox-tests/meta/t_TENANT_TRN/account/CREDIT/snapshot/0000000000 contains
    """
    CZK FORMAT_F
    """
    And   Directory reports/blackbox-tests/meta/t_TENANT_TRN/account/CREDIT/snapshot exists
    And   File reports/blackbox-tests/meta/t_TENANT_TRN/account/DEBIT/snapshot/0000000000 contains
    """
    CZK FORMAT_F
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
              "balance": 0
            },
            {
              "name": "DEBIT",
              "currency": "CZK",
              "balance": 0
            }
          ]
        }
      }
      """
