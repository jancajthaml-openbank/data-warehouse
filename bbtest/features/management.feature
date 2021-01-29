Feature: System control

  Scenario: check units presence
    Given systemctl contains following active units
      | name               | type    |
      | data-warehouse     | service |
      | data-warehouse-app | service |

  Scenario: stop
    When stop unit "data-warehouse.service"
    Then unit "data-warehouse-app.service" is not running

  Scenario: start
    When start unit "data-warehouse.service"
    Then unit "data-warehouse-app.service" is running

  Scenario: restart
    When restart unit "data-warehouse.service"
    Then unit "data-warehouse-app.service" is running
