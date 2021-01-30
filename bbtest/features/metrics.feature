Feature: Metrics test

  Scenario: metrics measures expected stats

    Given restart unit "data-warehouse.service"
  	And   Directory /data/t_M exists
  	And   Directory /data/t_M/account/A/snapshot exists
  	And   Directory /data/t_M/account/B/snapshot exists
    And   File /data/t_M/account/A/snapshot/0000000000 contains
    """
    CZK FORMAT_T
    """
    And   File /data/t_M/account/B/snapshot/0000000000 contains
    """
    CZK FORMAT_T
    """
    And   File /data/t_M/transaction/TRN contains
    """
    committed
    TRX M A M B 2020-01-01T00:00:00Z 1 CZK
    """
    And   File /data/t_M/account/A/events/0000000000/1_1_TRN contains
    """
    1
    """
    And   File /data/t_M/account/B/events/0000000000/1_-1_TRN contains
    """
    1
    """

    Then metrics reports:
      | key                                | type  | value |
      | openbank.dwh.discovery.tenant      | count |     1 |
      | openbank.dwh.discovery.transaction | count |     2 |
      | openbank.dwh.discovery.transfer    | count |     2 |
      | openbank.dwh.discovery.account     | count |     2 |
