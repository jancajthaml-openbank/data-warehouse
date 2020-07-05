Feature: Properly behaving units

  Scenario: lifecycle
    Given systemctl contains following active units
      | name    | type    |
      | dwh     | path    |
      | dwh     | service |
      | dwh-app | service |
    And unit "dwh-app.service" is running

    When stop unit "dwh-app.service"
    Then unit "dwh-app.service" is not running

    When start unit "dwh-app.service"
    Then unit "dwh-app.service" is running
    And I sleep for 10 seconds

    When restart unit "dwh-app.service"
    Then unit "dwh-app.service" is running
