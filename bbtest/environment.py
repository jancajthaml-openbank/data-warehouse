import os
from helpers.unit import UnitHelper


def after_feature(context, feature):
  context.unit.cleanup()


def before_all(context):
  context.unit = UnitHelper(context)

  os.system('mkdir -p /tmp/reports /tmp/reports/blackbox-tests /tmp/reports/blackbox-tests/logs /tmp/reports/blackbox-tests/metrics')
  os.system('rm -rf /tmp/reports/blackbox-tests/logs/*.log /tmp/reports/blackbox-tests/metrics/*.json')

  context.unit.download()
  context.unit.configure()


def after_all(context):
  context.unit.teardown()