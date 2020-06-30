Feature: Uninstall package

  Scenario: uninstall
    Given package dwh is uninstalled
    Then  systemctl does not contain following active units
      | name    | type    |
      | dwh-app | service |
      | dwh     | service |
      | dwh     | path    |
