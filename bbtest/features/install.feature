Feature: Install package

  Scenario: install
    Given package data-warehouse is installed
    Then  systemctl contains following active units
      | name                   | type    |
      | data-warehouse         | service |
      | data-warehouse-watcher | path    |
      | data-warehouse-watcher | service |
      | data-warehouse-app     | service |
