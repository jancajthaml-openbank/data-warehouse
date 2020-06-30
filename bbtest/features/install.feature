Feature: Install package

  Scenario: install
    Given package dwh is installed
    Then  systemctl contains following active units
      | name    | type    |
      | dwh-app | service |
      | dwh     | service |
      | dwh     | path    |
