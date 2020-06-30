Feature: Properly behaving units

  Scenario: lifecycle
    Given systemctl contains following active units
      | name    | type    |
      | dwh-app | service |
      | dwh     | path    |
      | dwh     | service |
    And unit "dwh-app.service" is running

    When stop unit "dwh-app.service"
    Then unit "dwh-app.service" is not running

    When start unit "dwh-app.service"
    Then unit "dwh-app.service" is running

    When restart unit "dwh-app.service"
    Then unit "dwh-app.service" is running