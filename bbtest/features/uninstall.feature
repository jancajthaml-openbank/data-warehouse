Feature: Uninstall package

  Scenario: uninstall
    Given package data-warehouse is uninstalled
    Then  systemctl does not contain following active units
      | name                   | type    |
      | data-warehouse         | service |
      | data-warehouse-watcher | path    |
      | data-warehouse-watcher | service |
      | data-warehouse-app     | service |
