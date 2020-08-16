Feature: Graphql

  Scenario: Tenants Query

    Given Directory reports/blackbox-tests/meta/t_demo exists

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
              "name": "demo"
            }
          ]
        }
      }
      """

  Scenario: Accounts Query

    Given Directory reports/blackbox-tests/meta/t_TENANT/account/ACCOUNT/snapshot exists
    And   File reports/blackbox-tests/meta/t_TENANT/account/ACCOUNT/snapshot/0000000000 contains
    """
    CZK FORMAT_T
    """

    When I request HTTP http://127.0.0.1/graphql
      | key    | value |
      | method | POST  |
      """
      query {
        accounts(tenant: "TENANT", limit: 1000, offset: 0) {
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

