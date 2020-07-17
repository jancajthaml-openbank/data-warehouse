Feature: Uninstall package

  Scenario: uninstall
    Given package data-warehouse is uninstalled
    Then  systemctl does not contain following active units
      | name               | type    |
      | data-warehouse-app | service |
      | data-warehouse     | service |
      | data-warehouse     | path    |
