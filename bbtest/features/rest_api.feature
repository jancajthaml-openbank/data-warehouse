Feature: REST

  Scenario: Health API

    When I request HTTP http://127.0.0.1/health
      | key    | value |
      | method | GET   |
      """
      {
        "healthy": true,
        "graphql": true
      }
      """
    Then HTTP response is
      | key    | value |
      | status | 200   |
