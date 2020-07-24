Feature: Properly behaving units

  Scenario: lifecycle
    Given systemctl contains following active units
      | name               | type    |
      | data-warehouse     | path    |
      | data-warehouse     | service |
      | data-warehouse-app | service |
    And unit "data-warehouse-app.service" is running

    When stop unit "data-warehouse-app.service"
    Then unit "data-warehouse-app.service" is not running

    When start unit "data-warehouse-app.service"
    Then unit "data-warehouse-app.service" is running

    When restart unit "data-warehouse-app.service"
    Then unit "data-warehouse-app.service" is running
