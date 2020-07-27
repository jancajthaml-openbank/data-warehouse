Feature: Graphql

  Scenario: Tenants Query

    Given Directory /tmp/reports/blackbox-tests/meta/t_demo exists

    When I request HTTP http://127.0.0.1/graphql
      | key    | value |
      | method | POST  |
      """
      {
        "query": "query GetTenants($limit: Int!, $offset: Int!) { tenants(limit: $limit, offset: $offset) { name } }",
        "variables": {
          "limit": 1,
          "offset": 0
        },
        "operationName": null
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

