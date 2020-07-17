Feature: Install package

  Scenario: install
    Given package data-warehouse is installed
    Then  systemctl contains following active units
      | name               | type    |
      | data-warehouse-app | service |
      | data-warehouse     | service |
      | data-warehouse     | path    |
